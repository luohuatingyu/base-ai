package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeySecretServiceTest {
    private ApiKeySecretService service;

    /** 使用固定的高强度摘要密钥初始化服务。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getApiKey().setHashSecret("0123456789abcdef0123456789abcdef");
        service = new ApiKeySecretService(properties);
    }

    /** 生成的 API Key 应可解析验证且数据库摘要不包含明文。 */
    @Test
    void generateCreatesVerifiableNonRecoverableSecret() {
        ApiKeySecretService.GeneratedApiKey generated = service.generate();
        ApiKeySecretService.ParsedApiKey parsed = service.parse(generated.rawApiKey());

        assertTrue(generated.rawApiKey().matches("sk-[A-Za-z0-9]{32}"));
        assertEquals(generated.rawApiKey().substring(3, 15), parsed.keyId());
        assertTrue(service.matches(parsed.secret(), generated.secretHash()));
        assertNotEquals(parsed.secret(), generated.secretHash());
        assertEquals("sk-" + parsed.keyId() + "****", service.displayPrefix(parsed.keyId()));
        assertFalse(service.displayPrefix(parsed.keyId()).contains(parsed.secret()));
    }

    /** 非法前缀、长度、字符和历史格式必须统一拒绝。 */
    @ParameterizedTest
    @MethodSource("malformedApiKeys")
    void parseRejectsMalformedKeys(String value) {
        assertThrows(BusinessException.class, () -> service.parse(value));
    }

    /** 不同 Secret 不得通过恒定时间摘要校验。 */
    @Test
    void matchesRejectsDifferentSecret() {
        ApiKeySecretService.GeneratedApiKey generated = service.generate();
        assertFalse(service.matches("different-secret-value-that-is-long-enough", generated.secretHash()));
    }

    /** 提供空值、边界长度、非法字符和历史格式输入。 */
    private static Stream<String> malformedApiKeys() {
        return Stream.of(null, "bad-key", "sk-" + "a".repeat(31), "sk-" + "a".repeat(33),
            "sk-" + "a".repeat(31) + "_", "sk-1234567890abcdef.abcdefghijklmnopqrstuvwxyz123456",
            "bai_live_1234567890abcdef.abcdefghijklmnopqrstuvwxyz123456");
    }
}
