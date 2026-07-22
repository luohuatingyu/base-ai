package com.baseai.platform.controller;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TraceIgnored;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@TraceIgnored
@RestController
@RequestMapping({"/api/open/platform", "/api/open/branding"})
public class PlatformController {
    private final PlatformProperties properties;
    public PlatformController(PlatformProperties properties) { this.properties = properties; }

    /** 返回统一平台配置；保留旧路径以兼容既有调用方。 */
    @GetMapping
    public Map<String, String> platform() {
        PlatformProperties.Platform platform = properties.getPlatform();
        return Map.of("code", platform.getCode(), "nameEn", platform.getNameEn(), "nameZh", platform.getNameZh(), "shortName", platform.getShortName());
    }
}
