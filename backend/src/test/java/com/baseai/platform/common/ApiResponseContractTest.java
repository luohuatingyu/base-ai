package com.baseai.platform.common;

import com.baseai.platform.config.I18nConfig;
import com.baseai.platform.trace.TraceCancelledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiResponseContractTest {
    private final ResourceBundleMessageSource messageSource = messageSource();
    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(messageSource);

    /** 每个测试结束后清理线程语言，避免污染其他测试。 */
    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
        MDC.clear();
    }

    /** 成功响应应使用数字 200，并根据当前请求语言返回消息。 */
    @Test
    void successResponseUsesNumericCodeAndCurrentLocale() {
        LocaleContextHolder.setLocale(Locale.US);
        MDC.put("traceId", "backend-trace-1");
        ApiResponseAdvice advice = new ApiResponseAdvice(messageSource);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/api/system/users"));

        Object result = advice.beforeBodyWrite(Map.of("id", 1), null, null, null, request, null);

        ApiResponse<?> response = (ApiResponse<?>) result;
        assertTrue(response.success());
        assertEquals(200, response.code());
        assertEquals("Operation successful", response.message());
        assertEquals(Map.of("id", 1), response.data());
        assertEquals("backend-trace-1", response.traceId());
    }

    /** 公开和内部协议应继续保持原始响应结构。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/api/open/health", "/api/internal/traces/python/events"})
    void openAndInternalResponsesRemainUnwrapped(String path) {
        ApiResponseAdvice advice = new ApiResponseAdvice(messageSource);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, String> body = Map.of("status", "UP");
        when(request.getURI()).thenReturn(URI.create("http://localhost" + path));

        Object result = advice.beforeBodyWrite(body, null, null, null, request, null);

        assertEquals(body, result);
    }

    /** 客户端未声明语言时应采用已确认的英文默认语言。 */
    @Test
    void localeResolverDefaultsToEnglish() {
        Locale locale = new I18nConfig(new com.baseai.platform.config.PlatformProperties())
            .localeResolver().resolveLocale(new MockHttpServletRequest());

        assertEquals(Locale.US, locale);
    }

    /** 业务异常响应体 code 必须与实际 HTTP 状态一致。 */
    @ParameterizedTest
    @MethodSource("businessErrors")
    void businessErrorCodeMatchesHttpStatus(int status, String key, String expectedMessage) {
        LocaleContextHolder.setLocale(Locale.US);
        MDC.put("traceId", "backend-trace-error");

        ResponseEntity<ApiResponse<Void>> entity = exceptionHandler.business(new BusinessException(status, key));

        assertEquals(status, entity.getStatusCode().value());
        assertEquals(status, entity.getBody().code());
        assertFalse(entity.getBody().success());
        assertEquals(expectedMessage, entity.getBody().message());
        assertNull(entity.getBody().data());
        assertEquals("backend-trace-error", entity.getBody().traceId());
    }

    /** 同一业务异常应按照中文语言状态返回中文消息。 */
    @Test
    void businessErrorUsesChineseLocale() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

        ResponseEntity<ApiResponse<Void>> entity = exceptionHandler.business(
            BusinessException.unauthorized("auth.required"));

        assertEquals(401, entity.getBody().code());
        assertEquals("请先登录", entity.getBody().message());
    }

    /** 框架解析失败消息也应按当前语言返回且使用数字 400。 */
    @Test
    void frameworkErrorsUseCurrentLocale() {
        LocaleContextHolder.setLocale(Locale.US);
        ResponseEntity<ApiResponse<Void>> english = exceptionHandler.unreadable(null);
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        ResponseEntity<ApiResponse<Void>> chinese = exceptionHandler.missing(null);

        assertEquals(400, english.getBody().code());
        assertEquals("Request body is not valid JSON", english.getBody().message());
        assertEquals(400, chinese.getBody().code());
        assertEquals("请求参数不完整", chinese.getBody().message());
    }

    /** 冲突和未知异常也应返回匹配的数字状态码及本地化消息。 */
    @Test
    void conflictAndUnknownErrorsUseNumericCodes() {
        LocaleContextHolder.setLocale(Locale.US);

        ResponseEntity<ApiResponse<Void>> conflict = exceptionHandler.cancelled(new TraceCancelledException("trace-1"));
        ResponseEntity<ApiResponse<Void>> unknown = exceptionHandler.unknown(new IllegalStateException("sensitive"));

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(409, conflict.getBody().code());
        assertEquals("Task cancelled: trace-1", conflict.getBody().message());
        assertEquals(500, unknown.getStatusCode().value());
        assertEquals(500, unknown.getBody().code());
        assertEquals("Internal server error", unknown.getBody().message());
    }

    /** 提供需验证的正常、权限、资源、限流和下游异常状态。 */
    private static Stream<Arguments> businessErrors() {
        return Stream.of(
            Arguments.of(400, "error.validation", "Request validation failed"),
            Arguments.of(401, "auth.required", "Authentication is required"),
            Arguments.of(403, "auth.permissionDenied", "You do not have permission to perform this operation"),
            Arguments.of(404, "trace.notFound", "Task not found"),
            Arguments.of(429, "apiKey.rateLimitExceeded", "API Key request rate limit exceeded"),
            Arguments.of(502, "ai.serviceCallFailed", "Failed to call the model service"),
            Arguments.of(503, "apiKey.rateLimitUnavailable", "The API Key rate limiting service is temporarily unavailable")
        );
    }

    /** 创建与生产配置一致且禁用系统语言回退的消息源。 */
    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
