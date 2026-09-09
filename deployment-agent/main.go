package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var revisionPattern = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$`)
var pathPattern = regexp.MustCompile(`^/[A-Za-z0-9_./-]{1,200}$`)
var filePattern = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,120}$`)
var hostPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9.:-]{0,253}$`)
var usernamePattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9._-]{0,63}$`)
var fingerprintPattern = regexp.MustCompile(`^SHA256:[A-Za-z0-9+/]{43}$`)

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
}

type agent struct{ token string }

func main() {
	token := os.Getenv("DEPLOYMENT_AGENT_INTERNAL_TOKEN")
	if len(token) < 24 {
		panic("DEPLOYMENT_AGENT_INTERNAL_TOKEN must contain at least 24 characters")
	}
	a := &agent{token: token}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", a.health)
	mux.HandleFunc("/test", a.test)
	mux.HandleFunc("/execute", a.execute)
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

// execute 执行白名单中的部署或回滚动作。
func (a *agent) execute(w http.ResponseWriter, r *http.Request) { a.handle(w, r, true) }

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
	var input request
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128*1024))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&input); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": "INVALID_REQUEST"})
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": "INVALID_REQUEST"})
		return
	}
	if err := validate(input, execute); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"status": "FAILED", "error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Minute)
	defer cancel()
	var output string
	var err error
	if input.Mode == "LOCAL" {
		output, err = local(ctx, input, execute)
	} else {
		output, err = remote(ctx, input, execute)
	}
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]string{"status": "FAILED", "error": trimOutput(err.Error())})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "SUCCEEDED", "output": trimOutput(output)})
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
	if execute && (!revisionPattern.MatchString(input.Revision) || (input.Action != "DEPLOY" && input.Action != "ROLLBACK")) {
		return errors.New("DEPLOYMENT_ARGUMENT_INVALID")
	}
	if input.Mode == "SSH" {
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
	}
	return nil
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
	remoteCommand := "cd " + shellQuote(input.WorkingDir) + " && APP_IMAGE_REVISION=validation docker compose -f " + shellQuote(input.ComposeFile) + " config --quiet"
	if execute {
		remoteCommand = "cd " + shellQuote(input.WorkingDir) + " && APP_IMAGE_REVISION=" + shellQuote(input.Revision) + " docker compose -f " + shellQuote(input.ComposeFile) + " up -d --no-build"
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
