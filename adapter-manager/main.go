package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

var adapterServices = map[string]string{
	"N8N":  "n8n-plugin-worker",
	"DIFY": "dify-plugin-worker",
}

type commandRunner interface {
	Run(context.Context, ...string) (string, error)
}

type dockerCommandRunner struct{}

// Run 仅执行控制器生成的固定参数，并返回限制长度的诊断信息。
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

type controller struct {
	runner      commandRunner
	projectDir  string
	composeFile string
	envFile     string
	token       string
	mu          sync.Mutex
	operations  map[string]adapterState
}

// composeArguments 构建两个适配器共用且不可由请求修改的 Compose 命令前缀。
func (c *controller) composeArguments() []string {
	return []string{"compose", "--project-directory", c.projectDir, "-f", c.composeFile, "--env-file", c.envFile}
}

// state 返回进行中的操作，或通过 Docker Compose 解析容器实际状态。
func (c *controller) state(source string) adapterState {
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
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
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
func (c *controller) setEnabled(source string, enabled bool) (adapterState, int) {
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
func (c *controller) runOperation(source string, service string, enabled bool) {
	timeout := 45 * time.Second
	arguments := c.composeArguments()
	if enabled {
		timeout = 10 * time.Minute
			// 适配器镜像必须由发布流程预构建，管理面不再通过 Docker Socket 执行构建。
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

// authorized 使用常量时间比较校验内部控制令牌。
func (c *controller) authorized(request *http.Request) bool {
	provided := request.Header.Get("X-Internal-Token")
	return len(provided) == len(c.token) && subtle.ConstantTimeCompare([]byte(provided), []byte(c.token)) == 1
}

// serveHTTP 提供健康、状态和目标开关接口，且不接受任意服务名。
func (c *controller) serveHTTP(response http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodGet && request.URL.Path == "/health" {
		writeJSON(response, http.StatusOK, map[string]string{"status": "UP"})
		return
	}
	if !c.authorized(request) {
		writeJSON(response, http.StatusUnauthorized, map[string]string{"error": "UNAUTHORIZED"})
		return
	}
	prefix := "/api/adapters/"
	if !strings.HasPrefix(request.URL.Path, prefix) || strings.Contains(strings.TrimPrefix(request.URL.Path, prefix), "/") {
		writeJSON(response, http.StatusNotFound, map[string]string{"error": "NOT_FOUND"})
		return
	}
	source := strings.ToUpper(strings.TrimSpace(strings.TrimPrefix(request.URL.Path, prefix)))
	if request.Method == http.MethodGet {
		state := c.state(source)
		status := http.StatusOK
		if state.Status == "INVALID" {
			status = http.StatusBadRequest
		}
		writeJSON(response, status, state)
		return
	}
	if request.Method != http.MethodPut {
		writeJSON(response, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}
	request.Body = http.MaxBytesReader(response, request.Body, 1024)
	var command struct {
		Enabled *bool `json:"enabled"`
	}
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	if decoder.Decode(&command) != nil || command.Enabled == nil {
		writeJSON(response, http.StatusBadRequest, map[string]string{"error": "REQUEST_INVALID"})
		return
	}
	state, status := c.setEnabled(source, *command.Enabled)
	writeJSON(response, status, state)
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

func main() {
	token := required("ADAPTER_MANAGER_INTERNAL_TOKEN")
	if len(token) < 24 {
		log.Fatal("ADAPTER_MANAGER_INTERNAL_TOKEN must contain at least 24 characters")
	}
	manager := &controller{
		runner: dockerCommandRunner{}, projectDir: required("COMPOSE_PROJECT_DIR"),
		composeFile: required("COMPOSE_FILE"), envFile: required("COMPOSE_ENV_FILE"),
		token: token, operations: map[string]adapterState{},
	}
	server := &http.Server{
		Addr:              ":8090",
		Handler:           http.HandlerFunc(manager.serveHTTP),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}
