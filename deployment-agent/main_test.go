package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
