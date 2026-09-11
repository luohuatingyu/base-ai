//go:build integration

package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestSSHIntegration 使用临时 sshd 验证真实认证、主机密钥轮换和数据流传输。
func TestSSHIntegration(t *testing.T) {
	if _, err := os.Stat("/.dockerenv"); err != nil || os.Geteuid() != 0 {
		t.Fatal("integration tests require a disposable Docker container running as root")
	}
	root := t.TempDir()
	password := "test'password$with spaces"
	passphrase := "key'phrase$with spaces"
	command := exec.Command("chpasswd")
	command.Stdin = strings.NewReader("root:" + password + "\n")
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("configure isolated account: %v: %s", err, output)
	}
	generate := func(name, phrase string) string {
		path := filepath.Join(root, name)
		if output, err := exec.Command("ssh-keygen", "-q", "-t", "ed25519", "-N", phrase, "-f", path).CombinedOutput(); err != nil {
			t.Fatalf("generate test key: %v: %s", err, output)
		}
		return path
	}
	key := generate("client", "")
	encryptedKey := generate("encrypted-client", passphrase)
	publicKey, err := os.ReadFile(key + ".pub")
	if err != nil {
		t.Fatal(err)
	}
	encryptedPublicKey, err := os.ReadFile(encryptedKey + ".pub")
	if err != nil {
		t.Fatal(err)
	}
	authorized := filepath.Join(root, "authorized_keys")
	if err := os.WriteFile(authorized, append(publicKey, encryptedPublicKey...), 0600); err != nil {
		t.Fatal(err)
	}
	readKey := func(path string) string {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		return string(data)
	}
	for index, method := range []string{"publickey", "password", "publickey,password", "publickey,password"} {
		t.Run(fmt.Sprintf("server-%d-%s", index, method), func(t *testing.T) {
			hostKey := generate(fmt.Sprintf("host-%d", index), "")
			config := filepath.Join(root, fmt.Sprintf("sshd-%d", index))
			contents := fmt.Sprintf("Port 2222\nListenAddress 127.0.0.1\nHostKey %s\nPidFile %s\nAuthorizedKeysFile %s\nStrictModes no\nPermitRootLogin yes\nPasswordAuthentication yes\nPubkeyAuthentication yes\nAuthenticationMethods %s\nUsePAM no\n", hostKey, filepath.Join(root, "sshd.pid"), authorized, method)
			if err := os.WriteFile(config, []byte(contents), 0600); err != nil {
				t.Fatal(err)
			}
			server := exec.Command("/usr/sbin/sshd", "-D", "-e", "-f", config)
			if err := server.Start(); err != nil {
				t.Fatal(err)
			}
			defer func() { _ = server.Process.Kill(); _ = server.Wait() }()
			ready := false
			for attempt := 0; attempt < 100; attempt++ {
				connection, err := net.DialTimeout("tcp", "127.0.0.1:2222", 50*time.Millisecond)
				if err == nil {
					_ = connection.Close()
					ready = true
					break
				}
				time.Sleep(20 * time.Millisecond)
			}
			if !ready {
				t.Fatal("temporary SSH server did not start")
			}
			input := request{Mode: "SSH", Host: "127.0.0.1", Port: 2222, Username: "root", AuthType: "KEY_PASSWORD", PrivateKey: readKey(key), Password: password, HostKey: "obsolete fingerprint"}
			if index == 0 {
				input.AuthType = "KEY"
			}
			if index == 1 {
				input.AuthType = "PASSWORD"
			}
			if index == 3 {
				input.PrivateKey = readKey(encryptedKey)
				input.Passphrase = passphrase
			}
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			output, err := runRemoteCommand(ctx, input, "printf AUTHENTICATED")
			if err != nil || !strings.HasSuffix(output, "AUTHENTICATED") {
				t.Fatalf("real SSH authentication: %v: %s", err, output)
			}
			stream, cleanup, err := dataSyncRemoteCommand(ctx, input, "cat")
			if err != nil {
				t.Fatal(err)
			}
			defer cleanup()
			stream.Stdin = strings.NewReader("DATA_SYNC_PAYLOAD")
			output, err = commandOutput(stream)
			if err != nil || !strings.HasSuffix(output, "DATA_SYNC_PAYLOAD") {
				t.Fatalf("SSH stdin transport: %v: %s", err, output)
			}
			if index >= 2 {
				invalid := input
				invalid.Password = "wrong"
				if output, err := runRemoteCommand(ctx, invalid, "printf UNEXPECTED"); err == nil || strings.Contains(output, "UNEXPECTED") {
					t.Fatal("wrong password authenticated")
				}
				invalid = input
				invalid.PrivateKey = readKey(generate(fmt.Sprintf("wrong-%d", index), ""))
				if _, err := runRemoteCommand(ctx, invalid, "true"); err == nil {
					t.Fatal("wrong private key authenticated")
				}
			}
			if index == 3 {
				timeoutContext, stop := context.WithTimeout(context.Background(), time.Second)
				defer stop()
				if _, err := runRemoteCommand(timeoutContext, input, "sleep 30"); err == nil || timeoutContext.Err() != context.DeadlineExceeded {
					t.Fatal("SSH execution did not stop at its deadline")
				}
				input.Passphrase = "wrong"
				if _, err := runRemoteCommand(ctx, input, "true"); err == nil {
					t.Fatal("wrong passphrase authenticated")
				}
			}
		})
	}
}
