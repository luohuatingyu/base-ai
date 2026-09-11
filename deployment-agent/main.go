package main

import (
	"bufio"
	"bytes"
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
var workerImagePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._/:@-]{0,255}$`)

type request struct {
	Mode        string          `json:"mode"`
	Host        string          `json:"host"`
	Port        int             `json:"port"`
	Username    string          `json:"username"`
	AuthType    string          `json:"authType"`
	PrivateKey  string          `json:"privateKey"`
	Password    string          `json:"password"`
	Passphrase  string          `json:"passphrase"`
	HostKey     string          `json:"hostKey"`
	WorkingDir  string          `json:"workingDir"`
	ComposeFile string          `json:"composeFile"`
	Action      string          `json:"action"`
	Revision    string          `json:"revision"`
	JobID       string          `json:"jobId"`
	WorkerImage string          `json:"workerImage"`
	Task        json.RawMessage `json:"task"`
}

type composeProject struct {
	WorkingDir  string
	ComposeFile string
	BaseAI      bool
}

type deploymentJob struct {
	status     string
	output     string
	error      string
	createdAt  time.Time
	finishedAt time.Time
	result     json.RawMessage
	cancel     context.CancelFunc
	dataSync   *request
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
	mux.HandleFunc("/data-sync/query", a.dataSyncQuery)
	mux.HandleFunc("/data-sync/execute", a.dataSyncExecute)
	mux.HandleFunc("/data-sync/jobs/", a.dataSyncJobs)
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

// dataSyncQuery 在所选服务器的一次性容器中执行表查询或同步预检。
func (a *agent) dataSyncQuery(w http.ResponseWriter, r *http.Request) {
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
	if err := validateDataSync(input, false); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Minute)
	defer cancel()
	result, _, err := runDataSync(ctx, input, "", nil)
	if err != nil || len(result) == 0 {
		writeJSON(w, http.StatusOK, map[string]string{"status": "FAILED", "error": "DATA_SYNC_EXECUTION_FAILED"})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(result)
}

// dataSyncExecute 以 Backend 预先持久化的任务编号启动远程同步。
func (a *agent) dataSyncExecute(w http.ResponseWriter, r *http.Request) {
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
	if err := validateDataSync(input, true); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": err.Error()})
		return
	}
	status := a.createAndStartDataSyncJob(input)
	writeJSON(w, http.StatusAccepted, map[string]string{"status": status, "jobId": input.JobID})
}

// dataSyncJobs 查询或取消远程同步任务，不回传任何连接配置。
func (a *agent) dataSyncJobs(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"status": "FAILED", "error": "UNAUTHORIZED"})
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/data-sync/jobs/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || !jobIDPattern.MatchString(parts[0]) {
		writeJSON(w, http.StatusNotFound, map[string]string{"status": "FAILED", "error": "JOB_NOT_FOUND"})
		return
	}
	if r.Method == http.MethodGet && len(parts) == 1 {
		a.writeJob(w, parts[0])
		return
	}
	if r.Method == http.MethodPost && len(parts) == 2 && parts[1] == "cancel" {
		a.cancelDataSyncJob(w, parts[0])
		return
	}
	writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"status": "FAILED", "error": "METHOD_NOT_ALLOWED"})
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
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	output, err := testConnection(ctx, input)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]string{"status": "FAILED", "error": trimOutput(err.Error())})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "SUCCEEDED", "output": trimOutput(output)})
}

// decodeRequest 限制请求体大小并拒绝未知字段和多余 JSON 内容。
func decodeRequest(w http.ResponseWriter, r *http.Request) (request, bool) {
	var input request
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024*1024))
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

// createAndStartDataSyncJob 创建带取消上下文的远程同步任务，重复请求只返回原状态。
func (a *agent) createAndStartDataSyncJob(input request) string {
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
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	inputCopy := input
	a.jobs[input.JobID] = &deploymentJob{status: "RUNNING", createdAt: time.Now(), cancel: cancel, dataSync: &inputCopy}
	a.mu.Unlock()
	go a.runDataSyncJob(ctx, input.JobID, inputCopy)
	return "RUNNING"
}

// runDataSyncJob 读取 Worker 的 NDJSON 进度并保存最新的无敏感结果。
func (a *agent) runDataSyncJob(ctx context.Context, jobID string, input request) {
	result, errorOutput, err := runDataSync(ctx, input, jobID, func(progress json.RawMessage) {
		a.mu.Lock()
		if job, exists := a.jobs[jobID]; exists && job.status == "RUNNING" {
			job.result = append(json.RawMessage(nil), progress...)
		}
		a.mu.Unlock()
	})
	a.mu.Lock()
	defer a.mu.Unlock()
	job, exists := a.jobs[jobID]
	if !exists || job.status == "CANCELLED" {
		return
	}
	job.finishedAt = time.Now()
	job.cancel = nil
	job.dataSync = nil
	if len(result) > 0 {
		job.result = append(json.RawMessage(nil), result...)
	}
	if err != nil {
		job.status = "FAILED"
		job.error = trimOutput(errorOutput)
		if job.error == "" {
			job.error = "DATA_SYNC_EXECUTION_FAILED"
		}
		return
	}
	var final struct {
		Status string `json:"status"`
		Error  string `json:"error"`
	}
	if len(job.result) == 0 || json.Unmarshal(job.result, &final) != nil || (final.Status != "SUCCESS" && final.Status != "FAILED") {
		job.status = "FAILED"
		job.error = "DATA_SYNC_RESULT_INVALID"
		return
	}
	if final.Status == "FAILED" {
		job.status = "FAILED"
		job.error = trimOutput(final.Error)
		return
	}
	job.status = "SUCCEEDED"
}

// cancelDataSyncJob 终止本地进程并强制清理命名 Worker 容器。
func (a *agent) cancelDataSyncJob(w http.ResponseWriter, jobID string) {
	a.mu.Lock()
	job, exists := a.jobs[jobID]
	if !exists || job.dataSync == nil {
		a.mu.Unlock()
		if exists {
			a.writeJob(w, jobID)
			return
		}
		writeJSON(w, http.StatusNotFound, map[string]string{"status": "FAILED", "error": "JOB_NOT_FOUND"})
		return
	}
	input := *job.dataSync
	cancel := job.cancel
	job.status = "CANCELLED"
	job.error = "cancelled"
	job.finishedAt = time.Now()
	job.cancel = nil
	job.dataSync = nil
	a.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	ctx, stop := context.WithTimeout(context.Background(), 20*time.Second)
	defer stop()
	_ = stopDataSyncContainer(ctx, input, jobID)
	a.writeJob(w, jobID)
}

// runJob 在独立超时上下文中执行 Compose，调用方重启不会中断任务。
func (a *agent) runJob(jobID string, input request) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()
	var output string
	var err error
	input, err = resolveComposeProject(ctx, input)
	if err == nil {
		if input.Mode == "LOCAL" {
			output, err = local(ctx, input, true)
		} else {
			output, err = remote(ctx, input, true)
		}
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
	result := map[string]any{"status": job.status, "jobId": jobID}
	if job.output != "" {
		result["output"] = job.output
	}
	if job.error != "" {
		result["error"] = job.error
	}
	if len(job.result) > 0 {
		result["result"] = json.RawMessage(job.result)
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

// validate 严格限制模式、兼容配置、版本和 SSH 参数。
func validate(input request, execute bool) error {
	if input.Mode != "LOCAL" && input.Mode != "SSH" {
		return errors.New("MODE_INVALID")
	}
	if err := validateOptionalCompose(input); err != nil {
		return err
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

// validateOptionalCompose 允许新服务器省略 Compose，同时严格验证旧服务器保留的配置。
func validateOptionalCompose(input request) error {
	if input.WorkingDir == "" && input.ComposeFile == "" {
		return nil
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

// validateDataSync 限制执行目标、固定 Worker 镜像、任务编号和 JSON 载荷。
func validateDataSync(input request, execute bool) error {
	if input.Mode != "LOCAL" && input.Mode != "SSH" {
		return errors.New("MODE_INVALID")
	}
	if input.Mode == "SSH" {
		if err := validateSSH(input); err != nil {
			return err
		}
	}
	if !workerImagePattern.MatchString(input.WorkerImage) || strings.Contains(input.WorkerImage, "..") {
		return errors.New("WORKER_IMAGE_INVALID")
	}
	if execute && !jobIDPattern.MatchString(input.JobID) {
		return errors.New("JOB_ID_INVALID")
	}
	if len(input.Task) == 0 || len(input.Task) > 768*1024 || !json.Valid(input.Task) {
		return errors.New("DATA_SYNC_TASK_INVALID")
	}
	var task struct {
		Action string `json:"action"`
	}
	if json.Unmarshal(input.Task, &task) != nil {
		return errors.New("DATA_SYNC_TASK_INVALID")
	}
	if execute && task.Action != "RUN" {
		return errors.New("DATA_SYNC_ACTION_INVALID")
	}
	if !execute && task.Action != "TABLES" && task.Action != "PREVIEW" {
		return errors.New("DATA_SYNC_ACTION_INVALID")
	}
	return nil
}

// runDataSync 使用固定 Docker 参数启动一次性 Worker，并增量读取结构化进度。
func runDataSync(ctx context.Context, input request, jobID string,
	progress func(json.RawMessage)) (json.RawMessage, string, error) {
	args := dataSyncDockerArgs(input.WorkerImage, jobID)
	var command *exec.Cmd
	cleanup := func() {}
	if input.Mode == "LOCAL" {
		command = exec.CommandContext(ctx, "docker", args...)
	} else {
		remoteCommand := "docker"
		for _, arg := range args {
			remoteCommand += " " + shellQuote(arg)
		}
		var err error
		command, cleanup, err = dataSyncRemoteCommand(ctx, input, remoteCommand)
		if err != nil {
			return nil, "", err
		}
	}
	defer cleanup()
	return runDataSyncProcess(command, input.Task, progress)
}

// dataSyncDockerArgs 返回不可由请求改变的容器隔离参数和 Java 入口。
func dataSyncDockerArgs(workerImage string, jobID string) []string {
	args := []string{"run", "--rm", "-i", "--network", "host", "--read-only", "--cap-drop", "ALL",
		"--security-opt", "no-new-privileges", "--pids-limit", "128", "--memory", "1g", "--cpus", "1.0",
		"--tmpfs", "/tmp:size=32m,mode=1700,uid=10001"}
	if jobID != "" {
		args = append(args, "--name", dataSyncContainerName(jobID))
	}
	return append(args, workerImage, "java", "-Xms32m", "-Xmx768m",
		"-Dloader.main=com.baseai.platform.datasync.DataSyncRemoteWorker", "-cp", "/app/app.jar",
		"org.springframework.boot.loader.launch.PropertiesLauncher")
}

// runDataSyncProcess 通过标准输入传递凭据，只保留 Worker 输出的最后一份合法 JSON。
func runDataSyncProcess(command *exec.Cmd, task json.RawMessage,
	progress func(json.RawMessage)) (json.RawMessage, string, error) {
	command.Stdin = bytes.NewReader(task)
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, "", err
	}
	stderr := &boundedBuffer{limit: 4096}
	command.Stderr = stderr
	if err := command.Start(); err != nil {
		return nil, stderr.String(), err
	}
	var latest json.RawMessage
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 4096), 256*1024)
	for scanner.Scan() {
		line := bytes.TrimSpace(scanner.Bytes())
		if len(line) == 0 || !json.Valid(line) {
			continue
		}
		latest = append(json.RawMessage(nil), line...)
		if progress != nil {
			progress(latest)
		}
	}
	scanErr := scanner.Err()
	waitErr := command.Wait()
	if scanErr != nil {
		return latest, stderr.String(), scanErr
	}
	return latest, stderr.String(), waitErr
}

// dataSyncRemoteCommand 为所有远程操作构造 SSH 进程，每次自动信任主机并隔离临时凭据。
func dataSyncRemoteCommand(ctx context.Context, input request, remoteCommand string) (*exec.Cmd, func(), error) {
	if err := validateSSH(input); err != nil {
		return nil, func() {}, err
	}
	tempDir, err := os.MkdirTemp("", "deployment-agent-")
	if err != nil {
		return nil, func() {}, err
	}
	cleanup := func() { _ = os.RemoveAll(tempDir) }
	args := []string{"-F", "/dev/null", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
		"-o", "GlobalKnownHostsFile=/dev/null", "-o", "CanonicalizeHostname=no", "-o", "ConnectTimeout=10",
		"-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", "-o", "IdentityAgent=none",
		"-o", "NumberOfPasswordPrompts=1", "-p", fmt.Sprint(input.Port)}
	if input.AuthType == "KEY" || input.AuthType == "KEY_PASSWORD" {
		privateKey := filepath.Join(tempDir, "id_key")
		if err := os.WriteFile(privateKey, []byte(input.PrivateKey), 0600); err != nil {
			cleanup()
			return nil, func() {}, err
		}
		args = append(args, "-o", "IdentitiesOnly=yes", "-i", privateKey)
	}
	switch input.AuthType {
	case "KEY":
		args = append(args, "-o", "PreferredAuthentications=publickey", "-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no")
	case "PASSWORD":
		args = append(args, "-o", "PubkeyAuthentication=no", "-o", "PreferredAuthentications=password,keyboard-interactive")
	case "KEY_PASSWORD":
		args = append(args, "-o", "PreferredAuthentications=publickey,password,keyboard-interactive")
	}
	askpass := filepath.Join(tempDir, "askpass")
	script := "#!/bin/sh\ncase \"$1\" in\n  *passphrase*) printf '%s\\n' \"$BASEAI_SSH_PASSPHRASE\" ;;\n  *assword*) printf '%s\\n' \"$BASEAI_SSH_PASSWORD\" ;;\n  *) exit 1 ;;\nesac\n"
	if err := os.WriteFile(askpass, []byte(script), 0700); err != nil {
		cleanup()
		return nil, func() {}, err
	}
	args = append(args, input.Username+"@"+input.Host, remoteCommand)
	command := exec.CommandContext(ctx, "ssh", args...)
	command.Env = append(os.Environ(), "LC_ALL=C", "DISPLAY=baseai:0", "SSH_ASKPASS_REQUIRE=force",
		"SSH_ASKPASS="+askpass, "BASEAI_SSH_PASSPHRASE="+input.Passphrase, "BASEAI_SSH_PASSWORD="+input.Password)
	return command, cleanup, nil
}

// stopDataSyncContainer 强制终止固定名称的本地或远端 Worker 容器。
func stopDataSyncContainer(ctx context.Context, input request, jobID string) error {
	name := dataSyncContainerName(jobID)
	if input.Mode == "LOCAL" {
		return exec.CommandContext(ctx, "docker", "rm", "-f", name).Run()
	}
	_, err := runRemoteCommand(ctx, input, "docker rm -f "+shellQuote(name)+" >/dev/null 2>&1 || true")
	return err
}

// dataSyncContainerName 将已验证的十六进制任务编号映射为固定容器名。
func dataSyncContainerName(jobID string) string { return "base-ai-data-sync-" + jobID }

// boundedBuffer 截断外部进程错误，避免异常输出耗尽 Agent 内存。
type boundedBuffer struct {
	bytes.Buffer
	limit int
}

// Write 只保存上限以内的错误字节，同时向调用方报告原始长度。
func (buffer *boundedBuffer) Write(value []byte) (int, error) {
	original := len(value)
	remaining := buffer.limit - buffer.Len()
	if remaining > 0 {
		if len(value) > remaining {
			value = value[:remaining]
		}
		_, _ = buffer.Buffer.Write(value)
	}
	return original, nil
}

// validateSSH 统一验证 SSH 目标和认证凭据，兼容忽略历史指纹字段。
func validateSSH(input request) error {
	invalidSSH := !hostPattern.MatchString(input.Host) || input.Port < 1 || input.Port > 65535
	invalidSSH = invalidSSH || !usernamePattern.MatchString(input.Username)
	if invalidSSH {
		return errors.New("SSH_CONFIGURATION_INVALID")
	}
	if (input.AuthType == "KEY" || input.AuthType == "KEY_PASSWORD") && strings.TrimSpace(input.PrivateKey) == "" {
		return errors.New("SSH_PRIVATE_KEY_REQUIRED")
	}
	if (input.AuthType == "PASSWORD" || input.AuthType == "KEY_PASSWORD") && strings.TrimSpace(input.Password) == "" {
		return errors.New("SSH_PASSWORD_REQUIRED")
	}
	if input.AuthType != "KEY" && input.AuthType != "PASSWORD" && input.AuthType != "KEY_PASSWORD" {
		return errors.New("SSH_AUTH_TYPE_INVALID")
	}
	return nil
}

// testConnection 仅验证 Agent 本地可用性或 SSH 登录，不依赖 Compose 项目。
func testConnection(ctx context.Context, input request) (string, error) {
	if input.Mode == "LOCAL" {
		return "CONNECTION_SUCCEEDED", nil
	}
	return runRemoteCommand(ctx, input, "printf 'BASEAI_CONNECTION_SUCCEEDED\\n'")
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
`
}

