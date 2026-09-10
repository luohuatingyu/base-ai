package com.baseai.platform.deviceagent;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证设备 Agent HMAC、正文缓存和 Redis Nonce 防重放。 */
class DeviceAgentAuthenticationFilterTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private DeviceAgentAuthenticationFilter filter;
    private ValueOperations<String, String> values;

    /** 模拟独立 Agent Secret 和可用 Redis。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("test");
        DeviceAgentRegistrationService registration = mock(DeviceAgentRegistrationService.class);
        when(registration.secretForAuthentication("ios-agent-test")).thenReturn(SECRET);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        when(ipResolver.resolve(any())).thenReturn("127.0.0.1");
        filter = new DeviceAgentAuthenticationFilter(properties, redis, new ObjectMapper(),
            registration, ipResolver);
    }

    /** 有效签名通过后控制器仍能完整读取正文和可信 Agent 身份。 */
    @Test
    void acceptsValidSignatureAndPreservesBody() throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        byte[] body = "{\"status\":\"ONLINE\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signed(body, "nonce-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> received = new AtomicReference<>();

        filter.doFilter(request, response, (authorized, ignored) -> {
            assertEquals("ios-agent-test", authorized.getAttribute(
                DeviceAgentAuthenticationFilter.AGENT_ID_ATTRIBUTE));
            received.set(new String(authorized.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });

        assertEquals(200, response.getStatus());
        assertEquals(new String(body, StandardCharsets.UTF_8), received.get());
    }

    /** Redis 拒绝重复 Nonce 时请求不得进入控制器。 */
    @Test
    void rejectsReplayedNonce() throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> reached = new AtomicReference<>();

        filter.doFilter(signed("{}".getBytes(StandardCharsets.UTF_8), "nonce-2"), response,
            (_request, _response) -> reached.set("yes"));

        assertEquals(401, response.getStatus());
        assertNull(reached.get());
    }

    /** 创建使用当前时间和通用设备 Agent 头的签名请求。 */
    private MockHttpServletRequest signed(byte[] body, String nonce) throws Exception {
        String path = "/api/agent/ios-device/v1/health";
        long timestamp = Instant.now().getEpochSecond();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = "POST\n" + path + "\n" + hash + "\n" + timestamp + "\n" + nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContent(body);
        request.addHeader("X-Device-Agent-Id", "ios-agent-test");
        request.addHeader("X-Device-Agent-Timestamp", timestamp);
        request.addHeader("X-Device-Agent-Nonce", nonce);
        request.addHeader("X-Device-Agent-Content-SHA256", hash);
        request.addHeader("X-Device-Agent-Signature", signature);
        return request;
    }
}
