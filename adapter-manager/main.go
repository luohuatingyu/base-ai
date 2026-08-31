package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
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
const maximumSandboxResponseBytes = 16 * 1024 * 1024

var internalHexPattern = regexp.MustCompile(`^[a-f0-9]+$`)

var adapterServices = map[string]string{
	"N8N":  "n8n-plugin-worker",
	"DIFY": "dify-plugin-worker",
}

type commandRunner interface {
	Run(context.Context, ...string) (string, error)
	RunInput(context.Context, []byte, int, ...string) (string, error)
}

type dockerCommandRunner struct{}

// Run 仅执行 Broker 生成的固定参数，并返回限制长度的诊断信息。
func (dockerCommandRunner) Run(ctx context.Context, arguments ...string) (string, error) {
	command := exec.CommandContext(ctx, "docker", arguments...)
	output, err := command.CombinedOutput()
	text := strings.TrimSpace(string(output))
	if len(text) > 2000 {
		text = text[len(text)-2000:]
	}
	return text, err
}

// boundedBuffer 接收子进程输出但只保留配置上限，避免恶意插件用标准输出耗尽 Broker 内存。
type boundedBuffer struct {
	buffer   bytes.Buffer
	maximum  int
	overflow bool
}

// Write 截断超限内容并向 os/exec 报告已消费全部字节，防止管道阻塞。
func (b *boundedBuffer) Write(value []byte) (int, error) {
	remaining := b.maximum - b.buffer.Len()
	if remaining > 0 {
		if remaining > len(value) {
			remaining = len(value)
		}
		_, _ = b.buffer.Write(value[:remaining])
	}
	if len(value) > remaining {
		b.overflow = true
	}
	return len(value), nil
}