// parseMonitorOutput 将固定脚本输出转换为有界结构化监控结果。
func parseMonitorOutput(output string, diskPath string) (monitorResult, error) {
	values := make(map[string]string)
	for _, line := range strings.Split(output, "\n") {
		parts := strings.SplitN(line, "\t", 2)
		if len(parts) != 2 {
			continue
		}
		values[parts[0]] = parts[1]
	}
	host, err := parseHostMetrics(values, diskPath)
	if err != nil {
		return monitorResult{}, err
	}
	return monitorResult{Status: "SUCCEEDED", CollectedAt: time.Now().UTC().Format(time.RFC3339), Host: host,
		Containers: []containerStatus{}}, nil
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

// resolveComposeProject 优先兼容旧配置，否则在执行部署前自动检测唯一项目。
func resolveComposeProject(ctx context.Context, input request) (request, error) {
	if input.WorkingDir != "" || input.ComposeFile != "" {
		if err := validateOptionalCompose(input); err != nil {
			return request{}, err
		}
		return input, nil
	}
	var project composeProject
	var err error
	if input.Mode == "LOCAL" {
		project, err = detectLocalComposeProject("/workspace")
	} else {
		output, commandErr := runRemoteCommand(ctx, input, composeDiscoveryCommand())
		if commandErr != nil {
			return request{}, errors.New("COMPOSE_PROJECT_DETECTION_FAILED")
		}
		project, err = selectComposeProject(parseComposeProjects(output))
	}
	if err != nil {
		return request{}, err
	}
	input.WorkingDir = project.WorkingDir
	input.ComposeFile = project.ComposeFile
	return input, nil
}

// detectLocalComposeProject 检查固定本地工作区支持的 Compose 文件。
func detectLocalComposeProject(root string) (composeProject, error) {
	candidates := make([]composeProject, 0, 2)
	for _, name := range []string{"docker-compose.yml", "compose.yml"} {
		if info, err := os.Stat(filepath.Join(root, name)); err == nil && !info.IsDir() {
			candidates = append(candidates, composeProject{WorkingDir: root, ComposeFile: name, BaseAI: true})
		}
	}
	return selectComposeProject(candidates)
}

// parseComposeProjects 解析远端固定探测脚本的有界输出并忽略其他命令文本。
func parseComposeProjects(output string) []composeProject {
	projects := make([]composeProject, 0)
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Split(line, "\t")
		if len(fields) != 4 || fields[0] != "BASEAI_COMPOSE" {
			continue
		}
		projects = append(projects, composeProject{WorkingDir: fields[1], ComposeFile: fields[2], BaseAI: fields[3] == "BASE_AI"})
	}
	return projects
}

