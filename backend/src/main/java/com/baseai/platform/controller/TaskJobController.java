package com.baseai.platform.controller;

import com.baseai.platform.job.JobIgnored;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.TaskJobService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@JobIgnored
@RestController
@RequestMapping("/api/system/tasks")
@RequiredPermission("system:task:view")
public class TaskJobController {
    private final TaskJobService service;
    public TaskJobController(TaskJobService service) { this.service = service; }

    /** 为长耗时前端请求预留任务编号。 */
    @PostMapping("/reservations")
    public Map<String, String> reserve() { return Map.of("jobId", service.reserve(AuthContext.require().id())); }

    /** 按条件查询当前用户可见任务。 */
    @GetMapping
    public List<Map<String, Object>> jobs(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String taskType,
                                          @RequestParam(required = false) String triggerEntry) {
        AuthUser user = AuthContext.require();
        return service.jobs(user.id(), isAdmin(user), status, taskType, triggerEntry);
    }

    @GetMapping("/running")
    public List<Map<String, Object>> running() {
        AuthUser user = AuthContext.require();
        return service.running(user.id(), isAdmin(user));
    }

    @GetMapping("/task-types") public List<String> taskTypes() { return service.taskTypes(); }
    @GetMapping("/trigger-entries") public List<String> triggerEntries() { return service.triggerEntries(); }
    @GetMapping("/metadata") public List<com.baseai.platform.job.TaskTypeRegistry.Metadata> metadata() { return service.taskMetadata(); }

    @GetMapping("/{jobId}")
    public Map<String, Object> get(@PathVariable String jobId) {
        AuthUser user = AuthContext.require();
        return service.get(jobId, user.id(), isAdmin(user));
    }

    @GetMapping("/{jobId}/logs")
    public List<Map<String, Object>> logs(@PathVariable String jobId) {
        AuthUser user = AuthContext.require();
        return service.logs(jobId, user.id(), isAdmin(user));
    }

    /** 请求取消本人任务，管理员可取消任意任务。 */
    @PostMapping("/{jobId}/cancel")
    @RequiredPermission("system:task:manage")
    public Map<String, Object> cancel(@PathVariable String jobId, @RequestBody(required = false) CancelRequest request) {
        AuthUser user = AuthContext.require();
        return service.cancel(jobId, user.id(), isAdmin(user), request == null ? null : request.reason());
    }

    /** 仅管理员可强制中断任务。 */
    @PostMapping("/{jobId}/force-terminate")
    @RequiredPermission("system:task:manage")
    public Map<String, Object> forceTerminate(@PathVariable String jobId, @RequestBody(required = false) CancelRequest request) {
        AuthUser user = AuthContext.require();
        return service.forceTerminate(jobId, user.id(), isAdmin(user), request == null ? null : request.reason());
    }

    private boolean isAdmin(AuthUser user) { return user.roles().contains("ADMIN"); }
    public record CancelRequest(String reason) {}
}
