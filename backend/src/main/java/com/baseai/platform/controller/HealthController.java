package com.baseai.platform.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/open")
public class HealthController {
    /** 提供容器级存活检查。 */
    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }
}
