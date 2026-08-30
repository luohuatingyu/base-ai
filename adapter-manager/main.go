package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const maximumResponseBytes = 4096

var internalHexPattern = regexp.MustCompile(`^[a-f0-9]+$`)

var adapterServices = map[string]string{
	"N8N":  "n8n-plugin-worker",
	"DIFY": "dify-plugin-worker",
}

type commandRunner interface {
	Run(context.Context, ...string) (string, error)
}

type dockerCommandRunner struct{}

// Run 仅执行 Supervisor 生成的固定参数，并返回限制长度的诊断信息。
func (dockerCommandRunner) Run(ctx context.Context, arguments ...string) (string, error) {
	command := exec.CommandContext(ctx, "docker", arguments...)
	output, err := command.CombinedOutput()
	text := strings.TrimSpace(string(output))
	if len(text) > 2000 {
		text = text[len(text)-2000:]
	}
	return text, err
}

type adapterState struct {
	Source string `json:"source"`
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}

type composeContainer struct {
	State  string `json:"State"`
	Health string `json:"Health"`
}

type supervisorController struct {
	runner      commandRunner
	projectDir  string
	composeFile string
	envFile     string
	mu          sync.Mutex
	operations  map[string]adapterState
}

// composeArguments 构建两个适配器共用且不可由请求修改的 Compose 命令前缀。
func (c *supervisorController) composeArguments() []string {
	return []string{"compose", "--project-directory", c.projectDir, "-f", c.composeFile, "--env-file", c.envFile}
}

// state 返回进行中的操作，或通过 Docker Compose 解析容器实际状态。
func (c *supervisorController) state(parent context.Context, source string) adapterState {
	service, ok := adapterServices[source]
	if !ok {
		return adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"}
	}
	c.mu.Lock()
	operation, active := c.operations[source]
	c.mu.Unlock()
	if active {
		return operation
	}
	ctx, cancel := context.WithTimeout(parent, 10*time.Second)
	defer cancel()
	arguments := append(c.composeArguments(), "ps", "--all", "--format", "json", service)
	output, err := c.runner.Run(ctx, arguments...)
	if err != nil {
		return adapterState{Source: source, Status: "FAILED", Error: "ADAPTER_STATUS_FAILED"}
	}
	if strings.TrimSpace(output) == "" {
		return adapterState{Source: source, Status: "STOPPED"}
	}
	containers, err := decodeContainers(output)
	if err != nil || len(containers) != 1 {
		return adapterState{Source: source, Status: "FAILED", Error: "ADAPTER_STATUS_INVALID"}
	}
	container := containers[0]
	if strings.EqualFold(container.State, "running") {
		if container.Health != "" && !strings.EqualFold(container.Health, "healthy") {
			return adapterState{Source: source, Status: "STARTING"}
		}
		return adapterState{Source: source, Status: "RUNNING"}
	}
	return adapterState{Source: source, Status: "STOPPED"}
}