// selectComposeProject 验证、去重并确定唯一候选，优先选择唯一 Base AI 项目。
func selectComposeProject(projects []composeProject) (composeProject, error) {
	unique := make(map[string]composeProject)
	for _, project := range projects {
		if !pathPattern.MatchString(project.WorkingDir) || filepath.Clean(project.WorkingDir) != project.WorkingDir {
			continue
		}
		if project.ComposeFile != "docker-compose.yml" && project.ComposeFile != "compose.yml" {
			continue
		}
		unique[filepath.Join(project.WorkingDir, project.ComposeFile)] = project
		if len(unique) > 40 {
			return composeProject{}, errors.New("COMPOSE_PROJECT_LIMIT_EXCEEDED")
		}
	}
	baseAI := make([]composeProject, 0)
	all := make([]composeProject, 0, len(unique))
	for _, project := range unique {
		all = append(all, project)
		if project.BaseAI {
			baseAI = append(baseAI, project)
		}
	}
	if len(baseAI) == 1 {
		return baseAI[0], nil
	}
	if len(baseAI) > 1 || len(all) > 1 {
		return composeProject{}, errors.New("COMPOSE_PROJECT_AMBIGUOUS")
	}
	if len(all) == 1 {
		return all[0], nil
	}
	return composeProject{}, errors.New("COMPOSE_PROJECT_NOT_FOUND")
}