// RunInput 通过标准输入传递插件载荷，分离并限制标准输出与诊断信息。
func (dockerCommandRunner) RunInput(ctx context.Context, input []byte, maximum int, arguments ...string) (string, error) {
	command := exec.CommandContext(ctx, "docker", arguments...)
	command.Stdin = bytes.NewReader(input)
	stdout := &boundedBuffer{maximum: maximum}
	stderr := &boundedBuffer{maximum: 4096}
	command.Stdout, command.Stderr = stdout, stderr
	err := command.Run()
	if stdout.overflow {
		return stdout.buffer.String(), errors.New("sandbox response too large")
	}
	if err != nil && stderr.buffer.Len() > 0 {
		log.Printf("sandbox command failed detail=%s", strings.TrimSpace(stderr.buffer.String()))
	}
	return stdout.buffer.String(), err
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

type dockerBrokerController struct {
	runner      commandRunner
	projectDir  string
	composeFile string
	envFile     string
}

// composeArguments 构建两个适配器共用且不可由请求修改的 Compose 命令前缀。
func (c *dockerBrokerController) composeArguments() []string {
	return []string{"compose", "--project-directory", c.projectDir, "-f", c.composeFile, "--env-file", c.envFile}
}

// state 通过 Docker Compose 解析固定插件容器的实际状态。
func (c *dockerBrokerController) state(parent context.Context, source string) adapterState {
	service, ok := adapterServices[source]
	if !ok {
		return adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"}
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

// setEnabled 仅对单个硬编码服务执行同步启动或停止命令。
func (c *dockerBrokerController) setEnabled(parent context.Context, source string, enabled bool) (adapterState, int) {
	service, ok := adapterServices[source]
	if !ok {
		return adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"}, http.StatusBadRequest
	}
	timeout := 45 * time.Second
	arguments := c.composeArguments()
	if enabled {
		timeout = 10 * time.Minute
		// 适配器镜像必须由发布流程预构建，Broker 不通过 Docker Socket 执行构建。
		arguments = append(arguments, "--profile", "plugin-adapters", "up", "-d", "--no-build", "--no-deps", service)
	} else {
		arguments = append(arguments, "stop", "-t", "10", service)
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	_, err := c.runner.Run(ctx, arguments...)
	if err != nil {
		code := "ADAPTER_START_FAILED"
		if !enabled {
			code = "ADAPTER_STOP_FAILED"
		}
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			code += "_TIMEOUT"
		}
		log.Printf("adapter operation failed source=%s code=%s", source, code)
		return adapterState{Source: source, Status: "FAILED", Error: code}, http.StatusServiceUnavailable
	}
	status := "STOPPED"
	if enabled {
		status = "RUNNING"
	}
	return adapterState{Source: source, Status: status}, http.StatusOK
}

// serveHTTP 仅在私有 Unix Socket 上暴露固定目标的 Docker 操作。
func (c *dockerBrokerController) serveHTTP(response http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodGet && request.URL.Path == "/health" {
		ctx, cancel := context.WithTimeout(request.Context(), 3*time.Second)
		defer cancel()
		if _, err := c.runner.Run(ctx, "version", "--format", "{{.Server.Version}}"); err != nil {
			writeJSON(response, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
			return
		}
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
	state, status := c.setEnabled(request.Context(), source, enabled)
	writeJSON(response, status, state)
}

// sandboxCommand 是控制 Worker 能提交的完整白名单协议，未知字段会在到达 Docker 前被拒绝。
type sandboxCommand struct {
	Fingerprint    string          `json:"fingerprint"`
	AllowedDomains []string        `json:"allowedDomains,omitempty"`
	PackageID      string          `json:"packageId,omitempty"`
	Version        string          `json:"version,omitempty"`
	ArchiveBase64  string          `json:"archiveBase64,omitempty"`
	ComponentID    string          `json:"componentId,omitempty"`
	Operation      string          `json:"operation,omitempty"`
	Parameters     json.RawMessage `json:"parameters,omitempty"`
	Credentials    json.RawMessage `json:"credentials,omitempty"`
	Input          json.RawMessage `json:"input,omitempty"`
	Context        json.RawMessage `json:"context,omitempty"`
	Event          json.RawMessage `json:"event,omitempty"`
	RedirectURI    string          `json:"redirectUri,omitempty"`
	State          string          `json:"state,omitempty"`
	Code           string          `json:"code,omitempty"`
	CodeVerifier   string          `json:"codeVerifier,omitempty"`
}

// sandboxTokenClaims 与出站网关共享最小声明格式，长期签名密钥只存在于 Broker 和网关。
type sandboxTokenClaims struct {
	Source      string   `json:"s"`
	Fingerprint string   `json:"f"`
	Operation   string   `json:"o"`
	Domains     []string `json:"d"`
	IssuedAt    int64    `json:"iat"`
	ExpiresAt   int64    `json:"exp"`
	Nonce       string   `json:"n"`
}

// sandboxBrokerController 只为固定来源创建一次性容器、独立卷和临时内部网络。
type sandboxBrokerController struct {
	runner            commandRunner
	source            string
	projectName       string
	gatewayContainer  string
	egressKey         string
	packageDomains    []string
	pipIndexURL       string
	npmRegistryURL    string
	memoryLimit       string
	cpuLimit          string
	pidsLimit         int
	maxRequestBytes   int64
	maximumArchive    int
	maximumUnpacked   int
	maximumFiles      int
	invocationTimeout int
	installTimeout    int
	probeTimeout      int
	responseMaximum   int
	locksMu           sync.Mutex
	locks             map[string]*sync.Mutex
}

var pluginFingerprintPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)
var dockerProjectPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,62}$`)
var resourceMemoryPattern = regexp.MustCompile(`^[1-9][0-9]*(?:[kKmMgG])?$`)
var resourceCPUPattern = regexp.MustCompile(`^[0-9]+(?:\.[0-9]+)?$`)

// serveHTTP 在来源专用 Unix Socket 上暴露严格的插件操作协议。
func (c *sandboxBrokerController) serveHTTP(response http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodGet && request.URL.Path == "/health" {
		ctx, cancel := context.WithTimeout(request.Context(), 3*time.Second)
		defer cancel()
		if _, err := c.runner.Run(ctx, "version", "--format", "{{.Server.Version}}"); err != nil {
			writeJSON(response, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
			return
		}
		writeJSON(response, http.StatusOK, map[string]string{"status": "UP"})
		return
	}
	if request.Method != http.MethodPost {
		writeJSON(response, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}
	operation := strings.TrimPrefix(request.URL.Path, "/sandbox/")
	if request.URL.Path != "/sandbox/"+operation || !contains([]string{"inspect", "invoke", "remove"}, operation) {
		writeJSON(response, http.StatusNotFound, map[string]string{"error": "NOT_FOUND"})
		return
	}
	command, valid := c.readCommand(response, request, operation)
	if !valid {
		return
	}
	if operation == "remove" {
		c.withFingerprintLock(command.Fingerprint, func() {
			ctx, cancel := context.WithTimeout(request.Context(), 30*time.Second)
			defer cancel()
			_, _ = c.runner.Run(ctx, "volume", "rm", "-f", c.volumeName(command.Fingerprint))
		})
		writeJSON(response, http.StatusOK, map[string]bool{"removed": true})
		return
	}
	var output string
	var err error
	execute := func() { output, err = c.runSandbox(request.Context(), operation, command) }
	if operation == "inspect" {
		c.withFingerprintLock(command.Fingerprint, execute)
	} else {
		execute()
	}
	if err != nil {
		status, code := http.StatusBadRequest, sandboxError(output, "PLUGIN_SANDBOX_FAILED")
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(request.Context().Err(), context.DeadlineExceeded) {
			status, code = http.StatusGatewayTimeout, "PLUGIN_SANDBOX_TIMEOUT"
		} else if output == "" {
			status, code = http.StatusServiceUnavailable, "PLUGIN_SANDBOX_UNAVAILABLE"
		}
		writeJSON(response, status, map[string]string{"error": code})
		return
	}
	var result json.RawMessage
	if json.Unmarshal([]byte(output), &result) != nil || len(result) == 0 {
		writeJSON(response, http.StatusBadGateway, map[string]string{"error": "PLUGIN_SANDBOX_OUTPUT_INVALID"})
		return
	}
	writeRawJSON(response, http.StatusOK, result)
}

// readCommand 限制请求体、拒绝未知字段并校验与操作绑定的全部身份字段。
func (c *sandboxBrokerController) readCommand(response http.ResponseWriter, request *http.Request,
	operation string) (sandboxCommand, bool) {
	maximum := c.maxRequestBytes
	if maximum <= 0 {
		maximum = 16 * 1024 * 1024
	}
	request.Body = http.MaxBytesReader(response, request.Body, maximum)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var command sandboxCommand
	if decoder.Decode(&command) != nil || decoder.Decode(&struct{}{}) != io.EOF ||
		!pluginFingerprintPattern.MatchString(command.Fingerprint) {
		writeJSON(response, http.StatusBadRequest, map[string]string{"error": "SANDBOX_REQUEST_INVALID"})
		return sandboxCommand{}, false
	}
	if operation == "inspect" && command.ArchiveBase64 == "" || operation == "invoke" &&
		(command.ComponentID == "" || !contains([]string{"invoke", "validate_credentials", "subscribe", "unsubscribe",
			"refresh", "dispatch_event", "oauth_authorize", "oauth_exchange", "schema"}, command.Operation)) {
		writeJSON(response, http.StatusBadRequest, map[string]string{"error": "SANDBOX_REQUEST_INVALID"})
		return sandboxCommand{}, false
	}
	if operation == "remove" && (command.ArchiveBase64 != "" || command.ComponentID != "" || len(command.AllowedDomains) != 0) {
		writeJSON(response, http.StatusBadRequest, map[string]string{"error": "SANDBOX_REQUEST_INVALID"})
		return sandboxCommand{}, false
	}
	if len(command.AllowedDomains) > 64 {
		writeJSON(response, http.StatusBadRequest, map[string]string{"error": "SANDBOX_DOMAINS_INVALID"})
		return sandboxCommand{}, false
	}
	seen := map[string]bool{}
	for index, domain := range command.AllowedDomains {
		normalized := exactDomain(domain)
		if normalized == "" || seen[normalized] {
			writeJSON(response, http.StatusBadRequest, map[string]string{"error": "SANDBOX_DOMAINS_INVALID"})
			return sandboxCommand{}, false
		}
		seen[normalized], command.AllowedDomains[index] = true, normalized
	}
	return command, true
}

// runSandbox 创建源和调用独占的内部网络，运行固定镜像并无条件清理临时资源。
func (c *sandboxBrokerController) runSandbox(parent context.Context, operation string,
	command sandboxCommand) (string, error) {
	volume := c.volumeName(command.Fingerprint)
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	_, err := c.runner.Run(ctx, "volume", "create", "--label", "base-ai.plugin-sandbox=true", "--label",
		"base-ai.plugin-source="+c.source, "--label", "base-ai.plugin-fingerprint="+command.Fingerprint, volume)
	cancel()
	if err != nil {
		return "", err
	}
	if err = c.prepareVolume(parent, volume); err != nil {
		return "", err
	}
	suffix, err := randomHex(8)
	if err != nil {
		return "", err
	}
	prefix := c.projectName + "-" + strings.ToLower(c.source) + "-sandbox-" + suffix
	network, container := prefix+"-network", prefix
	ctx, cancel = context.WithTimeout(parent, 30*time.Second)
	_, err = c.runner.Run(ctx, "network", "create", "--internal", "--label", "base-ai.plugin-sandbox=true", network)
	cancel()
	if err != nil {
		return "", err
	}
	defer c.cleanupSandbox(container, network)
	ctx, cancel = context.WithTimeout(parent, 30*time.Second)
	_, err = c.runner.Run(ctx, "network", "connect", "--alias", "outbound-gateway", network, c.gatewayContainer)
	cancel()
	if err != nil {
		return "", err
	}
	domains := command.AllowedDomains
	if operation == "inspect" {
		domains = append([]string(nil), c.packageDomains...)
	}
	timeout := c.invocationTimeout
	if operation == "inspect" {
		timeout = c.installTimeout + c.probeTimeout + 60
	}
	if timeout <= 0 {
		timeout = 300
	}
	if timeout > 630 {
		timeout = 630
	}
	token, err := c.mintToken(operation, command.Fingerprint, domains, time.Duration(timeout+15)*time.Second)
	if err != nil {
		return "", err
	}
	payloadCommand := command
	payloadCommand.AllowedDomains = nil
	payload, err := json.Marshal(payloadCommand)
	if err != nil {
		return "", err
	}
	ctx, cancel = context.WithTimeout(parent, time.Duration(timeout)*time.Second)
	defer cancel()
	output, runErr := c.runner.RunInput(ctx, payload, c.responseLimit(),
		c.runArguments(operation, command.Fingerprint, token, container, network)...)
	if ctx.Err() != nil {
		return output, ctx.Err()
	}
	return output, runErr
}

// prepareVolume 在接收插件载荷前用无网络固定命令初始化独占卷权限。
func (c *sandboxBrokerController) prepareVolume(parent context.Context, volume string) error {
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	_, err := c.runner.Run(ctx, "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
		"--user", "0:0", "--cap-drop", "ALL", "--cap-add", "CHOWN", "--security-opt",
		"no-new-privileges:true", "--pids-limit", "16", "--memory", "32m", "--cpus", "0.25",
		"--mount", "type=volume,src="+volume+",dst=/data/packages", "--entrypoint", "/bin/chown",
		c.imageName(), "10001:10001", "/data/packages")
	return err
}

// runArguments 构造不接受调用方参数的 docker run 安全基线。
func (c *sandboxBrokerController) runArguments(operation, fingerprint, token, container, network string) []string {
	memory := c.memoryLimit
	if !resourceMemoryPattern.MatchString(memory) {
		memory = "512m"
	}
	cpu := c.cpuLimit
	if !resourceCPUPattern.MatchString(cpu) {
		cpu = "1.0"
	}
	pids := c.pidsLimit
	if pids < 16 || pids > 512 {
		pids = 64
	}
	mount := "type=volume,src=" + c.volumeName(fingerprint) + ",dst=/data/packages"
	if operation != "inspect" {
		mount += ",readonly"
	}
	proxy := "http://sandbox:" + token + "@outbound-gateway:8080"
	arguments := []string{"run", "--rm", "--interactive", "--pull", "never", "--name", container, "--network", network,
		"--read-only", "--init", "--user", "10001:10001", "--cap-drop", "ALL", "--security-opt",
		"no-new-privileges:true", "--pids-limit", strconv.Itoa(pids), "--memory", memory, "--cpus", cpu,
		"--ulimit", "nofile=256:256", "--stop-timeout", "5", "--tmpfs",
		"/data/tmp:rw,nosuid,nodev,size=64m,uid=10001,gid=10001", "--tmpfs",
		"/tmp:rw,nosuid,nodev,size=64m,uid=10001,gid=10001", "--mount",
		mount,
		"--label", "base-ai.plugin-sandbox=true", "--label", "base-ai.plugin-source=" + c.source,
		"--label", "base-ai.plugin-fingerprint=" + fingerprint, "--env", "HTTP_PROXY=" + proxy,
		"--env", "HTTPS_PROXY=" + proxy, "--env", "NO_PROXY=localhost,127.0.0.1", "--env",
		"PLUGIN_PACKAGE_ROOT=/data/packages", "--env", "PLUGIN_MAX_PACKAGE_BYTES=" + strconv.Itoa(defaultInt(c.maximumArchive, 5*1024*1024)),
		"--env", "PLUGIN_MAX_UNPACKED_BYTES=" + strconv.Itoa(defaultInt(c.maximumUnpacked, 100*1024*1024)),
		"--env", "PLUGIN_MAX_PACKAGE_FILES=" + strconv.Itoa(defaultInt(c.maximumFiles, 2048)), "--env",
		"PLUGIN_INVOCATION_TIMEOUT_SECONDS=" + strconv.Itoa(defaultInt(c.invocationTimeout, 60)), "--env",
		"PLUGIN_DEPENDENCY_INSTALL_TIMEOUT_SECONDS=" + strconv.Itoa(defaultInt(c.installTimeout, 180)), "--env",
		"PLUGIN_PROBE_TIMEOUT_SECONDS=" + strconv.Itoa(defaultInt(c.probeTimeout, 20)), "--env",
		"PLUGIN_HTTP_RESPONSE_MAX_BYTES=" + strconv.Itoa(c.responseLimit())}
	if c.source == "DIFY" && c.pipIndexURL != "" {
		arguments = append(arguments, "--env", "PIP_INDEX_URL="+c.pipIndexURL)
	}
	if c.source == "N8N" && c.npmRegistryURL != "" {
		arguments = append(arguments, "--env", "npm_config_registry="+c.npmRegistryURL)
	}
	arguments = append(arguments, c.imageName())
	if c.source == "DIFY" {
		return append(arguments, "python", "-m", "app.sandbox", operation)
	}
	return append(arguments, "node", "app/sandbox.mjs", operation)
}

// cleanupSandbox 强制删除超时容器，并断开网关后移除本次调用独占网络。
func (c *sandboxBrokerController) cleanupSandbox(container, network string) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	_, _ = c.runner.Run(ctx, "rm", "-f", container)
	_, _ = c.runner.Run(ctx, "network", "disconnect", "-f", network, c.gatewayContainer)
	_, _ = c.runner.Run(ctx, "network", "rm", network)
}

// mintToken 为单次沙箱签发最多十一分钟、域名精确绑定的代理凭据。
func (c *sandboxBrokerController) mintToken(operation, fingerprint string, domains []string,
	ttl time.Duration) (string, error) {
	if len(c.egressKey) < 32 {
		return "", errors.New("sandbox signing key too short")
	}
	nonce, err := randomHex(16)
	if err != nil {
		return "", err
	}
	now := time.Now().Unix()
	claims := sandboxTokenClaims{Source: c.source, Fingerprint: fingerprint, Operation: operation,
		Domains: domains, IssuedAt: now, ExpiresAt: now + int64(ttl/time.Second), Nonce: nonce}
	payload, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, []byte(c.egressKey))
	_, _ = mac.Write([]byte(encoded))
	return encoded + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}

// volumeName 把来源和不可伪造包摘要映射到唯一持久卷。
func (c *sandboxBrokerController) volumeName(fingerprint string) string {
	return c.projectName + "-" + strings.ToLower(c.source) + "-plugin-" + fingerprint
}

// imageName 只允许当前 Compose 项目中固定来源的预构建 Worker 镜像。
func (c *sandboxBrokerController) imageName() string {
	return c.projectName + "-" + strings.ToLower(c.source) + "-plugin-worker:latest"
}

// withFingerprintLock 串行化同一插件的安装与删除，调用阶段仍允许只读并发。
func (c *sandboxBrokerController) withFingerprintLock(fingerprint string, action func()) {
	c.locksMu.Lock()
	if c.locks == nil {
		c.locks = map[string]*sync.Mutex{}
	}
	lock := c.locks[fingerprint]
	if lock == nil {
		lock = &sync.Mutex{}
		c.locks[fingerprint] = lock
	}
	c.locksMu.Unlock()
	lock.Lock()
	defer lock.Unlock()
	action()
}

// responseLimit 返回限制在 1 MiB 至 16 MiB 之间的沙箱输出上限。
func (c *sandboxBrokerController) responseLimit() int {
	value := c.responseMaximum
	if value < 1024*1024 {
		value = 1024 * 1024
	}
	if value > maximumSandboxResponseBytes {
		value = maximumSandboxResponseBytes
	}
	return value
}

// randomHex 生成只含小写十六进制的不可预测资源后缀。
func randomHex(bytesCount int) (string, error) {
	value := make([]byte, bytesCount)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return hex.EncodeToString(value), nil
}

// exactDomain 规范插件批准域名并拒绝 IP、通配符和非法 DNS 标签。
func exactDomain(value string) string {
	domain := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(value), "."))
	if len(domain) < 1 || len(domain) > 253 || net.ParseIP(domain) != nil || strings.Contains(domain, "*") {
		return ""
	}
	for _, label := range strings.Split(domain, ".") {
		if len(label) < 1 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return ""
		}
		for _, character := range label {
			if character != '-' && (character < 'a' || character > 'z') && (character < '0' || character > '9') {
				return ""
			}
		}
	}
	return domain
}

// sandboxError 只返回沙箱协议中的稳定错误码，不暴露命令和路径。
func sandboxError(output, fallback string) string {
	var value struct {
		Error string `json:"error"`
	}
	if json.Unmarshal([]byte(output), &value) == nil && regexp.MustCompile(`^[A-Z0-9_]{1,80}$`).MatchString(value.Error) {
		return value.Error
	}
	return fallback
}

// defaultInt 为缺失的正整数配置提供受控默认值。
func defaultInt(value, fallback int) int {
	if value > 0 {
		return value
	}
	return fallback
}

// contains 判断固定短列表是否包含目标字符串。
func contains(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}

// writeRawJSON 写入已验证为合法 JSON 的有限沙箱响应。
func writeRawJSON(response http.ResponseWriter, status int, value json.RawMessage) {
	response.Header().Set("Content-Type", "application/json; charset=utf-8")
	response.Header().Set("X-Content-Type-Options", "nosniff")
	response.WriteHeader(status)
	_, _ = response.Write(value)
}

type supervisorController struct {
	broker     supervisorAPI
	mu         sync.Mutex
	operations map[string]adapterState
}

// state 优先返回进行中的策略层操作，否则查询受限 Docker Broker。
func (c *supervisorController) state(parent context.Context, source string) adapterState {
	if _, ok := adapterServices[source]; !ok {
		return adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"}
	}
	c.mu.Lock()
	operation, active := c.operations[source]
	c.mu.Unlock()
	if active {
		return operation
	}
	state, status, err := c.broker.State(parent, source)
	if err != nil || status != http.StatusOK {
		return adapterState{Source: source, Status: "FAILED", Error: "ADAPTER_BROKER_UNAVAILABLE"}
	}
	return state
}

// setEnabled 为单个硬编码服务串行提交异步启停操作。
func (c *supervisorController) setEnabled(source string, enabled bool) (adapterState, int) {
	if _, ok := adapterServices[source]; !ok {
		return adapterState{Source: source, Status: "INVALID", Error: "ADAPTER_SOURCE_INVALID"}, http.StatusBadRequest
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if current, active := c.operations[source]; active {
		if current.Status == "ENABLING" || current.Status == "DISABLING" {
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
	go c.runOperation(source, enabled)
	return operation, http.StatusAccepted
}

// runOperation 仅向 Broker 发送类型化命令，并把底层失败收敛为稳定错误码。
func (c *supervisorController) runOperation(source string, enabled bool) {
	timeout := 45 * time.Second
	if enabled {
		timeout = 10 * time.Minute
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	state, status, err := c.broker.SetEnabled(ctx, source, enabled)
	c.mu.Lock()
	defer c.mu.Unlock()
	if err != nil || status != http.StatusOK || state.Status == "FAILED" {
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

// serveHTTP 在策略层串行化操作，并把 Docker 权限留在下游 Broker。
func (c *supervisorController) serveHTTP(response http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodGet && request.URL.Path == "/health" {
		ctx, cancel := context.WithTimeout(request.Context(), 3*time.Second)
		defer cancel()
		if c.broker.Health(ctx) != nil {
			writeJSON(response, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
			return
		}
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
			return nil, errors.New("control socket path is not a socket")
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

// brokerServer 允许预构建插件首次创建在固定超时内完成。
func brokerServer(handler http.Handler) *http.Server {
	return &http.Server{
		Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second,
		WriteTimeout: 11 * time.Minute, IdleTimeout: 30 * time.Second,
	}
}

// sandboxServer 为大包安装和有限调用结果提供硬超时，且只监听来源专用 Unix Socket。
func sandboxServer(handler http.Handler) *http.Server {
	return &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 2 * time.Minute,
		WriteTimeout: 11 * time.Minute, IdleTimeout: 30 * time.Second}
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

// runSupervisor 启动无网络、无 Docker Socket 的异步策略控制层。
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
		broker:     newUnixSupervisorClient(required("ADAPTER_BROKER_SOCKET")),
		operations: map[string]adapterState{},
	}
	log.Fatal(server(http.HandlerFunc(supervisor.serveHTTP)).Serve(listener))
}

// runBroker 启动唯一持有 Docker Socket 的固定命令 Broker。
func runBroker() {
	socketPath := required("ADAPTER_BROKER_SOCKET")
	listener, err := secureUnixListener(socketPath)
	if err != nil {
		log.Fatal(err)
	}
	defer func() {
		_ = listener.Close()
		_ = os.Remove(socketPath)
	}()
	runner := dockerCommandRunner{}
	broker := &dockerBrokerController{
		runner: runner, projectDir: required("COMPOSE_PROJECT_DIR"),
		composeFile: required("COMPOSE_FILE"), envFile: required("COMPOSE_ENV_FILE"),
	}
	projectName := required("COMPOSE_PROJECT_NAME")
	if !dockerProjectPattern.MatchString(projectName) {
		log.Fatal("COMPOSE_PROJECT_NAME is invalid")
	}
	egressKey := required("PLUGIN_SANDBOX_EGRESS_SIGNING_KEY")
	if len(egressKey) < 32 {
		log.Fatal("PLUGIN_SANDBOX_EGRESS_SIGNING_KEY must contain at least 32 characters")
	}
	packageDomains := parseExactDomains(os.Getenv("PLUGIN_PACKAGE_ALLOWED_DOMAINS"))
	common := sandboxBrokerController{runner: runner, projectName: projectName,
		gatewayContainer: projectName + "-outbound-gateway", egressKey: egressKey, packageDomains: packageDomains,
		pipIndexURL:    safeRegistryURL(optional("PIP_INDEX_URL", "https://pypi.org/simple")),
		npmRegistryURL: safeRegistryURL(optional("NPM_CONFIG_REGISTRY", "https://registry.npmjs.org")),
		memoryLimit:    optional("PLUGIN_SANDBOX_MEMORY_LIMIT", "512m"), cpuLimit: optional("PLUGIN_SANDBOX_CPU_LIMIT", "1.0"),
		pidsLimit:         positiveEnv("PLUGIN_SANDBOX_PIDS_LIMIT", 64),
		maxRequestBytes:   int64(positiveEnv("PLUGIN_WORKER_MAX_REQUEST_BYTES", 16*1024*1024)),
		maximumArchive:    positiveEnv("PLUGIN_MAX_PACKAGE_BYTES", 10*1024*1024),
		maximumUnpacked:   positiveEnv("PLUGIN_MAX_UNPACKED_BYTES", 100*1024*1024),
		maximumFiles:      positiveEnv("PLUGIN_MAX_PACKAGE_FILES", 2048),
		invocationTimeout: positiveEnv("PLUGIN_INVOCATION_TIMEOUT_SECONDS", 60),
		installTimeout:    positiveEnv("PLUGIN_DEPENDENCY_INSTALL_TIMEOUT_SECONDS", 180),
		probeTimeout:      positiveEnv("PLUGIN_PROBE_TIMEOUT_SECONDS", 20),
		responseMaximum:   positiveEnv("PLUGIN_HTTP_RESPONSE_MAX_BYTES", 10*1024*1024)}
	dify := common
	dify.source = "DIFY"
	n8n := common
	n8n.source = "N8N"
	go serveSandboxSocket(required("DIFY_SANDBOX_BROKER_SOCKET"), &dify)
	go serveSandboxSocket(required("N8N_SANDBOX_BROKER_SOCKET"), &n8n)
	log.Fatal(brokerServer(http.HandlerFunc(broker.serveHTTP)).Serve(listener))
}

// serveSandboxSocket 启动来源绑定的 Broker 监听器，任一监听失败都会终止进程以阻止降级运行。
func serveSandboxSocket(socketPath string, controller *sandboxBrokerController) {
	listener, err := secureUnixListener(socketPath)
	if err != nil {
		log.Fatal(err)
	}
	defer func() { _ = listener.Close(); _ = os.Remove(socketPath) }()
	if err := sandboxServer(http.HandlerFunc(controller.serveHTTP)).Serve(listener); err != nil {
		log.Fatal(err)
	}
}

// parseExactDomains 读取包仓库精确域名列表，非法值直接阻止 Broker 启动。
func parseExactDomains(value string) []string {
	result := []string{}
	seen := map[string]bool{}
	for _, raw := range strings.FieldsFunc(value, func(character rune) bool {
		return character == ',' || character == ' ' || character == '\n' || character == '\t'
	}) {
		domain := exactDomain(raw)
		if domain == "" {
			log.Fatal("PLUGIN_PACKAGE_ALLOWED_DOMAINS contains an invalid domain")
		}
		if !seen[domain] {
			result = append(result, domain)
			seen[domain] = true
		}
	}
	return result
}

// safeRegistryURL 只允许无凭据、无查询和片段的 HTTPS 包仓库地址。
func safeRegistryURL(value string) string {
	parsed, err := url.Parse(strings.TrimSpace(value))
	if err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil ||
		parsed.RawQuery != "" || parsed.Fragment != "" {
		log.Fatal("plugin package registry URL must be credential-free HTTPS")
	}
	return parsed.String()
}

// optional 读取可选环境变量并返回安全默认值。
func optional(name, fallback string) string {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	return value
}

// positiveEnv 读取有界正整数，非法配置阻止服务以不确定资源策略启动。
func positiveEnv(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 || parsed > 1<<30 {
		log.Fatal(name + " must be a bounded positive integer")
	}
	return parsed
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

// runBrokerHealthcheck 验证 Docker Broker 及 Docker Engine 均可用。
func runBrokerHealthcheck() error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return newUnixSupervisorClient(required("ADAPTER_BROKER_SOCKET")).Health(ctx)
}

func main() {
	if len(os.Args) > 1 {
		var err error
		switch os.Args[1] {
		case "manager-healthcheck":
			err = runManagerHealthcheck()
		case "supervisor-healthcheck":
			err = runSupervisorHealthcheck()
		case "broker-healthcheck":
			err = runBrokerHealthcheck()
		default:
			log.Fatalf("unsupported command: %s", os.Args[1])
		}
		if err != nil {
			log.Fatal(err)
		}
		return
	}
	switch os.Getenv("ADAPTER_MANAGER_MODE") {
	case "supervisor":
		runSupervisor()
	case "broker":
		runBroker()
	default:
		runManager()
	}
}