// decodeContainers 兼容不同 Compose 版本输出的 JSON 数组与逐行 JSON 格式。
func decodeContainers(output string) ([]composeContainer, error) {
	var array []composeContainer
	if json.Unmarshal([]byte(output), &array) == nil {
		return array, nil
	}
	var result []composeContainer
	for _, line := range strings.Split(output, "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		var item composeContainer
		if err := json.Unmarshal([]byte(line), &item); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	if len(result) == 0 {
		return nil, errors.New("empty compose status")
	}
	return result, nil
}

// setEnabled 为单个硬编码服务串行提交异步启动或停止操作。
func (c *supervisorController) setEnabled(source string, enabled bool) (adapterState, int) {
	service, ok := adapterServices[source]
	if !ok {
		return adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"}, http.StatusBadRequest
	}
	c.mu.Lock()
	if current, active := c.operations[source]; active {
		if current.Status == "ENABLING" || current.Status == "DISABLING" {
			c.mu.Unlock()
			return current, http.StatusConflict
		}
		delete(c.operations, source)
	}
	status := "DISABLING"
	if enabled {
		status = "ENABLING"
	}
	operation := adapterState{Source: source, Status: status}
	c.operations[source] = operation
	c.mu.Unlock()
	go c.runOperation(source, service, enabled)
	return operation, http.StatusAccepted
}

// runOperation 使用固定参数调用 Compose，且仅保留有限的非敏感失败码。
func (c *supervisorController) runOperation(source string, service string, enabled bool) {
	timeout := 45 * time.Second
	arguments := c.composeArguments()
	if enabled {
		timeout = 10 * time.Minute
		// 适配器镜像必须由发布流程预构建，Supervisor 不通过 Docker Socket 执行构建。
		arguments = append(arguments, "--profile", "plugin-adapters", "up", "-d", "--no-build", "--no-deps", service)
	} else {
		arguments = append(arguments, "stop", "-t", "10", service)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	_, err := c.runner.Run(ctx, arguments...)
	c.mu.Lock()
	defer c.mu.Unlock()
	if err != nil {
		code := "ADAPTER_START_FAILED"
		if !enabled {
			code = "ADAPTER_STOP_FAILED"
		}
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			code += "_TIMEOUT"
		}
		c.operations[source] = adapterState{Source: source, Status: "FAILED", Error: code}
		log.Printf("adapter operation failed source=%s code=%s", source, code)
		return
	}
	delete(c.operations, source)
}

// serveHTTP 仅在私有 Unix Socket 上提供健康、状态和固定目标开关接口。
func (c *supervisorController) serveHTTP(response http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodGet && request.URL.Path == "/health" {
		writeJSON(response, http.StatusOK, map[string]string{"status": "UP"})
		return
	}
	source, ok := sourceFromPath(request.URL.Path)
	if !ok {
		writeJSON(response, http.StatusNotFound, map[string]string{"error": "NOT_FOUND"})
		return
	}
	if _, allowed := adapterServices[source]; !allowed {
		writeJSON(response, http.StatusBadRequest, adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"})
		return
	}
	if request.Method == http.MethodGet {
		writeJSON(response, http.StatusOK, c.state(request.Context(), source))
		return
	}
	if request.Method != http.MethodPut {
		writeJSON(response, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}
	enabled, valid := enabledCommand(response, request)
	if !valid {
		return
	}
	state, status := c.setEnabled(source, enabled)
	writeJSON(response, status, state)
}

type supervisorAPI interface {
	Health(context.Context) error
	State(context.Context, string) (adapterState, int, error)
	SetEnabled(context.Context, string, bool) (adapterState, int, error)
}

type unixSupervisorClient struct {
	httpClient *http.Client
}

// newUnixSupervisorClient 创建只能连接指定 Unix Socket 的 HTTP 客户端。
func newUnixSupervisorClient(socketPath string) *unixSupervisorClient {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "unix", socketPath)
		},
		DisableKeepAlives: true,
	}
	return &unixSupervisorClient{httpClient: &http.Client{Transport: transport}}
}

// Health 验证隔离 Supervisor 的 Unix Socket 服务可用。
func (c *unixSupervisorClient) Health(ctx context.Context) error {
	_, status, err := c.request(ctx, http.MethodGet, "", nil)
	if err != nil {
		return err
	}
	if status != http.StatusOK {
		return fmt.Errorf("unexpected supervisor health status: %d", status)
	}
	return nil
}

// State 查询固定来源的实际容器状态。
func (c *unixSupervisorClient) State(ctx context.Context, source string) (adapterState, int, error) {
	return c.request(ctx, http.MethodGet, source, nil)
}

// SetEnabled 提交固定来源的启停命令。
func (c *unixSupervisorClient) SetEnabled(ctx context.Context, source string, enabled bool) (adapterState, int, error) {
	return c.request(ctx, http.MethodPut, source, &enabled)
}

// request 发送类型化请求并限制、校验 Supervisor 响应。
func (c *unixSupervisorClient) request(ctx context.Context, method string, source string, enabled *bool) (adapterState, int, error) {
	path := "/health"
	var body io.Reader
	if source != "" {
		path = "/api/adapters/" + source
	}
	if enabled != nil {
		payload, err := json.Marshal(map[string]bool{"enabled": *enabled})
		if err != nil {
			return adapterState{}, 0, err
		}
		body = bytes.NewReader(payload)
	}
	request, err := http.NewRequestWithContext(ctx, method, "http://supervisor"+path, body)
	if err != nil {
		return adapterState{}, 0, err
	}
	if enabled != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := c.httpClient.Do(request)
	if err != nil {
		return adapterState{}, 0, err
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(response.Body, maximumResponseBytes+1))
	if err != nil || len(payload) > maximumResponseBytes {
		return adapterState{}, response.StatusCode, errors.New("supervisor response invalid")
	}
	if source == "" {
		return adapterState{}, response.StatusCode, nil
	}
	var state adapterState
	if json.Unmarshal(payload, &state) != nil || state.Source != source || !validAdapterStatus(state.Status) {
		return adapterState{}, response.StatusCode, errors.New("supervisor response invalid")
	}
	return state, response.StatusCode, nil
}

type managerController struct {
	supervisor supervisorAPI
	token      string
	authMu     sync.Mutex
	usedNonces map[string]int64
	now        func() time.Time
}

