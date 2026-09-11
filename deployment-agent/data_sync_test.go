package main

import (
	"context"
	"encoding/json"
	"os/exec"
	"strings"
	"testing"
)

// TestValidateDataSync 覆盖固定动作、镜像和任务编号边界。
func TestValidateDataSync(t *testing.T) {
	validTask := json.RawMessage(`{"action":"RUN","source":{"password":"secret"}}`)
	input := request{Mode: "LOCAL", WorkerImage: "base-ai-backend:abcdef", JobID: "0123456789abcdef0123456789abcdef", Task: validTask}
	if err := validateDataSync(input, true); err != nil {
		t.Fatalf("valid task rejected: %v", err)
	}
	input.WorkerImage = "-v /:/host"
	if err := validateDataSync(input, true); err == nil {
		t.Fatal("unsafe worker image accepted")
	}
	input.WorkerImage = "base-ai-backend:abcdef"
	input.Task = json.RawMessage(`{"action":"TABLES"}`)
	if err := validateDataSync(input, true); err == nil {
		t.Fatal("query action accepted by execution endpoint")
	}
}

// TestDataSyncDockerArgs 验证容器入口固定且任务凭据不会进入参数。
func TestDataSyncDockerArgs(t *testing.T) {
	args := dataSyncDockerArgs("base-ai-backend:abcdef", "0123456789abcdef0123456789abcdef")
	joined := strings.Join(args, " ")
	for _, expected := range []string{"--read-only", "--cap-drop ALL", "no-new-privileges",
		"com.baseai.platform.datasync.DataSyncRemoteWorker", "base-ai-data-sync-0123456789abcdef0123456789abcdef"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("missing fixed argument %q in %q", expected, joined)
		}
	}
	if strings.Contains(joined, "password") || strings.Contains(joined, "secret") {
		t.Fatalf("credentials leaked into arguments: %q", joined)
	}
}

// TestRunDataSyncProcess 验证标准输入传递和 NDJSON 进度提取。
func TestRunDataSyncProcess(t *testing.T) {
	command := exec.CommandContext(context.Background(), "sh", "-c",
		`read payload; printf '%s\n' '{"status":"RUNNING","completedTables":0}' '{"status":"SUCCESS","completedTables":1}'`)
	seen := 0
	result, errorOutput, err := runDataSyncProcess(command, json.RawMessage(`{"password":"secret"}`), func(json.RawMessage) { seen++ })
	if err != nil {
		t.Fatalf("process failed: %v (%s)", err, errorOutput)
	}
	if seen != 2 || !strings.Contains(string(result), `"status":"SUCCESS"`) {
		t.Fatalf("unexpected progress: seen=%d result=%s", seen, result)
	}
}

// TestBoundedBuffer 验证外部错误输出被截断但写入语义保持兼容。
func TestBoundedBuffer(t *testing.T) {
	buffer := &boundedBuffer{limit: 4}
	count, err := buffer.Write([]byte("123456"))
	if err != nil || count != 6 || buffer.String() != "1234" {
		t.Fatalf("unexpected bounded write: count=%d value=%q error=%v", count, buffer.String(), err)
	}
}
