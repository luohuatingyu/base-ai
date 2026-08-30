package com.baseai.platform.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalRequestSignerTest {
    private static final String SECRET = "s".repeat(32);
    private static final long NOW = 1_788_000_000L;
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    /** 有效签名必须绑定方法、目标和原始正文字节。 */
    @Test
    void verifiesBoundRequest() {
        byte[] body = "{\"status\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = InternalRequestSigner.headers(SECRET, "POST", "/api/internal/events", body, NOW, NONCE);

        assertTrue(verify(headers, body, NOW));
        assertFalse(verify(headers, "{}".getBytes(StandardCharsets.UTF_8), NOW));
    }

    /** 超出允许时钟窗的签名不得被接受。 */
    @Test
    void rejectsExpiredSignature() {
        byte[] body = new byte[0];
        Map<String, String> headers = InternalRequestSigner.headers(SECRET, "POST", "/api/internal/events", body, NOW, NONCE);

        assertFalse(verify(headers, body, NOW + 61));
    }

    /** 调用统一验证器并固定协议输入。 */
    private boolean verify(Map<String, String> headers, byte[] body, long now) {
        return InternalRequestSigner.verify(SECRET, "POST", "/api/internal/events", body,
            headers.get(InternalRequestSigner.TIMESTAMP), headers.get(InternalRequestSigner.NONCE),
            headers.get(InternalRequestSigner.TARGET), headers.get(InternalRequestSigner.CONTENT_SHA256),
            headers.get(InternalRequestSigner.SIGNATURE), Instant.ofEpochSecond(now), 60);
    }
}
