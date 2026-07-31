package com.baseai.platform.automation;

import com.baseai.platform.trace.TraceType;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.security.ApiKeyEndpoint;
import com.baseai.platform.security.ApiKeyField;
import com.baseai.platform.security.ApiKeyRisk;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/automation/api-triggers")
public class ApiTriggerController {
    private final ApiTriggerService service;
    private final ApiTriggerScheduler scheduler;

    public ApiTriggerController(ApiTriggerService service, ApiTriggerScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @GetMapping
    @RequiredPermission("automation:api-trigger:list")
    public List<ApiTriggerModels.View> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Boolean enabled) {
        return service.list(keyword, enabled);
    }

    @PostMapping
    @RequiredPermission("automation:api-trigger:create")
    @TraceType(value = "API_TRIGGER_CREATE", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.View create(@RequestBody ApiTriggerModels.Command command) {
        ApiTriggerModels.View created = service.create(command, AuthContext.require().id());
        scheduler.reschedule(created.id());
        return created;
    }

    @PutMapping("/{id}")
    @RequiredPermission("automation:api-trigger:update")
    @TraceType(value = "API_TRIGGER_UPDATE", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.View update(@PathVariable Long id, @RequestBody ApiTriggerModels.Command command) {
        ApiTriggerModels.View updated = service.update(id, command);
        scheduler.reschedule(id);
        return updated;
    }

    @DeleteMapping("/{id}")
    @RequiredPermission("automation:api-trigger:delete")
    @TraceType(value = "API_TRIGGER_DISABLE", triggerEntry = "MANUAL")
    public void disable(@PathVariable Long id) { service.disable(id); scheduler.cancel(id); }

    @PostMapping("/{id}/void")
    @RequiredPermission("automation:api-trigger:delete")
    @TraceType(value = "API_TRIGGER_VOID", triggerEntry = "MANUAL")
    public void voidConfig(@PathVariable Long id) { service.voidConfig(id); scheduler.cancel(id); }

    @PostMapping("/{id}/trigger")
    @RequiredPermission("automation:api-trigger:trigger")
    @ApiKeyEndpoint(code = "automation.api-trigger.execute", nameKey = "apiKeys.endpointNames.apiTriggerExecute",
        groupKey = "apiKeys.endpointGroups.automation",
        descriptionKey = "openPlatform.endpointDescriptions.apiTriggerExecute", risk = ApiKeyRisk.HIGH,
        pathParameters = {
            @ApiKeyField(name = "id", descriptionKey = "openPlatform.fields.triggerId", type = "integer",
                required = true, example = "1")
        },
        responseFields = {
            @ApiKeyField(name = "success", descriptionKey = "openPlatform.fields.success", type = "boolean", required = true,
                example = "true"),
            @ApiKeyField(name = "code", descriptionKey = "openPlatform.fields.code", type = "integer", required = true,
                example = "200"),
            @ApiKeyField(name = "message", descriptionKey = "openPlatform.fields.message", type = "string", required = true,
                example = "Success"),
            @ApiKeyField(name = "data.httpStatus", descriptionKey = "openPlatform.fields.httpStatus", type = "integer", required = true,
                example = "200"),
            @ApiKeyField(name = "data.durationMs", descriptionKey = "openPlatform.fields.durationMs", type = "integer", required = true,
                example = "126"),
            @ApiKeyField(name = "data.responseBody", descriptionKey = "openPlatform.fields.responseBody", type = "string", required = true,
                example = "{\"status\":\"ok\"}")
        },
        responseExample = "{\n  \"success\": true,\n  \"code\": 200,\n  \"message\": \"Success\",\n  \"data\": {\n    \"httpStatus\": 200,\n    \"durationMs\": 126,\n    \"responseBody\": \"{\\\"status\\\":\\\"ok\\\"}\"\n  }\n}")
    @TraceType(value = "API_TRIGGER_EXECUTE", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.ExecutionResult trigger(@PathVariable Long id) { return service.execute(id, "MANUAL"); }

    @PostMapping("/test")
    @RequiredPermission("automation:api-trigger:trigger")
    @TraceType(value = "API_TRIGGER_TEST", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.ExecutionResult test(@RequestBody ApiTriggerModels.Command command) { return service.test(command); }

    /** 查询当前接口触发器配置的执行日志，并支持 Trace ID 精确过滤。 */
    @GetMapping("/{id}/logs")
    @RequiredPermission("automation:api-trigger:logs")
    public List<ApiTriggerModels.LogView> logs(@PathVariable Long id,
                                                @RequestParam(required = false) String traceId) {
        return service.logs(id, traceId);
    }
}
