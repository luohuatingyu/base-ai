package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
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

// newSupervisor 使用替身命令执行器创建隔离 Supervisor。
func newSupervisor(runner commandRunner) *supervisorController {
	return &supervisorController{runner: runner, projectDir: "/workspace", composeFile: "/workspace/docker-compose.yml",
		envFile: "/workspace/.env", operations: map[string]adapterState{}}
}

// TestState 将健康、启动中、停止和畸形 Compose 输出映射为稳定接口状态。
func TestState(t *testing.T) {
	runner := &fakeRunner{outputs: []string{
		`[{"State":"running","Health":"healthy"}]`,
		`{"State":"running","Health":"starting"}`,
		`[{"State":"exited","Health":""}]`,
		`not-json`,
	}}
	supervisor := newSupervisor(runner)
	for _, expected := range []string{"RUNNING", "STARTING", "STOPPED", "FAILED"} {
		if actual := supervisor.state(context.Background(), "N8N").Status; actual != expected {
			t.Fatalf("expected %s, got %s", expected, actual)
		}
	}
}

// TestControlUsesAllowlistedComposeService 验证独立来源无法注入命令参数。
func TestControlUsesAllowlistedComposeService(t *testing.T) {
	runner := &fakeRunner{}
	supervisor := newSupervisor(runner)
	state, status := supervisor.setEnabled("N8N", true)
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
	if rejected, rejectedStatus := supervisor.setEnabled("N8N;rm -rf /", true); rejectedStatus != http.StatusBadRequest || rejected.Status != "INVALID" {
		t.Fatalf("malicious source was not rejected: %d %#v", rejectedStatus, rejected)
	}
}

