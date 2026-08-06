package com.baseai.platform.security;

import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TokenServiceTest {
    private TokenService service;

    /** 使用固定测试密钥初始化令牌服务。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getToken().setSecret("test-secret-key-for-jwt-signing-must-be-long-enough");
        service = new TokenService(properties, new ObjectMapper(), mock(StringRedisTemplate.class));
    }

    /** CSRF Token 必须与完整 JWT 绑定且无法替换使用。 */
    @Test
    void csrfTokenIsSignedAndBoundToJwt() {
        String csrfToken = service.createCsrfToken("jwt-one");

        assertTrue(service.matchesCsrfToken("jwt-one", csrfToken));
        assertFalse(service.matchesCsrfToken("jwt-two", csrfToken));
        assertFalse(service.matchesCsrfToken("jwt-one", csrfToken + "x"));
    }
}
