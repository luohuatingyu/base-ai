package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestValidateLocal 验证固定本地工作区和发布版本能够通过校验。
func TestValidateLocal(t *testing.T) {
	input := request{Mode: "LOCAL", WorkingDir: "/workspace", ComposeFile: "docker-compose.yml", Action: "DEPLOY", Revision: "abc123", JobID: "0123456789abcdef0123456789abcdef"}
	if err := validate(input, true); err != nil {
		t.Fatalf("expected valid local request: %v", err)
	}
}

// TestConnectionValidationDoesNotRequireCompose 验证连接测试不依赖部署目录和 Compose 文件。
func TestConnectionValidationDoesNotRequireCompose(t *testing.T) {
	input := request{Mode: "SSH", Host: "example.com", Port: 22, Username: "deploy", AuthType: "KEY",
		PrivateKey: "PRIVATE", HostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
	if err := validate(input, false); err != nil {
		t.Fatalf("expected connection-only request to be valid: %v", err)
	}
	input.Action = "DEPLOY"
	input.Revision = "abc123"
	input.JobID = "0123456789abcdef0123456789abcdef"
	if err := validate(input, true); err != nil {
		t.Fatalf("expected deployment request to allow automatic Compose detection: %v", err)
	}
}

// TestLocalConnectionHandlerDoesNotRequireCompose 验证本地连接测试无需 Compose 配置即可成功。
func TestLocalConnectionHandlerDoesNotRequireCompose(t *testing.T) {
	agent := &agent{token: "internal-token-with-24-characters"}
	body, _ := json.Marshal(request{Mode: "LOCAL"})
	request := httptest.NewRequest(http.MethodPost, "/test", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+agent.token)
	response := httptest.NewRecorder()

	agent.test(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("unexpected connection test status: %d, body: %s", response.Code, response.Body.String())
	}
}

// TestValidateRejectsCommandInjection 验证路径、文件和版本中的命令注入输入被拒绝。
func TestValidateRejectsCommandInjection(t *testing.T) {
	input := request{Mode: "LOCAL", WorkingDir: "/workspace;rm", ComposeFile: "docker-compose.yml", Action: "DEPLOY", Revision: "abc123", JobID: "0123456789abcdef0123456789abcdef"}
	if err := validate(input, true); err == nil {
		t.Fatal("expected invalid working directory")
	}
	input.WorkingDir = "/workspace"
	input.ComposeFile = "compose.yml;id"
	if err := validate(input, true); err == nil {
		t.Fatal("expected invalid compose file")
	}
	input.ComposeFile = "docker-compose.yml"
	input.WorkingDir = "/workspace/../tmp"
	if err := validate(input, true); err == nil {
		t.Fatal("expected non-normalized working directory")
	}
	input.WorkingDir = "/workspace"
	input.Revision = "registry:tag"
	if err := validate(input, true); err == nil {
		t.Fatal("expected invalid Docker tag revision")
	}
}

// TestValidateSSHRequiresHostKeyAndCredential 验证 SSH 模式必须提供完整指纹和凭据。
func TestValidateSSHRequiresHostKeyAndCredential(t *testing.T) {
	input := request{Mode: "SSH", Host: "example.com", Port: 22, Username: "deploy", AuthType: "KEY", WorkingDir: "/opt/base-ai", ComposeFile: "docker-compose.yml", Action: "DEPLOY", Revision: "abc123", JobID: "0123456789abcdef0123456789abcdef"}
	if err := validate(input, true); err == nil {
		t.Fatal("expected host key and key validation")
	}
	input.HostKey, input.PrivateKey = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "PRIVATE"
	if err := validate(input, true); err != nil {
		t.Fatalf("expected valid SSH request: %v", err)
	}
}

// TestValidateMonitorOnlyRequiresConnection 验证实时监控不要求部署目录但仍严格校验 SSH 身份。
func TestValidateMonitorOnlyRequiresConnection(t *testing.T) {
	local := request{Mode: "LOCAL"}
	if err := validateMonitor(local); err != nil {
		t.Fatalf("expected valid local monitor request: %v", err)
	}
	ssh := request{Mode: "SSH", Host: "example.com", Port: 22, Username: "deploy", AuthType: "PASSWORD",
		Password: "secret", HostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
	if err := validateMonitor(ssh); err != nil {
		t.Fatalf("expected valid SSH monitor request: %v", err)
	}
	ssh.Username = "-oProxyCommand=id"
	if err := validateMonitor(ssh); err == nil {
		t.Fatal("expected unsafe monitor identity to be rejected")
	}
}

// TestValidateRejectsUnsafeSSHIdentity 验证 SSH 用户名和指纹不能携带选项注入或部分匹配值。
func TestValidateRejectsUnsafeSSHIdentity(t *testing.T) {
	input := request{Mode: "SSH", Host: "example.com", Port: 22, Username: "-oProxyCommand=id", AuthType: "KEY",
		PrivateKey: "PRIVATE", HostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
		WorkingDir: "/opt/base-ai", ComposeFile: "docker-compose.yml", Action: "DEPLOY", Revision: "abc123", JobID: "0123456789abcdef0123456789abcdef"}
	if err := validate(input, true); err == nil {
		t.Fatal("expected unsafe SSH username to be rejected")
	}
	input.Username, input.HostKey = "deploy", "SHA256:AAAA"
	if err := validate(input, true); err == nil {
		t.Fatal("expected partial host fingerprint to be rejected")
	}
}

// TestSelectComposeProjectPrefersUniqueBaseAI 验证多项目环境只选择唯一的 Base AI 项目并去重。
func TestSelectComposeProjectPrefersUniqueBaseAI(t *testing.T) {
	output := "warning\nBASEAI_COMPOSE\t/opt/other\tcompose.yml\tOTHER\n" +
		"BASEAI_COMPOSE\t/opt/base-ai\tdocker-compose.yml\tBASE_AI\n" +
		"BASEAI_COMPOSE\t/opt/base-ai\tdocker-compose.yml\tBASE_AI\n"

	project, err := selectComposeProject(parseComposeProjects(output))

	if err != nil || project.WorkingDir != "/opt/base-ai" || project.ComposeFile != "docker-compose.yml" {
		t.Fatalf("unexpected detected project: %#v, %v", project, err)
	}
}

// TestSelectComposeProjectRejectsAmbiguousAndUnsafeCandidates 验证无法区分或路径不安全时不执行部署。
func TestSelectComposeProjectRejectsAmbiguousAndUnsafeCandidates(t *testing.T) {
	_, ambiguous := selectComposeProject([]composeProject{
		{WorkingDir: "/opt/base-ai-a", ComposeFile: "compose.yml", BaseAI: true},
		{WorkingDir: "/opt/base-ai-b", ComposeFile: "compose.yml", BaseAI: true},
	})
	if ambiguous == nil || ambiguous.Error() != "COMPOSE_PROJECT_AMBIGUOUS" {
		t.Fatalf("expected ambiguous project error: %v", ambiguous)
	}
	_, unsafe := selectComposeProject([]composeProject{{WorkingDir: "/opt/base-ai;id", ComposeFile: "compose.yml", BaseAI: true}})
	if unsafe == nil || unsafe.Error() != "COMPOSE_PROJECT_NOT_FOUND" {
		t.Fatalf("expected unsafe project to be ignored: %v", unsafe)
	}
	many := make([]composeProject, 41)
	for index := range many {
		many[index] = composeProject{WorkingDir: fmt.Sprintf("/opt/project-%d", index), ComposeFile: "compose.yml"}
	}
	if _, err := selectComposeProject(many); err == nil || err.Error() != "COMPOSE_PROJECT_LIMIT_EXCEEDED" {
		t.Fatalf("expected candidate limit error: %v", err)
	}
}

// TestDetectLocalComposeProjectRequiresUniqueSupportedFile 验证本地仅自动接受唯一受支持文件。
func TestDetectLocalComposeProjectRequiresUniqueSupportedFile(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "compose.yml"), []byte("services: {}"), 0600); err != nil {
		t.Fatal(err)
	}
	project, err := detectLocalComposeProject(root)
	if err != nil || project.WorkingDir != root || project.ComposeFile != "compose.yml" {
		t.Fatalf("unexpected local project: %#v, %v", project, err)
	}
	if err := os.WriteFile(filepath.Join(root, "docker-compose.yml"), []byte("services: {}"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := detectLocalComposeProject(root); err == nil || err.Error() != "COMPOSE_PROJECT_AMBIGUOUS" {
		t.Fatalf("expected ambiguous local project: %v", err)
	}
}

// TestComposeDiscoveryCommandFindsBaseAIHomeProject 验证固定远端脚本可从 SSH 用户目录识别 Base AI 服务集合。
func TestComposeDiscoveryCommandFindsBaseAIHomeProject(t *testing.T) {
	root := t.TempDir()
	projectDir := filepath.Join(root, "base-ai")
	binDir := filepath.Join(root, "bin")
	if err := os.MkdirAll(projectDir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(binDir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(projectDir, "compose.yml"), []byte("services: {}"), 0600); err != nil {
		t.Fatal(err)
	}
	fakeDocker := "#!/bin/sh\nif [ \"$1\" = ps ]; then exit 0; fi\nprintf 'backend\\nfrontend\\ncaddy\\n'\n"
	if err := os.WriteFile(filepath.Join(binDir, "docker"), []byte(fakeDocker), 0700); err != nil {
		t.Fatal(err)
	}
	command := exec.Command("sh", "-c", composeDiscoveryCommand())
	command.Env = []string{"HOME=" + root, "PATH=" + binDir + ":/usr/bin:/bin"}
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("unexpected discovery command failure: %v, %s", err, output)
	}
	project, err := selectComposeProject(parseComposeProjects(string(output)))
	if err != nil || project.WorkingDir != projectDir || !project.BaseAI {
		t.Fatalf("unexpected discovered project: %#v, %v, output: %s", project, err, output)
	}
}

// TestContainsFingerprintRequiresExactWithoutPartial 验证 Host Key 只能精确匹配完整指纹字段。
func TestContainsFingerprintRequiresExactWithoutPartial(t *testing.T) {
	fingerprint := "256 SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA host (ED25519)"
	if !containsFingerprint(fingerprint, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") {
		t.Fatal("expected exact fingerprint match")
	}
	if containsFingerprint(fingerprint, "SHA256:AAAA") {
		t.Fatal("partial fingerprint must not match")
	}
}

// TestMatchingHostKeysOnlyKeepsExpectedKey 验证扫描结果中的其他主机公钥不会被写入信任文件。
func TestMatchingHostKeysOnlyKeepsExpectedKey(t *testing.T) {
	keyData := []byte("host ssh-ed25519 expected\nhost ssh-rsa untrusted\n")
	trusted, err := matchingHostKeys(keyData, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", func(line string) (string, error) {
		if line == "host ssh-ed25519 expected" {
			return "256 SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA host (ED25519)", nil
		}
		return "3072 SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB host (RSA)", nil
	})
	if err != nil {
		t.Fatalf("expected matching key: %v", err)
	}
	if string(trusted) != "host ssh-ed25519 expected\n" {
		t.Fatalf("unexpected trusted keys: %q", trusted)
	}
}

// TestAuthorizedRequiresBearerScheme 验证内部令牌必须使用明确的 Bearer 认证格式。
func TestAuthorizedRequiresBearerScheme(t *testing.T) {
	agent := &agent{token: "internal-token-with-24-characters"}
	request, _ := http.NewRequest(http.MethodPost, "/test", nil)
	request.Header.Set("Authorization", agent.token)
	if agent.authorized(request) {
		t.Fatal("raw token must not be accepted")
	}
	request.Header.Set("Authorization", "Bearer "+agent.token)
	if !agent.authorized(request) {
		t.Fatal("Bearer token should be accepted")
	}
}

// TestJobsHandlerReturnsPersistedJob 验证任务编号可鉴权查询且结果不包含执行凭据。
func TestJobsHandlerReturnsPersistedJob(t *testing.T) {
	jobID := "0123456789abcdef0123456789abcdef"
	agent := &agent{token: "internal-token-with-24-characters", jobs: map[string]*deploymentJob{
		jobID: {status: "SUCCEEDED", output: "done", createdAt: time.Now(), finishedAt: time.Now()},
	}}
	request := httptest.NewRequest(http.MethodGet, "/jobs/"+jobID, nil)
	request.Header.Set("Authorization", "Bearer "+agent.token)
	response := httptest.NewRecorder()

	agent.jobsHandler(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d", response.Code)
	}
	var result map[string]string
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatalf("invalid response: %v", err)
	}
	if result["status"] != "SUCCEEDED" || result["output"] != "done" {
		t.Fatalf("unexpected job result: %#v", result)
	}
}

