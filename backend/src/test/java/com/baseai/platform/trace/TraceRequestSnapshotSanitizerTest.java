package com.baseai.platform.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceRequestSnapshotSanitizerTest {
    /** 认证、连接配置和动态请求体字段必须递归掩码，不依赖固定连字符写法。 */
    @Test
    void masksCredentialAndDynamicConfigurationFields() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader("Authorization", "Bearer hidden");
        request.addHeader("X-Request-Id", "request-1");
        TraceRequestSnapshotSanitizer sanitizer = new TraceRequestSnapshotSanitizer(new ObjectMapper());

        TraceSnapshot snapshot = sanitizer.sanitize(request, new String[]{"command"}, new Object[]{Map.of(
            "api_keys", "provider-secret", "connection-string", "jdbc:mysql://user:password@host/db",
            "nested", Map.of("clientSecret", "oauth-secret"), "name", "safe-name"
        )});

        assertFalse(snapshot.paramsJson().contains("provider-secret"));
        assertFalse(snapshot.paramsJson().contains("oauth-secret"));
        assertFalse(snapshot.paramsJson().contains("jdbc:mysql"));
        assertTrue(snapshot.paramsJson().contains("safe-name"));
        assertFalse(snapshot.headersJson().contains("Bearer hidden"));
        assertTrue(snapshot.headersJson().contains("request-1"));
    }
}
