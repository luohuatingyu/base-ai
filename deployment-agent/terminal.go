package main

import (
	"bytes"
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	"github.com/creack/pty"
	"github.com/gorilla/websocket"
)

var terminalSlots = make(chan struct{}, 32)

type terminalMessage struct {
	Type string `json:"type"`
	Data string `json:"data"`
	Cols uint16 `json:"cols"`
	Rows uint16 `json:"rows"`
}

// validTerminalSize 限制终端尺寸，避免非法窗口及资源消耗。
func validTerminalSize(cols, rows uint16) bool {
	return cols >= 2 && cols <= 500 && rows >= 1 && rows <= 200
}

// terminal 仅接受内部认证连接，使用有界 PTY 会话承载交互 SSH。
func (agent *agent) terminal(response http.ResponseWriter, incoming *http.Request) {
	if subtle.ConstantTimeCompare([]byte(incoming.Header.Get("X-Internal-Token")), []byte(agent.token)) != 1 {
		http.Error(response, "Unauthorized", http.StatusUnauthorized)
		return
	}
	select {
	case terminalSlots <- struct{}{}:
		defer func() { <-terminalSlots }()
	default:
		http.Error(response, "Session limit", http.StatusTooManyRequests)
		return
	}
	upgrader := websocket.Upgrader{HandshakeTimeout: 10 * time.Second}
	connection, err := upgrader.Upgrade(response, incoming, nil)
	if err != nil {
		return
	}
	defer connection.Close()
	connection.SetReadLimit(128 * 1024)
	_ = connection.SetReadDeadline(time.Now().Add(10 * time.Second))
	var input request
	if err := connection.ReadJSON(&input); err != nil || input.Mode != "SSH" {
		return
	}
	ctx, cancel := context.WithTimeout(incoming.Context(), 30*time.Minute)
	defer cancel()
	command, cleanup, err := terminalCommand(ctx, input)
	if err != nil {
		_ = connection.WriteJSON(map[string]string{"type": "error", "data": "SSH_CONNECTION_FAILED"})
		return
	}
	defer cleanup()
	terminal, err := pty.StartWithSize(command, &pty.Winsize{Cols: 80, Rows: 24})
	if err != nil {
		return
	}
	defer terminal.Close()
	defer func() { cancel(); _ = command.Wait() }()
	var writer sync.Mutex
	write := func(kind int, data []byte) error {
		writer.Lock()
		defer writer.Unlock()
		_ = connection.SetWriteDeadline(time.Now().Add(10 * time.Second))
		return connection.WriteMessage(kind, data)
	}
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			_ = connection.Close()
		case <-done:
		}
	}()
	go func() {
		defer connection.Close()
		buffer := make([]byte, 8192)
		for {
			count, readErr := terminal.Read(buffer)
			if count > 0 && write(websocket.BinaryMessage, buffer[:count]) != nil {
				return
			}
			if readErr != nil {
				return
			}
		}
	}()
	connection.SetReadLimit(32 * 1024)
	for {
		_ = connection.SetReadDeadline(time.Now().Add(5 * time.Minute))
		var message terminalMessage
		if err := connection.ReadJSON(&message); err != nil {
			return
		}
		switch message.Type {
		case "input":
			if len(message.Data) > 16384 {
				return
			}
			if _, err := terminal.Write([]byte(message.Data)); err != nil {
				return
			}
		case "resize":
			if !validTerminalSize(message.Cols, message.Rows) {
				return
			}
			if err := pty.Setsize(terminal, &pty.Winsize{Cols: message.Cols, Rows: message.Rows}); err != nil {
				return
			}
		default:
			return
		}
	}
}

// terminalCommand 复用凭据隔离，仅启动登录 Shell；已配置指纹时强制校验主机公钥。
func terminalCommand(ctx context.Context, input request) (*exec.Cmd, func(), error) {
	command, cleanup, err := dataSyncRemoteCommand(ctx, input, "")
	if err != nil {
		return nil, cleanup, err
	}
	command.Args = command.Args[:len(command.Args)-1]
	options := []string{"-tt", "-o", "LogLevel=ERROR"}
	if input.HostKey != "" {
		if !fingerprintPattern.MatchString(input.HostKey) {
			cleanup()
			return nil, func() {}, errors.New("SSH_HOST_KEY_INVALID")
		}
		scan := exec.CommandContext(ctx, "ssh-keyscan", "-T", "5", "-p", fmt.Sprint(input.Port), input.Host)
		keys, scanErr := scan.Output()
		if scanErr != nil {
			cleanup()
			return nil, func() {}, errors.New("SSH_HOST_KEY_FAILED")
		}
		matched, matchErr := matchingHostKeys(keys, input.HostKey, func(line string) (string, error) {
			fingerprint := exec.CommandContext(ctx, "ssh-keygen", "-lf", "-", "-E", "sha256")
			fingerprint.Stdin = bytes.NewBufferString(line)
			output, err := fingerprint.Output()
			return string(output), err
		})
		if matchErr != nil || len(matched) == 0 {
			cleanup()
			return nil, func() {}, errors.New("SSH_HOST_KEY_MISMATCH")
		}
		directory, dirErr := os.MkdirTemp("", "terminal-host-")
		if dirErr != nil {
			cleanup()
			return nil, func() {}, dirErr
		}
		previousCleanup := cleanup
		cleanup = func() { previousCleanup(); _ = os.RemoveAll(directory) }
		knownHosts := filepath.Join(directory, "known_hosts")
		if err := os.WriteFile(knownHosts, matched, 0600); err != nil {
			cleanup()
			return nil, func() {}, err
		}
		options = append(options, "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile="+knownHosts)
	}
	command.Args = append([]string{command.Args[0]}, append(options, command.Args[1:]...)...)
	command.Env = append(command.Env, "TERM=xterm-256color")
	return command, cleanup, nil
}