// authorized 校验正文绑定的短时 HMAC，并拒绝时间窗内重复 nonce。
func (c *managerController) authorized(request *http.Request) bool {
	body, err := io.ReadAll(io.LimitReader(request.Body, 1025))
	if err != nil || len(body) > 1024 {
		return false
	}
	request.Body = io.NopCloser(bytes.NewReader(body))
	timestamp := request.Header.Get("X-Internal-Timestamp")
	nonce := request.Header.Get("X-Internal-Nonce")
	target := request.Header.Get("X-Internal-Target")
	digest := request.Header.Get("X-Internal-Content-SHA256")
	signature := request.Header.Get("X-Internal-Signature")
	signedAt, parseErr := strconv.ParseInt(timestamp, 10, 64)
	if parseErr != nil || len(timestamp) > 12 || len(nonce) != 32 || len(digest) != 64 || len(signature) != 64 ||
		!internalHexPattern.MatchString(nonce) || !internalHexPattern.MatchString(digest) ||
		!internalHexPattern.MatchString(signature) || target != request.URL.RequestURI() {
		return false
	}
	now := time.Now()
	if c.now != nil {
		now = c.now()
	}
	if delta := now.Unix() - signedAt; delta > 60 || delta < -60 {
		return false
	}
	actualDigest := sha256.Sum256(body)
	if !hmac.Equal([]byte(digest), []byte(hex.EncodeToString(actualDigest[:]))) {
		return false
	}
	canonical := request.Method + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + digest
	mac := hmac.New(sha256.New, []byte(c.token))
	_, _ = mac.Write([]byte(canonical))
	if !hmac.Equal([]byte(signature), []byte(hex.EncodeToString(mac.Sum(nil)))) {
		return false
	}
	c.authMu.Lock()
	defer c.authMu.Unlock()
	if c.usedNonces == nil {
		c.usedNonces = map[string]int64{}
	}
	for value, usedAt := range c.usedNonces {
		if usedAt < now.Unix()-60 {
			delete(c.usedNonces, value)
		}
	}
	if _, exists := c.usedNonces[nonce]; exists {
		return false
	}
	c.usedNonces[nonce] = now.Unix()
	return true
}

