package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeRunner struct {
	mu      sync.Mutex
	outputs []string
	errors  []error
	calls   [][]string
}

// Run 记录完整固定命令，并返回下一个预设结果。
func (f *fakeRunner) Run(_ context.Context, arguments ...string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, append([]string(nil), arguments...))
	index := len(f.calls) - 1
	var output string
	var err error
	if index < len(f.outputs) {
		output = f.outputs[index]
	}
	if index < len(f.errors) {
		err = f.errors[index]
	}
	return output, err
}

// newController 使用强测试令牌创建隔离的控制器。
func newController(runner commandRunner) *controller {
	return &controller{runner: runner, projectDir: "/workspace", composeFile: "/workspace/docker-compose.yml",
		envFile: "/workspace/.env", token: strings.Repeat("t", 32), operations: map[string]adapterState{}}
}

// TestState 将健康、启动中、停止和畸形 Compose 输出映射为稳定接口状态。
func TestState(t *testing.T) {
	runner := &fakeRunner{outputs: []string{
		`[{"State":"running","Health":"healthy"}]`,
		`{"State":"running","Health":"starting"}`,
		`[{"State":"exited","Health":""}]`,
		`not-json`,
	}}
	manager := newController(runner)
	for _, expected := range []string{"RUNNING", "STARTING", "STOPPED", "FAILED"} {
		if actual := manager.state("N8N").Status; actual != expected {
			t.Fatalf("expected %s, got %s", expected, actual)
		}
	}
}

// TestControlUsesAllowlistedComposeService 验证独立来源无法注入命令参数。
func TestControlUsesAllowlistedComposeService(t *testing.T) {
	runner := &fakeRunner{}
	manager := newController(runner)
	state, status := manager.setEnabled("N8N", true)
	if status != http.StatusAccepted || state.Status != "ENABLING" {
		t.Fatalf("unexpected response: %d %#v", status, state)
	}
	waitForCalls(t, runner, 1)
	runner.mu.Lock()
	command := strings.Join(runner.calls[0], " ")
	runner.mu.Unlock()
	if !strings.HasSuffix(command, "--no-build --no-deps n8n-plugin-worker") ||
		strings.Contains(command, "dify-plugin-worker") || strings.Contains(command, " --build ") {
		t.Fatalf("unexpected command: %s", command)
	}
	if rejected, rejectedStatus := manager.setEnabled("N8N;rm -rf /", true); rejectedStatus != http.StatusBadRequest || rejected.Status != "INVALID" {
		t.Fatalf("malicious source was not rejected: %d %#v", rejectedStatus, rejected)
	}
}

// TestFailedOperationPreservesBoundedError 验证命令细节不会返回给调用方。
func TestFailedOperationPreservesBoundedError(t *testing.T) {
	runner := &fakeRunner{outputs: []string{"secret command output"}, errors: []error{errors.New("failed")}}
	manager := newController(runner)
	manager.setEnabled("DIFY", false)
	waitForCalls(t, runner, 1)
	var state adapterState
	for deadline := time.Now().Add(time.Second); time.Now().Before(deadline); {
		manager.mu.Lock()
		state = manager.operations["DIFY"]
		manager.mu.Unlock()
		if state.Status == "FAILED" {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if state.Error != "ADAPTER_STOP_FAILED" || strings.Contains(state.Error, "secret") {
		t.Fatalf("unexpected failure: %#v", state)
	}
}

// TestHTTPAuthenticationAndValidation 覆盖健康检查、令牌校验和严格 JSON 解析。
func TestHTTPAuthenticationAndValidation(t *testing.T) {
	manager := newController(&fakeRunner{outputs: []string{""}})
	health := httptest.NewRecorder()
	manager.serveHTTP(health, httptest.NewRequest(http.MethodGet, "/health", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("health returned %d", health.Code)
	}
	unauthorized := httptest.NewRecorder()
	manager.serveHTTP(unauthorized, httptest.NewRequest(http.MethodGet, "/api/adapters/N8N", nil))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized request returned %d", unauthorized.Code)
	}
	invalid := httptest.NewRequest(http.MethodPut, "/api/adapters/N8N", strings.NewReader(`{"enabled":true,"service":"evil"}`))
	invalid.Header.Set("X-Internal-Token", manager.token)
	invalidResponse := httptest.NewRecorder()
	manager.serveHTTP(invalidResponse, invalid)
	if invalidResponse.Code != http.StatusBadRequest {
		t.Fatalf("invalid body returned %d", invalidResponse.Code)
	}
	var body map[string]string
	_ = json.Unmarshal(invalidResponse.Body.Bytes(), &body)
	if body["error"] != "REQUEST_INVALID" {
		t.Fatalf("unexpected body: %#v", body)
	}
}

// waitForCalls 短暂等待异步控制命令到达替身执行器。
func waitForCalls(t *testing.T, runner *fakeRunner, count int) {
	t.Helper()
	for deadline := time.Now().Add(time.Second); time.Now().Before(deadline); {
		runner.mu.Lock()
		actual := len(runner.calls)
		runner.mu.Unlock()
		if actual >= count {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("runner did not receive %d calls", count)
}