// TestExecuteReturnsExistingJob 验证 Backend 重试相同任务编号时不会重复执行部署。
func TestExecuteReturnsExistingJob(t *testing.T) {
	jobID := "0123456789abcdef0123456789abcdef"
	agent := &agent{token: "internal-token-with-24-characters", jobs: map[string]*deploymentJob{
		jobID: {status: "SUCCEEDED", output: "done", createdAt: time.Now(), finishedAt: time.Now()},
	}}
	body, _ := json.Marshal(request{Mode: "LOCAL", WorkingDir: "/workspace", ComposeFile: "docker-compose.yml",
		Action: "DEPLOY", Revision: "abc123", JobID: jobID})
	request := httptest.NewRequest(http.MethodPost, "/execute", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+agent.token)
	response := httptest.NewRecorder()

	agent.execute(response, request)

	if response.Code != http.StatusAccepted {
		t.Fatalf("unexpected status: %d", response.Code)
	}
	var result map[string]string
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatalf("invalid response: %v", err)
	}
	if result["status"] != "SUCCEEDED" || result["jobId"] != jobID {
		t.Fatalf("unexpected execute response: %#v", result)
	}
}

// TestParseMonitorOutput 验证主机资源正常解析并忽略旧容器数据。
func TestParseMonitorOutput(t *testing.T) {
	output := "BASEAI_CPU_FIRST\tcpu 100 0 50 850 0 0 0 0 0 0\n" +
		"BASEAI_CPU_SECOND\tcpu 160 0 70 970 0 0 0 0 0 0\n" +
		"BASEAI_CPU_CORES\t4\nBASEAI_LOAD\t0.25 0.50 0.75 1/100 123\n" +
		"BASEAI_UPTIME\t7200.50 100.00\nBASEAI_MEMORY\t1000 250\nBASEAI_DISK\t2000 500 1500\n" +
		"BASEAI_CONTAINER\t0123456789abcdef\tapi\tbase-ai:latest\trunning\tUp 2 minutes (healthy)\n" +
		"BASEAI_CONTAINER\tfedcba9876543210\tworker\tworker:latest\texited\tExited (1) 1 minute ago\n"

	result, err := parseMonitorOutput(output, "/")

	if err != nil {
		t.Fatalf("expected valid monitor output: %v", err)
	}
	if result.Status != "SUCCEEDED" || result.Host.CPUCores != 4 || result.Host.CPUUsagePercent != 40 {
		t.Fatalf("unexpected host metrics: %#v", result.Host)
	}
	if result.Host.MemoryUsagePercent != 75 || result.Host.DiskUsagePercent != 25 || result.Host.UptimeSeconds != 7200 {
		t.Fatalf("unexpected resource percentages: %#v", result.Host)
	}
	if len(result.Containers) != 0 {
		t.Fatalf("unexpected containers: %#v", result.Containers)
	}
}