// composeDiscoveryCommand 返回不含用户输入的远端 Compose 项目探测脚本。
func composeDiscoveryCommand() string {
	return `emit_compose() {
  candidate="$1"
  [ -f "$candidate" ] || return 0
  case "$candidate" in
    */docker-compose.yml|*/compose.yml) ;;
    *) return 0 ;;
  esac
  directory="${candidate%/*}"
  [ -n "$directory" ] || directory="/"
  services="$(APP_IMAGE_REVISION=validation docker compose --project-directory "$directory" -f "$candidate" config --services 2>/dev/null)" || return 0
  signature="OTHER"
  if printf '%s\n' "$services" | grep -qx 'backend' && printf '%s\n' "$services" | grep -qx 'frontend' && printf '%s\n' "$services" | grep -qx 'caddy'; then
    signature="BASE_AI"
  fi
  printf 'BASEAI_COMPOSE\t%s\t%s\t%s\n' "$directory" "${candidate##*/}" "$signature"
}
docker ps -a --filter label=com.docker.compose.project --format '{{.Label "com.docker.compose.project.config_files"}}' 2>/dev/null | while IFS= read -r files; do
  printf '%s\n' "$files" | tr ',' '\n' | while IFS= read -r candidate; do emit_compose "$candidate"; done
done
for root in "$HOME" /opt /srv; do
  [ -d "$root" ] || continue
  find "$root" -maxdepth 4 -type f \( -name docker-compose.yml -o -name compose.yml \) 2>/dev/null
done | awk '!seen[$0]++' | head -n 40 | while IFS= read -r candidate; do emit_compose "$candidate"; done`
}

// remote 使用自动信任的 SSH 连接执行由安全参数拼接的固定 Compose 命令。
func remote(ctx context.Context, input request, execute bool) (string, error) {
	remoteCommand := "cd " + shellQuote(input.WorkingDir) + " && APP_IMAGE_REVISION=validation docker compose -f " + shellQuote(input.ComposeFile) + " config --quiet"
	if execute {
		remoteCommand = "cd " + shellQuote(input.WorkingDir) + " && APP_IMAGE_REVISION=" + shellQuote(input.Revision) + " docker compose -f " + shellQuote(input.ComposeFile) + " up -d --no-build"
	}
	return runRemoteCommand(ctx, input, remoteCommand)
}

// runRemoteCommand 复用 SSH 认证入口并在执行结束后清除临时凭据。
func runRemoteCommand(ctx context.Context, input request, remoteCommand string) (string, error) {
	command, cleanup, err := dataSyncRemoteCommand(ctx, input, remoteCommand)
	if err != nil {
		return "", err
	}
	defer cleanup()
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
