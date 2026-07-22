package com.baseai.platform.automation;

import com.baseai.platform.trace.TraceType;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
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
    @TraceType(value = "新增接口触发配置", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.View create(@RequestBody ApiTriggerModels.Command command) {
        ApiTriggerModels.View created = service.create(command, AuthContext.require().id());
        scheduler.reschedule(created.id());
        return created;
    }

    @PutMapping("/{id}")
    @RequiredPermission("automation:api-trigger:update")
    @TraceType(value = "更新接口触发配置", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.View update(@PathVariable Long id, @RequestBody ApiTriggerModels.Command command) {
        ApiTriggerModels.View updated = service.update(id, command);
        scheduler.reschedule(id);
        return updated;
    }

    @DeleteMapping("/{id}")
    @RequiredPermission("automation:api-trigger:delete")
    @TraceType(value = "停用接口触发配置", triggerEntry = "MANUAL")
    public void disable(@PathVariable Long id) { service.disable(id); scheduler.cancel(id); }

    @PostMapping("/{id}/void")
    @RequiredPermission("automation:api-trigger:delete")
    @TraceType(value = "作废接口触发配置", triggerEntry = "MANUAL")
    public void voidConfig(@PathVariable Long id) { service.voidConfig(id); scheduler.cancel(id); }

    @PostMapping("/{id}/trigger")
    @RequiredPermission("automation:api-trigger:trigger")
    @TraceType(value = "手动执行接口触发", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.ExecutionResult trigger(@PathVariable Long id) { return service.execute(id, "MANUAL"); }

    @PostMapping("/test")
    @RequiredPermission("automation:api-trigger:trigger")
    @TraceType(value = "临时测试接口调用", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerModels.ExecutionResult test(@RequestBody ApiTriggerModels.Command command) { return service.test(command); }

    @GetMapping("/{id}/logs")
    @RequiredPermission("automation:api-trigger:logs")
    public List<ApiTriggerModels.LogView> logs(@PathVariable Long id) { return service.logs(id); }
}
