package com.baseai.platform.controller;

import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformControllerTest {

    /** 验证平台接口返回平台配置，并同时保留旧公开路径。 */
    @Test
    void returnsPlatformConfigurationAndKeepsLegacyPath() {
        PlatformProperties properties = new PlatformProperties();
        PlatformProperties.Platform platform = new PlatformProperties.Platform();
        platform.setCode("test-platform");
        platform.setNameEn("Test Platform");
        platform.setNameZh("测试平台");
        platform.setShortName("TEST");
        properties.setPlatform(platform);

        PlatformController controller = new PlatformController(properties);
        Map<String, String> response = controller.platform();
        RequestMapping mapping = PlatformController.class.getAnnotation(RequestMapping.class);

        assertEquals(Map.of("code", "test-platform", "nameEn", "Test Platform", "nameZh", "测试平台", "shortName", "TEST"), response);
        assertArrayEquals(new String[]{"/api/open/platform", "/api/open/branding"}, mapping.value());
    }
}
