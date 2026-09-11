//go:build integration

package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// exerciseTerminal 在真实 sshd 上验证持久目录、终端尺寸、中断及主机指纹，复用四种认证场景。
func exerciseTerminal(t *testing.T, input request, hostKey string) {
	t.Helper()
	fingerprint, err := exec.Command("ssh-keygen", "-lf", hostKey, "-E", "sha256").Output()
	if err != nil {
		t.Fatal(err)
	}
	input.HostKey = strings.Fields(string(fingerprint))[1]
	agent := &agent{token: "integration-terminal-token"}
	server := httptest.NewServer(http.HandlerFunc(agent.terminal))
	defer server.Close()
	connection, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), http.Header{"X-Internal-Token": []string{agent.token}})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if err := connection.WriteJSON(input); err != nil {
		t.Fatal(err)
	}
	readUntil := func(expected string) {
		t.Helper()
		_ = connection.SetReadDeadline(time.Now().Add(15 * time.Second))
		var output strings.Builder
		for !strings.Contains(output.String(), expected) {
			_, data, err := connection.ReadMessage()
			if err != nil {
				t.Fatalf("terminal missing %q: %v output=%q", expected, err, output.String())
			}
			output.Write(data)
			if output.Len() > 100000 {
				t.Fatal("unbounded output")
			}
		}
	}
	send := func(data string) {
		t.Helper()
		if err := connection.WriteJSON(terminalMessage{Type: "input", Data: data}); err != nil {
			t.Fatal(err)
		}
	}
	send("stty -echo; printf 'REA''DY\\n'\r")
	readUntil("READY")
	send("cd /tmp\r")
	send("printf 'DIR='; pwd\r")
	readUntil("DIR=/tmp")
	if err := connection.WriteJSON(terminalMessage{Type: "resize", Cols: 112, Rows: 37}); err != nil {
		t.Fatal(err)
	}
	send("stty size\r")
	readUntil("37 112")
	send("printf '中''文\\n'\r")
	readUntil("中文")
	send("sleep 30\r")
	time.Sleep(100 * time.Millisecond)
	send("\x03")
	send("printf 'INTERRUP''TED\\n'\r")
	readUntil("INTERRUPTED")
	send("exit\r")
	_ = connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		if _, _, err := connection.ReadMessage(); err != nil {
			break
		}
	}
	invalid := input
	invalid.HostKey = "SHA256:" + strings.Repeat("A", 43)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, cleanup, err := terminalCommand(ctx, invalid); err == nil {
		cleanup()
		t.Fatal("mismatched fingerprint accepted")
	}
	t.Log(fmt.Sprintf("interactive SSH verified: %s", input.AuthType))
}
