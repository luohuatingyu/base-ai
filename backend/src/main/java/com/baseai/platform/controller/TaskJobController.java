package com.baseai.platform.controller;

import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.TaskJobService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/tasks")
@RequiredPermission("system:task:view")
public class TaskJobController {
    private final TaskJobService service;
    public TaskJobController(TaskJobService service) { this.service = service; }

    /** 查询当前用户可见任务。 */
    @GetMapping
    public List<Map<String, Object>> jobs() {
        AuthUser user = AuthContext.require();
        return service.jobs(user.id(), user.roles().contains("ADMIN"));
    }

    /** 查询指定任务的跨服务日志。 */
    @GetMapping("/{jobId}/logs")
    public List<Map<String, Object>> logs(@PathVariable String jobId) { return service.logs(jobId); }
}
