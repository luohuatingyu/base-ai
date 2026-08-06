package com.baseai.platform.controller;

import com.baseai.platform.service.HealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/open")
public class HealthController {
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /** 仅确认 Java 进程仍可处理 HTTP 请求。 */
    @GetMapping("/health/live")
    public Map<String, String> live() { return Map.of("status", "UP"); }

    /** 关键依赖全部可用时返回 200，否则返回不泄露细节的 503。 */
    @GetMapping({"/health", "/health/ready"})
    public ResponseEntity<Map<String, String>> ready() {
        boolean ready = healthService.isReady();
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "UP" : "DOWN"));
    }
}
