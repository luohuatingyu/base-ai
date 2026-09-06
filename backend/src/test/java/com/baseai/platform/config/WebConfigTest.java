package com.baseai.platform.config;

import com.baseai.platform.security.AuthInterceptor;
import com.baseai.platform.web.HttpRequestTraceInterceptor;
import com.baseai.platform.web.TraceIdInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WebConfigTest {
    /** 空白名单不得注册任何跨域映射。 */
    @Test
    void disablesCorsByDefault() {
        InspectableCorsRegistry registry = registry(List.of());

        assertEquals(Map.of(), registry.configurations());
    }

    /** 只注册去重和规范化后的精确 HTTP 来源。 */
    @Test
    void registersOnlyExactConfiguredOrigins() {
        InspectableCorsRegistry registry = registry(List.of(" HTTPS://Console.Example.com:8443 ",
            "https://console.example.com:8443", "http://localhost:5173"));

        CorsConfiguration configuration = registry.configurations().get("/api/**");
        assertEquals(List.of("https://console.example.com:8443", "http://localhost:5173"),
            configuration.getAllowedOrigins());
        assertEquals(Boolean.FALSE, configuration.getAllowCredentials());
    }

    /** 通配符、路径、查询和非 HTTP 协议均不得成为跨域来源。 */
    @Test
    void rejectsUnsafeOrigins() {
        for (String origin : List.of("*", "https://*.example.com", "https://example.com/path",
            "https://example.com?next=x", "file:///tmp/page.html")) {
            assertThrows(IllegalArgumentException.class, () -> registry(List.of(origin)), origin);
        }
    }

    /** 创建 Web 配置并将注册结果暴露给断言。 */
    private InspectableCorsRegistry registry(List<String> origins) {
        PlatformProperties properties = new PlatformProperties();
        properties.getCors().setAllowedOrigins(origins);
        WebConfig config = new WebConfig(mock(AuthInterceptor.class), mock(TraceIdInterceptor.class),
            mock(HttpRequestTraceInterceptor.class), properties);
        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        config.addCorsMappings(registry);
        return registry;
    }

    /** 仅在测试中公开 Spring 生成的跨域映射。 */
    private static final class InspectableCorsRegistry extends CorsRegistry {
        /** 返回已注册的路径和跨域配置。 */
        private Map<String, CorsConfiguration> configurations() { return getCorsConfigurations(); }
    }
}