// TestParseMonitorOutputAllowsContainerFailure 验证旧 Docker 错误不再影响主机监控结果。
func TestParseMonitorOutputAllowsContainerFailure(t *testing.T) {
	output := "BASEAI_CPU_FIRST\tcpu 10 0 10 80 0 0 0 0\n" +
		"BASEAI_CPU_SECOND\tcpu 20 0 20 160 0 0 0 0\n" +
		"BASEAI_CPU_CORES\t2\nBASEAI_LOAD\t0 0 0\nBASEAI_UPTIME\t10 1\n" +
		"BASEAI_MEMORY\t100 50\nBASEAI_DISK\t100 20 80\nBASEAI_CONTAINER_ERROR\tDocker unavailable\n"

	result, err := parseMonitorOutput(output, "/")

	if err != nil || result.Status != "SUCCEEDED" || result.ContainerError != "" || len(result.Containers) != 0 {
		t.Fatalf("unexpected partial result: %#v, %v", result, err)
	}
}

// TestParseMonitorOutputRejectsMalformedMetrics 验证缺失或倒退的资源计数不会生成误导结果。
func TestParseMonitorOutputRejectsMalformedMetrics(t *testing.T) {
	output := "BASEAI_CPU_FIRST\tcpu 100 0 50 850\nBASEAI_CPU_SECOND\tcpu 90 0 40 800\n" +
		"BASEAI_CPU_CORES\t4\nBASEAI_LOAD\t0 0 0\nBASEAI_UPTIME\t10\nBASEAI_MEMORY\t100 50\nBASEAI_DISK\t100 20 80\n"
	if _, err := parseMonitorOutput(output, "/"); err == nil {
		t.Fatal("expected malformed resource counters to be rejected")
	}
}

