package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

// TestTerminalSize 覆盖尺寸边界，拒绝零值及超大窗口。
func TestTerminalSize(t *testing.T) {
	for _, scenario := range []struct {
		cols, rows uint16
		valid      bool
	}{
		{2, 1, true}, {500, 200, true}, {80, 24, true}, {0, 24, false}, {1, 24, false}, {80, 0, false}, {501, 24, false}, {80, 201, false},
	} {
		if validTerminalSize(scenario.cols, scenario.rows) != scenario.valid {
			t.Fatalf("unexpected size result: %+v", scenario)
		}
	}
}

// TestTerminalAuthentication 内部令牌缺失及错误时不得升级连接，LOCAL 不得启动 Shell。
func TestTerminalAuthentication(t *testing.T) {
	agent := &agent{token: "internal-terminal-test-token"}
	server := httptest.NewServer(http.HandlerFunc(agent.terminal))
	defer server.Close()
	for _, token := range []string{"", "invalid"} {
		connection, response, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), http.Header{"X-Internal-Token": []string{token}})
		if err == nil {
			connection.Close()
			t.Fatal("unauthorized upgrade accepted")
		}
		if response.StatusCode != http.StatusUnauthorized {
			t.Fatal(response.StatusCode)
		}
		response.Body.Close()
	}
	connection, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), http.Header{"X-Internal-Token": []string{agent.token}})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if err := connection.WriteJSON(request{Mode: "LOCAL"}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := connection.ReadMessage(); err == nil {
		t.Fatal("LOCAL shell accepted")
	}
}

// TestTerminalCommand 保留认证方式，启动交互会话且不拼接远端命令；错误指纹须拒绝。
func TestTerminalCommand(t *testing.T) {
	input := request{Mode: "SSH", Host: "example.test", Port: 22, Username: "deploy", AuthType: "PASSWORD", Password: "secret"}
	command, cleanup, err := terminalCommand(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
	defer cleanup()
	if command.Args[len(command.Args)-1] != "deploy@example.test" || command.Args[1] != "-tt" {
		t.Fatal(command.Args)
	}
	if strings.Contains(strings.Join(command.Args, " "), "secret") {
		t.Fatal("password leaked in argv")
	}
	input.HostKey = "invalid"
	if _, cleanup, err := terminalCommand(context.Background(), input); err == nil {
		cleanup()
		t.Fatal("invalid fingerprint accepted")
	}
}
