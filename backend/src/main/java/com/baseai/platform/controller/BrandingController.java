package com.baseai.platform.controller;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TraceIgnored;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@TraceIgnored
@RestController
@RequestMapping("/api/open/branding")
public class BrandingController {
    private final PlatformProperties properties;
    public BrandingController(PlatformProperties properties) { this.properties = properties; }

    /** 返回统一品牌配置，供外部管理端或运维页面读取。 */
    @GetMapping
    public Map<String, String> branding() {
        PlatformProperties.Brand brand = properties.getBrand();
        return Map.of("code", brand.getCode(), "nameEn", brand.getNameEn(), "nameZh", brand.getNameZh(), "shortName", brand.getShortName());
    }
}
