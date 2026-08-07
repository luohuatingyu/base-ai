package com.baseai.platform.workflow;

import com.baseai.platform.security.ApiKeyEndpoint;
import com.baseai.platform.security.ApiKeyField;
import com.baseai.platform.security.ApiKeyRisk;
import com.baseai.platform.security.RequiredPermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暴露可由 API Key 授权的已发布工作流执行与查询接口。 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowOpenController {
    private final WorkflowExecutionService executionService;

    /** 注入异步执行服务。 */
    public WorkflowOpenController(WorkflowExecutionService executionService) { this.executionService = executionService; }

    /** 按稳定编码异步启动已发布工作流。 */
    @PostMapping("/{code}/runs")
    @RequiredPermission("workflow:canvas:execute")
    @ApiKeyEndpoint(code = "workflow.execute", nameKey = "apiKeys.endpointNames.workflowExecute",
        groupKey = "apiKeys.endpointGroups.workflow", descriptionKey = "openPlatform.endpointDescriptions.workflowExecute",
        risk = ApiKeyRisk.HIGH,
        pathParameters = @ApiKeyField(name = "code", descriptionKey = "openPlatform.fields.workflowCode",
            type = "string", required = true, example = "ORDER_REVIEW"),
        requestFields = @ApiKeyField(name = "inputs", descriptionKey = "openPlatform.fields.workflowInputs",
            type = "object", required = true, example = "{\"orderId\":123}"),
        responseFields = {
            @ApiKeyField(name = "success", descriptionKey = "openPlatform.fields.success", type = "boolean", required = true, example = "true"),
            @ApiKeyField(name = "code", descriptionKey = "openPlatform.fields.code", type = "integer", required = true, example = "200"),
            @ApiKeyField(name = "message", descriptionKey = "openPlatform.fields.message", type = "string", required = true, example = "Success"),
            @ApiKeyField(name = "data.runId", descriptionKey = "openPlatform.fields.workflowRunId", type = "string", required = true, example = "550e8400-e29b-41d4-a716-446655440000"),
            @ApiKeyField(name = "data.status", descriptionKey = "openPlatform.fields.workflowStatus", type = "string", required = true, example = "QUEUED")
        },
        requestExample = "{\n  \"inputs\": {\n    \"orderId\": 123\n  }\n}",
        responseExample = "{\n  \"success\": true,\n  \"code\": 200,\n  \"message\": \"Success\",\n  \"data\": {\n    \"runId\": \"550e8400-e29b-41d4-a716-446655440000\",\n    \"status\": \"QUEUED\"\n  }\n}")
    public ResponseEntity<WorkflowModels.RunAccepted> execute(@PathVariable String code,
                                                              @RequestBody(required = false) WorkflowModels.RunCommand command) {
        return ResponseEntity.accepted().body(executionService.startPublished(code, command == null ? null : command.inputs()));
    }

    /** 查询属于当前 API Key 绑定用户的运行结果。 */
    @GetMapping("/runs/{runId}")
    @RequiredPermission("workflow:canvas:logs")
    @ApiKeyEndpoint(code = "workflow.run.read", nameKey = "apiKeys.endpointNames.workflowRunRead",
        groupKey = "apiKeys.endpointGroups.workflow", descriptionKey = "openPlatform.endpointDescriptions.workflowRunRead",
        risk = ApiKeyRisk.SENSITIVE,
        pathParameters = @ApiKeyField(name = "runId", descriptionKey = "openPlatform.fields.workflowRunId",
            type = "string", required = true, example = "550e8400-e29b-41d4-a716-446655440000"),
        responseFields = {
            @ApiKeyField(name = "success", descriptionKey = "openPlatform.fields.success", type = "boolean", required = true, example = "true"),
            @ApiKeyField(name = "data.status", descriptionKey = "openPlatform.fields.workflowStatus", type = "string", required = true, example = "SUCCESS"),
            @ApiKeyField(name = "data.output", descriptionKey = "openPlatform.fields.workflowOutput", type = "object", example = "{\"approved\":true}"),
            @ApiKeyField(name = "data.errorMessage", descriptionKey = "openPlatform.fields.workflowError", type = "string", example = "")
        },
        responseExample = "{\n  \"success\": true,\n  \"code\": 200,\n  \"message\": \"Success\",\n  \"data\": {\n    \"status\": \"SUCCESS\",\n    \"output\": {\"approved\": true}\n  }\n}")
    public WorkflowModels.RunView result(@PathVariable String runId) { return executionService.run(runId); }
}