// TestCollectMonitorReadsLiveLinuxMetrics 验证固定脚本可在最小 Linux 环境采集真实基础资源。
func TestCollectMonitorReadsLiveLinuxMetrics(t *testing.T) {
	if strings.Contains(monitorCommand("/"), "docker") || strings.Contains(monitorCommand("/"), "BASEAI_CONTAINER") {
		t.Fatal("host monitoring must not query Docker")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	result, err := collectMonitor(ctx, request{Mode: "LOCAL"})

	if err != nil {
		t.Fatalf("expected live host metrics: %v", err)
	}
	if result.Host.CPUCores < 1 || result.Host.MemoryTotalBytes < 1 || result.Host.DiskTotalBytes < 1 {
		t.Fatalf("unexpected live host metrics: %#v", result.Host)
	}
	if result.Status != "SUCCEEDED" {
		t.Fatalf("unexpected live monitor status: %s", result.Status)
	}
}

// TestMonitorHandlerRequiresAuthAndReturnsSnapshot 验证监控端点必须鉴权并返回实时结构化快照。
func TestMonitorHandlerRequiresAuthAndReturnsSnapshot(t *testing.T) {
	agent := &agent{token: "internal-token-with-24-characters", monitorRunner: func(context.Context, request) (monitorResult, error) {
		return monitorResult{Status: "SUCCEEDED", CollectedAt: time.Now().UTC().Format(time.RFC3339),
			Host: hostMetrics{CPUCores: 2}, Containers: []containerStatus{}}, nil
	}}
	body, _ := json.Marshal(request{Mode: "LOCAL"})
	unauthorized := httptest.NewRequest(http.MethodPost, "/monitor", bytes.NewReader(body))
	unauthorizedResponse := httptest.NewRecorder()
	agent.monitor(unauthorizedResponse, unauthorized)
	if unauthorizedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unexpected unauthorized status: %d", unauthorizedResponse.Code)
	}

	authorized := httptest.NewRequest(http.MethodPost, "/monitor", bytes.NewReader(body))
	authorized.Header.Set("Authorization", "Bearer "+agent.token)
	response := httptest.NewRecorder()
	agent.monitor(response, authorized)
	if response.Code != http.StatusOK {
		t.Fatalf("unexpected monitor status: %d", response.Code)
	}
	var result monitorResult
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil || result.Status != "SUCCEEDED" || result.Host.CPUCores != 2 {
		t.Fatalf("unexpected monitor response: %#v, %v", result, err)
	}
}
