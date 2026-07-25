package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        assertTrue(generated.rawApiKey().startsWith("bai_live_"));
        assertTrue(service.matches(parsed.secret(), generated.secretHash()));
        assertNotEquals(parsed.secret(), generated.secretHash());
        assertFalse(service.displayPrefix(parsed.keyId()).contains(parsed.secret()));
    }

    /** 非法前缀、缺失 Secret 和超长输入必须统一拒绝。 */
    @Test
    void parseRejectsMalformedKeys() {
        for (String value : new String[]{null, "bad-key", "bai_live_1234", "bai_live_1234567890abcdef.short", "x".repeat(300)}) {
            assertThrows(BusinessException.class, () -> service.parse(value));
        }
    }

    /** 不同 Secret 不得通过恒定时间摘要校验。 */
    @Test
    void matchesRejectsDifferentSecret() {
        ApiKeySecretService.GeneratedApiKey generated = service.generate();
        assertFalse(service.matches("different-secret-value-that-is-long-enough", generated.secretHash()));
    }
}
