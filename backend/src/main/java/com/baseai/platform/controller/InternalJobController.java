package com.baseai.platform.controller;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.service.TaskJobService;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/internal/jobs")
public class InternalJobController {
    private final TaskJobService service;
    private final PlatformProperties properties;

    public InternalJobController(TaskJobService service, PlatformProperties properties) { this.service = service; this.properties = properties; }

    /** 接收 Worker 子任务状态与心跳事件。 */
    @PostMapping("/python/events")
    public void event(@RequestHeader(value = "X-Internal-Token", required = false) String token, @RequestBody PythonJobEvent event) {
        verify(token);
        service.updatePython(event.pythonJobId(), event.status(), event.workerInstanceId(), event.errorMessage());
    }

    /** 校验 Java 与 Worker 共享内部令牌。 */
    private void verify(String token) {
        String expected = properties.getPythonWorker().getInternalToken();
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.unauthorized("内部令牌无效");
        }
    }

    public record PythonJobEvent(String pythonJobId, String status, String workerInstanceId, String errorMessage) {}
}