// TestFailedOperationPreservesBoundedError 验证命令细节不会返回给调用方。
func TestFailedOperationPreservesBoundedError(t *testing.T) {
	runner := &fakeRunner{outputs: []string{"secret command output"}, errors: []error{errors.New("failed")}}
	supervisor := newSupervisor(runner)
	supervisor.setEnabled("DIFY", false)
	waitForCalls(t, runner, 1)
	var state adapterState
	for deadline := time.Now().Add(time.Second); time.Now().Before(deadline); {
		supervisor.mu.Lock()
		state = supervisor.operations["DIFY"]
		supervisor.mu.Unlock()
		if state.Status == "FAILED" {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if state.Error != "ADAPTER_STOP_FAILED" || strings.Contains(state.Error, "secret") {
		t.Fatalf("unexpected failure: %#v", state)
	}
}

type fakeSupervisor struct {
	healthErr error
	state     adapterState
	status    int
	err       error
	mu        sync.Mutex
	calls     []string
}

// Health 返回预设的隔离控制层健康状态。
func (f *fakeSupervisor) Health(context.Context) error { return f.healthErr }

// State 记录固定来源状态查询。
func (f *fakeSupervisor) State(_ context.Context, source string) (adapterState, int, error) {
	f.mu.Lock()
	f.calls = append(f.calls, "GET "+source)
	f.mu.Unlock()
	return f.state, f.status, f.err
}

// SetEnabled 记录经过解析的类型化布尔命令。
func (f *fakeSupervisor) SetEnabled(_ context.Context, source string, enabled bool) (adapterState, int, error) {
	f.mu.Lock()
	f.calls = append(f.calls, "PUT "+source+" "+map[bool]string{true: "true", false: "false"}[enabled])
	f.mu.Unlock()
	return f.state, f.status, f.err
}

// newManager 使用强测试令牌创建不持有命令执行器的网络 Manager。
func newManager(supervisor supervisorAPI) *managerController {
	return &managerController{supervisor: supervisor, token: strings.Repeat("t", 32), now: time.Now}
}

// signManagerRequest 使用与 Backend 相同的规范串签名测试请求。
func signManagerRequest(request *http.Request, token string, body string, nonce string) {
	timestamp := strconv.FormatInt(time.Now().Unix(), 10)
	digestBytes := sha256.Sum256([]byte(body))
	digest := hex.EncodeToString(digestBytes[:])
	canonical := request.Method + "\n" + request.URL.RequestURI() + "\n" + timestamp + "\n" + nonce + "\n" + digest
	mac := hmac.New(sha256.New, []byte(token))
	_, _ = mac.Write([]byte(canonical))
	request.Header.Set("X-Internal-Timestamp", timestamp)
	request.Header.Set("X-Internal-Nonce", nonce)
	request.Header.Set("X-Internal-Target", request.URL.RequestURI())
	request.Header.Set("X-Internal-Content-SHA256", digest)
	request.Header.Set("X-Internal-Signature", hex.EncodeToString(mac.Sum(nil)))
}

// TestManagerAuthenticationAndTypedForwarding 覆盖鉴权、严格解析和类型化转发。
func TestManagerAuthenticationAndTypedForwarding(t *testing.T) {
	upstream := &fakeSupervisor{state: adapterState{Source: "N8N", Status: "ENABLING"}, status: http.StatusAccepted}
	manager := newManager(upstream)
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
	body := `{"enabled":true}`
	request := httptest.NewRequest(http.MethodPut, "/api/adapters/n8n", strings.NewReader(body))
	signManagerRequest(request, manager.token, body, "0123456789abcdef0123456789abcdef")
	response := httptest.NewRecorder()
	manager.serveHTTP(response, request)
	if response.Code != http.StatusAccepted {
		t.Fatalf("typed request returned %d", response.Code)
	}
	upstream.mu.Lock()
	calls := append([]string(nil), upstream.calls...)
	upstream.mu.Unlock()
	if len(calls) != 1 || calls[0] != "PUT N8N true" {
		t.Fatalf("unexpected supervisor calls: %#v", calls)
	}
}

// TestManagerRejectsMalformedCommandsBeforeSupervisor 覆盖未知字段、尾随 JSON 和恶意来源。
func TestManagerRejectsMalformedCommandsBeforeSupervisor(t *testing.T) {
	upstream := &fakeSupervisor{state: adapterState{Source: "N8N", Status: "ENABLING"}, status: http.StatusAccepted}
	manager := newManager(upstream)
	for index, pathAndBody := range [][2]string{
		{"/api/adapters/N8N", `{"enabled":true,"service":"evil"}`},
		{"/api/adapters/N8N", `{"enabled":true}{"enabled":false}`},
		{"/api/adapters/N8N%3Brm-rf", `{"enabled":true}`},
	} {
		request := httptest.NewRequest(http.MethodPut, pathAndBody[0], strings.NewReader(pathAndBody[1]))
		signManagerRequest(request, manager.token, pathAndBody[1], fmt.Sprintf("%032x", index+1))
		response := httptest.NewRecorder()
		manager.serveHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("%s returned %d", pathAndBody[0], response.Code)
		}
	}
	upstream.mu.Lock()
	defer upstream.mu.Unlock()
	if len(upstream.calls) != 0 {
		t.Fatalf("invalid input reached supervisor: %#v", upstream.calls)
	}
}

// TestManagerBoundsSupervisorFailures 验证隔离层错误不会把底层细节返回网络调用方。
func TestManagerBoundsSupervisorFailures(t *testing.T) {
	upstream := &fakeSupervisor{err: errors.New("/workspace/.env: secret"), status: http.StatusInternalServerError}
	manager := newManager(upstream)
	request := httptest.NewRequest(http.MethodGet, "/api/adapters/DIFY", nil)
	signManagerRequest(request, manager.token, "", "abcdef0123456789abcdef0123456789")
	response := httptest.NewRecorder()
	manager.serveHTTP(response, request)
	if response.Code != http.StatusServiceUnavailable || strings.Contains(response.Body.String(), "workspace") {
		t.Fatalf("unexpected bounded response: %d %s", response.Code, response.Body.String())
	}
	var state adapterState
	_ = json.Unmarshal(response.Body.Bytes(), &state)
	if state.Error != "ADAPTER_SUPERVISOR_UNAVAILABLE" {
		t.Fatalf("unexpected failure state: %#v", state)
	}
}

// TestUnixSupervisorClientUsesPrivateSocket 验证 Manager 客户端只通过指定 Unix Socket 通信。
func TestUnixSupervisorClientUsesPrivateSocket(t *testing.T) {
	socketPath := filepath.Join(t.TempDir(), "supervisor.sock")
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		t.Fatal(err)
	}
	upstream := newSupervisor(&fakeRunner{outputs: []string{`[{"State":"running","Health":"healthy"}]`}})
	httpServer := &http.Server{Handler: http.HandlerFunc(upstream.serveHTTP)}
	go func() { _ = httpServer.Serve(listener) }()
	defer httpServer.Close()
	client := newUnixSupervisorClient(socketPath)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	state, status, err := client.State(ctx, "N8N")
	if err != nil || status != http.StatusOK || state.Status != "RUNNING" {
		t.Fatalf("unexpected socket response: %d %#v %v", status, state, err)
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