// serveHTTP 提供网络接口，并仅将验证后的类型化命令转发到私有 Supervisor。
func (c *managerController) serveHTTP(response http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodGet && request.URL.Path == "/health" {
		ctx, cancel := context.WithTimeout(request.Context(), 3*time.Second)
		defer cancel()
		if c.supervisor.Health(ctx) != nil {
			writeJSON(response, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
			return
		}
		writeJSON(response, http.StatusOK, map[string]string{"status": "UP"})
		return
	}
	if !c.authorized(request) {
		writeJSON(response, http.StatusUnauthorized, map[string]string{"error": "UNAUTHORIZED"})
		return
	}
	source, ok := sourceFromPath(request.URL.Path)
	if !ok {
		writeJSON(response, http.StatusNotFound, map[string]string{"error": "NOT_FOUND"})
		return
	}
	if _, allowed := adapterServices[source]; !allowed {
		writeJSON(response, http.StatusBadRequest, adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"})
		return
	}
	if request.Method == http.MethodGet {
		state, status, err := c.supervisor.State(request.Context(), source)
		c.writeSupervisorResult(response, source, state, status, err)
		return
	}
	if request.Method != http.MethodPut {
		writeJSON(response, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}
	enabled, valid := enabledCommand(response, request)
	if !valid {
		return
	}
	state, status, err := c.supervisor.SetEnabled(request.Context(), source, enabled)
	c.writeSupervisorResult(response, source, state, status, err)
}

// writeSupervisorResult 将隔离层错误映射为不包含部署细节的稳定响应。
func (c *managerController) writeSupervisorResult(response http.ResponseWriter, source string, state adapterState, status int, err error) {
	if err != nil {
		writeJSON(response, http.StatusServiceUnavailable,
			adapterState{Source: source, Status: "FAILED", Error: "ADAPTER_SUPERVISOR_UNAVAILABLE"})
		return
	}
	writeJSON(response, status, state)
}

// sourceFromPath 只接受单段适配器来源路径并统一为大写。
func sourceFromPath(path string) (string, bool) {
	const prefix = "/api/adapters/"
	if !strings.HasPrefix(path, prefix) {
		return "", false
	}
	remainder := strings.TrimPrefix(path, prefix)
	if remainder == "" || strings.Contains(remainder, "/") {
		return "", false
	}
	return strings.ToUpper(strings.TrimSpace(remainder)), true
}

// enabledCommand 严格读取单个布尔字段并拒绝未知字段和尾随内容。
func enabledCommand(response http.ResponseWriter, request *http.Request) (bool, bool) {
	request.Body = http.MaxBytesReader(response, request.Body, 1024)
	var command struct {
		Enabled *bool `json:"enabled"`
	}
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	if decoder.Decode(&command) != nil || command.Enabled == nil || decoder.Decode(&struct{}{}) != io.EOF {
		writeJSON(response, http.StatusBadRequest, map[string]string{"error": "REQUEST_INVALID"})
		return false, false
	}
	return *command.Enabled, true
}

// validAdapterStatus 限制 Supervisor 能返回到网络边界的状态集合。
func validAdapterStatus(status string) bool {
	switch status {
	case "INVALID", "ENABLING", "RUNNING", "STARTING", "DISABLING", "STOPPED", "FAILED":
		return true
	default:
		return false
	}
}

// writeJSON 写入带防御性响应头的小型 JSON 响应。
func writeJSON(response http.ResponseWriter, status int, value any) {
	response.Header().Set("Content-Type", "application/json; charset=utf-8")
	response.Header().Set("X-Content-Type-Options", "nosniff")
	response.WriteHeader(status)
	_ = json.NewEncoder(response).Encode(value)
}

// required 读取必填环境变量，并在缺失时阻止服务以不安全默认值启动。
func required(name string) string {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		log.Fatal(fmt.Sprintf("%s is required", name))
	}
	return value
}

// secureUnixListener 清理同路径的旧 Socket，并创建仅服务组可访问的新监听器。
func secureUnixListener(socketPath string) (net.Listener, error) {
	if err := os.MkdirAll(filepath.Dir(socketPath), 0750); err != nil {
		return nil, err
	}
	if info, err := os.Lstat(socketPath); err == nil {
		if info.Mode()&os.ModeSocket == 0 {
			return nil, errors.New("supervisor socket path is not a socket")
		}
		if err := os.Remove(socketPath); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(socketPath, 0660); err != nil {
		_ = listener.Close()
		return nil, err
	}
	return listener, nil
}

// server 创建共享超时策略的受限 HTTP 服务。
func server(handler http.Handler) *http.Server {
	return &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       12 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
}

// runManager 启动不持有 Docker 权限的网络控制接口。
func runManager() {
	token := required("ADAPTER_MANAGER_INTERNAL_TOKEN")
	if len(token) < 24 {
		log.Fatal("ADAPTER_MANAGER_INTERNAL_TOKEN must contain at least 24 characters")
	}
	manager := &managerController{supervisor: newUnixSupervisorClient(required("ADAPTER_SUPERVISOR_SOCKET")), token: token}
	httpServer := server(http.HandlerFunc(manager.serveHTTP))
	httpServer.Addr = ":8090"
	log.Fatal(httpServer.ListenAndServe())
}

// runSupervisor 启动无网络、仅通过 Unix Socket 提供固定 Docker 操作的隔离服务。
func runSupervisor() {
	socketPath := required("ADAPTER_SUPERVISOR_SOCKET")
	listener, err := secureUnixListener(socketPath)
	if err != nil {
		log.Fatal(err)
	}
	defer func() {
		_ = listener.Close()
		_ = os.Remove(socketPath)
	}()
	supervisor := &supervisorController{
		runner: dockerCommandRunner{}, projectDir: required("COMPOSE_PROJECT_DIR"),
		composeFile: required("COMPOSE_FILE"), envFile: required("COMPOSE_ENV_FILE"),
		operations: map[string]adapterState{},
	}
	log.Fatal(server(http.HandlerFunc(supervisor.serveHTTP)).Serve(listener))
}

// runManagerHealthcheck 验证 Manager 网络入口及其下游 Supervisor 均可用。
func runManagerHealthcheck() error {
	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Get("http://127.0.0.1:8090/health")
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("manager health returned %d", response.StatusCode)
	}
	return nil
}

// runSupervisorHealthcheck 验证私有 Unix Socket 控制服务可用。
func runSupervisorHealthcheck() error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return newUnixSupervisorClient(required("ADAPTER_SUPERVISOR_SOCKET")).Health(ctx)
}

func main() {
	if len(os.Args) > 1 {
		var err error
		switch os.Args[1] {
		case "manager-healthcheck":
			err = runManagerHealthcheck()
		case "supervisor-healthcheck":
			err = runSupervisorHealthcheck()
		default:
			log.Fatalf("unsupported command: %s", os.Args[1])
		}
		if err != nil {
			log.Fatal(err)
		}
		return
	}
	if os.Getenv("ADAPTER_MANAGER_MODE") == "supervisor" {
		runSupervisor()
		return
	}
	runManager()
}
