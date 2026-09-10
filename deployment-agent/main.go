package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
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

var revisionPattern = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$`)
var pathPattern = regexp.MustCompile(`^/[A-Za-z0-9_./-]{1,200}$`)
var filePattern = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,120}$`)
var hostPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9.:-]{0,253}$`)
var usernamePattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9._-]{0,63}$`)
var fingerprintPattern = regexp.MustCompile(`^SHA256:[A-Za-z0-9+/]{43}$`)
var jobIDPattern = regexp.MustCompile(`^[a-f0-9]{32}$`)

type request struct {
	Mode        string `json:"mode"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	Username    string `json:"username"`
	AuthType    string `json:"authType"`
	PrivateKey  string `json:"privateKey"`
	Password    string `json:"password"`
	Passphrase  string `json:"passphrase"`
	HostKey     string `json:"hostKey"`
	WorkingDir  string `json:"workingDir"`
	ComposeFile string `json:"composeFile"`
	Action      string `json:"action"`
	Revision    string `json:"revision"`
	JobID       string `json:"jobId"`
}

type deploymentJob struct {
	status     string
	output     string
	error      string
	createdAt  time.Time
	finishedAt time.Time
}

type hostMetrics struct {
	CPUCores           int     `json:"cpuCores"`
	CPUUsagePercent    float64 `json:"cpuUsagePercent"`
	Load1              float64 `json:"load1"`
	Load5              float64 `json:"load5"`
	Load15             float64 `json:"load15"`
	MemoryTotalBytes   int64   `json:"memoryTotalBytes"`
	MemoryUsedBytes    int64   `json:"memoryUsedBytes"`
	MemoryUsagePercent float64 `json:"memoryUsagePercent"`
	DiskPath           string  `json:"diskPath"`
	DiskTotalBytes     int64   `json:"diskTotalBytes"`
	DiskUsedBytes      int64   `json:"diskUsedBytes"`
	DiskUsagePercent   float64 `json:"diskUsagePercent"`
	UptimeSeconds      int64   `json:"uptimeSeconds"`
}

type containerStatus struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Image  string `json:"image"`
	State  string `json:"state"`
	Health string `json:"health"`
	Status string `json:"status"`
}

type monitorResult struct {
	Status         string            `json:"status"`
	CollectedAt    string            `json:"collectedAt"`
	Host           hostMetrics       `json:"host"`
	Containers     []containerStatus `json:"containers"`
	ContainerError string            `json:"containerError,omitempty"`
}

type agent struct {
	token         string
	mu            sync.RWMutex
	jobs          map[string]*deploymentJob
	monitorRunner func(context.Context, request) (monitorResult, error)
}

func main() {
	token := os.Getenv("DEPLOYMENT_AGENT_INTERNAL_TOKEN")
	if len(token) < 24 {
		panic("DEPLOYMENT_AGENT_INTERNAL_TOKEN must contain at least 24 characters")
	}
	a := &agent{token: token, jobs: make(map[string]*deploymentJob)}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", a.health)
	mux.HandleFunc("/test", a.test)
	mux.HandleFunc("/monitor", a.monitor)
	mux.HandleFunc("/execute", a.execute)
	mux.HandleFunc("/jobs/", a.jobsHandler)
	server := &http.Server{Addr: ":8091", Handler: mux, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Minute, WriteTimeout: 15 * time.Minute}
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		panic(err)
	}
}

// health 返回不包含主机信息的存活状态。
func (a *agent) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"status": "METHOD_NOT_ALLOWED"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

// test 验证本地或远端 Compose 配置但不变更容器状态。
func (a *agent) test(w http.ResponseWriter, r *http.Request) { a.handle(w, r, false) }

// execute 创建白名单部署任务，等待 Backend 持久化任务编号后再启动。
func (a *agent) execute(w http.ResponseWriter, r *http.Request) { a.handle(w, r, true) }

// monitor 鉴权后实时采集只读主机资源与 Docker 容器状态。
func (a *agent) monitor(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"status": "FAILED", "error": "METHOD_NOT_ALLOWED"})
		return
	}
	if !a.authorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"status": "FAILED", "error": "UNAUTHORIZED"})
		return
	}
	input, ok := decodeRequest(w, r)
	if !ok {
		return
	}
	if err := validateMonitor(input); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	runner := collectMonitor
	if a.monitorRunner != nil {
		runner = a.monitorRunner
	}
	result, err := runner(ctx, input)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]string{"status": "FAILED", "error": trimOutput(err.Error())})
		return
	}
	writeJSON(w, http.StatusOK, result)
}

// handle 统一完成鉴权、请求限制、参数验证和结果脱敏。
func (a *agent) handle(w http.ResponseWriter, r *http.Request, execute bool) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"status": "FAILED", "error": "METHOD_NOT_ALLOWED"})
		return
	}
	if !a.authorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"status": "FAILED", "error": "UNAUTHORIZED"})
		return
	}
	input, ok := decodeRequest(w, r)
	if !ok {
		return
	}
	if err := validate(input, execute); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": err.Error()})
		return
	}
	if execute {
		status := a.createAndStartJob(input)
		writeJSON(w, http.StatusAccepted, map[string]string{"status": status, "jobId": input.JobID})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Minute)
	defer cancel()
	var output string
	var err error
	if input.Mode == "LOCAL" {
		output, err = local(ctx, input, false)
	} else {
		output, err = remote(ctx, input, false)
	}
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]string{"status": "FAILED", "error": trimOutput(err.Error())})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "SUCCEEDED", "output": trimOutput(output)})
}

// decodeRequest 限制请求体大小并拒绝未知字段和多余 JSON 内容。
func decodeRequest(w http.ResponseWriter, r *http.Request) (request, bool) {
	var input request
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128*1024))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&input); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": "INVALID_REQUEST"})
		return request{}, false
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": "INVALID_REQUEST"})
		return request{}, false
	}
	return input, true
}

// jobsHandler 查询或启动已持久化编号的部署任务。
func (a *agent) jobsHandler(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"status": "FAILED", "error": "UNAUTHORIZED"})
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/jobs/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || !jobIDPattern.MatchString(parts[0]) {
		writeJSON(w, http.StatusNotFound, map[string]string{"status": "FAILED", "error": "JOB_NOT_FOUND"})
		return
	}
	if r.Method == http.MethodGet && len(parts) == 1 {
		a.writeJob(w, parts[0])
		return
	}
	writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"status": "FAILED", "error": "METHOD_NOT_ALLOWED"})
}

// createAndStartJob 以 Backend 已持久化的编号启动任务，重复请求只返回原状态。
func (a *agent) createAndStartJob(input request) string {
	a.mu.Lock()
	a.cleanupJobsLocked(time.Now())
	if a.jobs == nil {
		a.jobs = make(map[string]*deploymentJob)
	}
	if job, exists := a.jobs[input.JobID]; exists {
		status := job.status
		a.mu.Unlock()
		return status
	}
	a.jobs[input.JobID] = &deploymentJob{status: "RUNNING", createdAt: time.Now()}
	a.mu.Unlock()
	go a.runJob(input.JobID, input)
	return "RUNNING"
}

// runJob 在独立超时上下文中执行 Compose，调用方重启不会中断任务。
func (a *agent) runJob(jobID string, input request) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()
	var output string
	var err error
	if input.Mode == "LOCAL" {
		output, err = local(ctx, input, true)
	} else {
		output, err = remote(ctx, input, true)
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	job, exists := a.jobs[jobID]
	if !exists {
		return
	}
	job.finishedAt = time.Now()
	if err != nil {
		job.status = "FAILED"
		job.error = trimOutput(err.Error())
		return
	}
	job.status = "SUCCEEDED"
	job.output = trimOutput(output)
}

// writeJob 返回不含凭据的任务状态和受限命令输出。
func (a *agent) writeJob(w http.ResponseWriter, jobID string) {
	a.mu.RLock()
	job, exists := a.jobs[jobID]
	if !exists {
		a.mu.RUnlock()
		writeJSON(w, http.StatusNotFound, map[string]string{"status": "FAILED", "error": "JOB_NOT_FOUND"})
		return
	}
	result := map[string]string{"status": job.status, "jobId": jobID}
	if job.output != "" {
		result["output"] = job.output
	}
	if job.error != "" {
		result["error"] = job.error
	}
	a.mu.RUnlock()
	writeJSON(w, http.StatusOK, result)
}

// cleanupJobsLocked 清除一天前已不再需要续查的任务结果。
func (a *agent) cleanupJobsLocked(now time.Time) {
	for jobID, job := range a.jobs {
		if job.createdAt.Before(now.Add(-24 * time.Hour)) {
			delete(a.jobs, jobID)
		}
	}
}

// authorized 使用常量时间比较验证内部令牌。
func (a *agent) authorized(r *http.Request) bool {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		return false
	}
	value := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
	return value != "" && subtle.ConstantTimeCompare([]byte(value), []byte(a.token)) == 1
}

// validate 严格限制模式、路径、版本和 SSH 参数。
func validate(input request, execute bool) error {
	if input.Mode != "LOCAL" && input.Mode != "SSH" {
		return errors.New("MODE_INVALID")
	}
	if input.WorkingDir == "" || !pathPattern.MatchString(input.WorkingDir) || filepath.Clean(input.WorkingDir) != input.WorkingDir {
		return errors.New("WORKING_DIR_INVALID")
	}
	if input.ComposeFile == "" || !filePattern.MatchString(input.ComposeFile) {
		return errors.New("COMPOSE_FILE_INVALID")
	}
	if input.ComposeFile != "docker-compose.yml" && input.ComposeFile != "compose.yml" {
		return errors.New("COMPOSE_FILE_FORBIDDEN")
	}
	if input.Mode == "LOCAL" && input.WorkingDir != "/workspace" {
		return errors.New("LOCAL_WORKING_DIR_FORBIDDEN")
	}
	if execute && (!revisionPattern.MatchString(input.Revision) || !jobIDPattern.MatchString(input.JobID) ||
		(input.Action != "DEPLOY" && input.Action != "ROLLBACK")) {
		return errors.New("DEPLOYMENT_ARGUMENT_INVALID")
	}
	if input.Mode == "SSH" {
		return validateSSH(input)
	}
	return nil
}

// validateMonitor 仅验证实时监控所需的模式和 SSH 身份，不依赖 Compose 参数。
func validateMonitor(input request) error {
	if input.Mode != "LOCAL" && input.Mode != "SSH" {
		return errors.New("MODE_INVALID")
	}
	if input.Mode == "SSH" {
		return validateSSH(input)
	}
	return nil
}

// validateSSH 统一验证 SSH 目标、主机指纹和认证凭据。
func validateSSH(input request) error {
	invalidSSH := !hostPattern.MatchString(input.Host) || input.Port < 1 || input.Port > 65535
	invalidSSH = invalidSSH || !usernamePattern.MatchString(input.Username) || !fingerprintPattern.MatchString(input.HostKey)
	if invalidSSH {
		return errors.New("SSH_CONFIGURATION_INVALID")
	}
	if input.AuthType == "KEY" && input.PrivateKey == "" {
		return errors.New("SSH_PRIVATE_KEY_REQUIRED")
	}
	if input.AuthType == "PASSWORD" && input.Password == "" {
		return errors.New("SSH_PASSWORD_REQUIRED")
	}
	if input.AuthType != "KEY" && input.AuthType != "PASSWORD" {
		return errors.New("SSH_AUTH_TYPE_INVALID")
	}
	return nil
}

// collectMonitor 使用固定脚本采集资源快照，SSH 模式仍执行严格主机指纹校验。
func collectMonitor(ctx context.Context, input request) (monitorResult, error) {
	diskPath := "/workspace"
	var output string
	var err error
	if input.Mode == "SSH" {
		diskPath = "/"
		output, err = runRemoteCommand(ctx, input, monitorCommand(diskPath))
	} else {
		if _, statErr := os.Stat(diskPath); statErr != nil {
			diskPath = "/"
		}
		command := exec.CommandContext(ctx, "sh", "-c", monitorCommand(diskPath))
		output, err = commandOutput(command)
	}
	if err != nil {
		return monitorResult{}, errors.New(trimOutput(err.Error()))
	}
	return parseMonitorOutput(output, diskPath)
}

// monitorCommand 返回不含用户输入的固定只读采集脚本。
func monitorCommand(diskPath string) string {
	return `set -eu
cpu_first="$(head -n 1 /proc/stat)"
sleep 1
cpu_second="$(head -n 1 /proc/stat)"
cpu_cores="$(grep -c '^processor' /proc/cpuinfo || true)"
load_average="$(cat /proc/loadavg)"
uptime_value="$(cat /proc/uptime)"
memory_values="$(awk '/^MemTotal:/ {total=$2} /^MemAvailable:/ {available=$2} /^MemFree:/ {free=$2} /^Buffers:/ {buffers=$2} /^Cached:/ {cached=$2} END {if (available == "") available=free+buffers+cached; print total, available}' /proc/meminfo)"
disk_values="$(df -Pk ` + shellQuote(diskPath) + ` | awk 'NR == 2 {print $2, $3, $4}')"
printf 'BASEAI_CPU_FIRST\t%s\n' "$cpu_first"
printf 'BASEAI_CPU_SECOND\t%s\n' "$cpu_second"
printf 'BASEAI_CPU_CORES\t%s\n' "$cpu_cores"
printf 'BASEAI_LOAD\t%s\n' "$load_average"
printf 'BASEAI_UPTIME\t%s\n' "$uptime_value"
printf 'BASEAI_MEMORY\t%s\n' "$memory_values"
printf 'BASEAI_DISK\t%s\n' "$disk_values"
if container_values="$(docker ps -a --last 200 --no-trunc --format '{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.State}}\t{{.Status}}' 2>&1)"; then
  if [ -n "$container_values" ]; then printf '%s\n' "$container_values" | while IFS= read -r line; do printf 'BASEAI_CONTAINER\t%s\n' "$line"; done; fi
else
  container_error="$(printf '%s' "$container_values" | head -n 1)"
  printf 'BASEAI_CONTAINER_ERROR\t%s\n' "$container_error"
fi`
}

// parseMonitorOutput 将固定脚本输出转换为有界结构化监控结果。
func parseMonitorOutput(output string, diskPath string) (monitorResult, error) {
	values := make(map[string]string)
	containers := make([]containerStatus, 0)
	containerError := ""
	for _, line := range strings.Split(output, "\n") {
		parts := strings.SplitN(line, "\t", 2)
		if len(parts) != 2 {
			continue
		}
		switch parts[0] {
		case "BASEAI_CONTAINER":
			fields := strings.SplitN(parts[1], "\t", 5)
			if len(fields) != 5 || len(containers) >= 200 {
				continue
			}
			containers = append(containers, containerStatus{ID: shortID(fields[0]), Name: fields[1], Image: fields[2],
				State: strings.ToUpper(fields[3]), Health: containerHealth(fields[4]), Status: fields[4]})
		case "BASEAI_CONTAINER_ERROR":
			containerError = trimOutput(parts[1])
		default:
			values[parts[0]] = parts[1]
		}
	}
	host, err := parseHostMetrics(values, diskPath)
	if err != nil {
		return monitorResult{}, err
	}
	status := "SUCCEEDED"
	if containerError != "" {
		status = "PARTIAL"
	}
	return monitorResult{Status: status, CollectedAt: time.Now().UTC().Format(time.RFC3339), Host: host,
		Containers: containers, ContainerError: containerError}, nil
}

// parseHostMetrics 校验并计算 CPU、内存、磁盘、负载和运行时长。
func parseHostMetrics(values map[string]string, diskPath string) (hostMetrics, error) {
	first, err := parseCPUTimes(values["BASEAI_CPU_FIRST"])
	if err != nil {
		return hostMetrics{}, errors.New("MONITOR_OUTPUT_INVALID")
	}
	second, err := parseCPUTimes(values["BASEAI_CPU_SECOND"])
	if err != nil {
		return hostMetrics{}, errors.New("MONITOR_OUTPUT_INVALID")
	}
	cores, err := strconv.Atoi(strings.TrimSpace(values["BASEAI_CPU_CORES"]))
	loads, loadErr := parseFloats(values["BASEAI_LOAD"], 3)
	uptime, uptimeErr := parseFloats(values["BASEAI_UPTIME"], 1)
	memory, memoryErr := parseIntegers(values["BASEAI_MEMORY"], 2)
	disk, diskErr := parseIntegers(values["BASEAI_DISK"], 3)
	if err != nil || loadErr != nil || uptimeErr != nil || memoryErr != nil || diskErr != nil || cores < 1 ||
		memory[0] <= 0 || memory[0] < memory[1] || disk[0] <= 0 || disk[0] < disk[1] {
		return hostMetrics{}, errors.New("MONITOR_OUTPUT_INVALID")
	}
	totalDelta := second.total - first.total
	idleDelta := second.idle - first.idle
	if totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta {
		return hostMetrics{}, errors.New("MONITOR_OUTPUT_INVALID")
	}
	memoryTotal := memory[0] * 1024
	memoryUsed := (memory[0] - memory[1]) * 1024
	diskTotal := disk[0] * 1024
	diskUsed := disk[1] * 1024
	return hostMetrics{CPUCores: cores, CPUUsagePercent: percentage(totalDelta-idleDelta, totalDelta),
		Load1: loads[0], Load5: loads[1], Load15: loads[2], MemoryTotalBytes: memoryTotal,
		MemoryUsedBytes: memoryUsed, MemoryUsagePercent: percentage(memoryUsed, memoryTotal), DiskPath: diskPath,
		DiskTotalBytes: diskTotal, DiskUsedBytes: diskUsed, DiskUsagePercent: percentage(diskUsed, diskTotal),
		UptimeSeconds: int64(uptime[0])}, nil
}

type cpuTimes struct {
	total int64
	idle  int64
}

// parseCPUTimes 读取 Linux /proc/stat 的累计 CPU 时间并排除重复 guest 字段。
func parseCPUTimes(value string) (cpuTimes, error) {
	fields := strings.Fields(value)
	if len(fields) < 5 || fields[0] != "cpu" {
		return cpuTimes{}, errors.New("invalid cpu data")
	}
	limit := len(fields)
	if limit > 9 {
		limit = 9
	}
	var total int64
	var idle int64
	for index := 1; index < limit; index++ {
		number, err := strconv.ParseInt(fields[index], 10, 64)
		if err != nil || number < 0 {
			return cpuTimes{}, errors.New("invalid cpu data")
		}
		total += number
		if index == 4 || index == 5 {
			idle += number
		}
	}
	return cpuTimes{total: total, idle: idle}, nil
}

// parseFloats 读取监控输出前若干个非负浮点数。
func parseFloats(value string, count int) ([]float64, error) {
	fields := strings.Fields(value)
	if len(fields) < count {
		return nil, errors.New("invalid numeric data")
	}
	result := make([]float64, count)
	for index := 0; index < count; index++ {
		number, err := strconv.ParseFloat(fields[index], 64)
		if err != nil || number < 0 || math.IsInf(number, 0) || math.IsNaN(number) {
			return nil, errors.New("invalid numeric data")
		}
		result[index] = number
	}
	return result, nil
}

// parseIntegers 读取监控输出前若干个非负整数。
func parseIntegers(value string, count int) ([]int64, error) {
	fields := strings.Fields(value)
	if len(fields) < count {
		return nil, errors.New("invalid numeric data")
	}
	result := make([]int64, count)
	for index := 0; index < count; index++ {
		number, err := strconv.ParseInt(fields[index], 10, 64)
		if err != nil || number < 0 {
			return nil, errors.New("invalid numeric data")
		}
		result[index] = number
	}
	return result, nil
}

// percentage 计算一位小数的有界百分比。
func percentage(used int64, total int64) float64 {
	if total <= 0 {
		return 0
	}
	value := float64(used) * 100 / float64(total)
	value = math.Max(0, math.Min(100, value))
	return math.Round(value*10) / 10
}

// shortID 只返回容器编号前十二位，避免无意义地扩大响应。
func shortID(value string) string {
	if len(value) > 12 {
		return value[:12]
	}
	return value
}

// containerHealth 从 Docker 状态描述中提取标准健康状态。
func containerHealth(status string) string {
	normalized := strings.ToLower(status)
	if strings.Contains(normalized, "(healthy)") {
		return "HEALTHY"
	}
	if strings.Contains(normalized, "(unhealthy)") {
		return "UNHEALTHY"
	}
	if strings.Contains(normalized, "(health: starting)") {
		return "STARTING"
	}
	return "NONE"
}

// local 仅对只读挂载的固定工作区执行 Compose 校验或启动。
func local(ctx context.Context, input request, execute bool) (string, error) {
	args := []string{"compose", "--project-directory", input.WorkingDir, "-f", filepath.Join(input.WorkingDir, input.ComposeFile), "config", "--quiet"}
	if execute {
		args = []string{"compose", "--project-directory", input.WorkingDir, "-f", filepath.Join(input.WorkingDir, input.ComposeFile), "up", "-d", "--no-build"}
	}
	command := exec.CommandContext(ctx, "docker", args...)
	revision := "validation"
	if execute {
		revision = input.Revision
	}
	command.Env = append(os.Environ(), "APP_IMAGE_REVISION="+revision)
	output, err := command.CombinedOutput()
	return string(output), err
}

// remote 校验远端 Host Key 后执行由安全参数拼接的固定 Compose 命令。
func remote(ctx context.Context, input request, execute bool) (string, error) {
	remoteCommand := "cd " + shellQuote(input.WorkingDir) + " && APP_IMAGE_REVISION=validation docker compose -f " + shellQuote(input.ComposeFile) + " config --quiet"
	if execute {
		remoteCommand = "cd " + shellQuote(input.WorkingDir) + " && APP_IMAGE_REVISION=" + shellQuote(input.Revision) + " docker compose -f " + shellQuote(input.ComposeFile) + " up -d --no-build"
	}
	return runRemoteCommand(ctx, input, remoteCommand)
}

// runRemoteCommand 校验 Host Key 后执行平台内部构造的固定远端命令。
func runRemoteCommand(ctx context.Context, input request, remoteCommand string) (string, error) {
	tempDir, err := os.MkdirTemp("", "deployment-agent-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tempDir)
	knownHosts := filepath.Join(tempDir, "known_hosts")
	scanContext, cancelScan := context.WithTimeout(ctx, 10*time.Second)
	defer cancelScan()
	scan := exec.CommandContext(scanContext, "ssh-keyscan", "-T", "5", "-p", fmt.Sprint(input.Port), input.Host)
	keyData, err := scan.Output()
	if err != nil {
		return "", errors.New("SSH_HOST_KEY_SCAN_FAILED")
	}
	candidate := filepath.Join(tempDir, "candidate_host_key")
	trustedKeys, err := matchingHostKeys(keyData, input.HostKey, func(line string) (string, error) {
		if writeErr := os.WriteFile(candidate, []byte(line+"\n"), 0600); writeErr != nil {
			return "", writeErr
		}
		output, commandErr := exec.CommandContext(ctx, "ssh-keygen", "-lf", candidate, "-E", "sha256").Output()
		return string(output), commandErr
	})
	if err != nil {
		return "", errors.New("SSH_HOST_KEY_MISMATCH")
	}
	if err := os.WriteFile(knownHosts, trustedKeys, 0600); err != nil {
		return "", err
	}
	args := []string{"-F", "/dev/null", "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + knownHosts,
		"-o", "GlobalKnownHostsFile=/dev/null", "-o", "CanonicalizeHostname=no", "-o", "ConnectTimeout=10",
		"-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", "-p", fmt.Sprint(input.Port)}
	if input.AuthType == "KEY" {
		privateKey := filepath.Join(tempDir, "id_key")
		if err := os.WriteFile(privateKey, []byte(input.PrivateKey), 0600); err != nil {
			return "", err
		}
		args = append(args, "-o", "IdentitiesOnly=yes", "-o", "PasswordAuthentication=no", "-i", privateKey)
		if input.Passphrase == "" {
			args = append(args, "-o", "BatchMode=yes")
		}
	} else {
		args = append(args, "-o", "BatchMode=no", "-o", "PubkeyAuthentication=no",
			"-o", "PreferredAuthentications=password,keyboard-interactive")
	}
	args = append(args, input.Username+"@"+input.Host, remoteCommand)
	command := exec.CommandContext(ctx, "ssh", args...)
	if input.AuthType == "PASSWORD" {
		command = exec.CommandContext(ctx, "sshpass", append([]string{"-e", "ssh"}, args...)...)
		command.Env = append(os.Environ(), "SSHPASS="+input.Password)
	}
	if input.AuthType == "KEY" && input.Passphrase != "" {
		command = exec.CommandContext(ctx, "sshpass", append([]string{"-e", "-P", "Enter passphrase", "ssh"}, args...)...)
		command.Env = append(os.Environ(), "SSHPASS="+input.Passphrase)
	}
	return commandOutput(command)
}

// containsFingerprint 精确匹配 ssh-keygen 输出中的 SHA-256 指纹字段。
func containsFingerprint(output string, expected string) bool {
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[1] == expected {
			return true
		}
	}
	return false
}

// matchingHostKeys 只保留与预期指纹对应的具体公钥，避免信任扫描结果中的其他密钥。
func matchingHostKeys(keyData []byte, expected string, fingerprint func(string) (string, error)) ([]byte, error) {
	trusted := make([]string, 0)
	for _, line := range strings.Split(string(keyData), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		output, err := fingerprint(line)
		if err == nil && containsFingerprint(output, expected) {
			trusted = append(trusted, line)
		}
	}
	if len(trusted) == 0 {
		return nil, errors.New("SSH_HOST_KEY_MISMATCH")
	}
	return []byte(strings.Join(trusted, "\n") + "\n"), nil
}

// commandOutput 收集受限长度的子进程结果供上层处理。
func commandOutput(command *exec.Cmd) (string, error) {
	output, err := command.CombinedOutput()
	return string(output), err
}

// shellQuote 引用已通过格式校验的远端路径和值。
func shellQuote(value string) string { return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'" }

// trimOutput 限制返回内容，避免命令输出耗尽接口或数据库空间。
func trimOutput(value string) string {
	value = strings.TrimSpace(value)
	if len(value) > 4000 {
		return value[len(value)-4000:]
	}
	return value
}

// writeJSON 写入结构化 Agent 响应。
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
