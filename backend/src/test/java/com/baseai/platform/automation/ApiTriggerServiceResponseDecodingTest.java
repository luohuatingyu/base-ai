package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ApiTriggerServiceResponseDecodingTest {
    private final ApiTriggerService service = new ApiTriggerService(mock(JdbcTemplate.class), new ObjectMapper(),
        mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties());

    /** JSON 未声明字符集时应按 UTF-8 解码中文响应。 */
    @Test
    void decodesJsonWithoutCharsetAsUtf8() {
        byte[] body = "{\"message\":\"操作成功\"}".getBytes(StandardCharsets.UTF_8);

        String decoded = ReflectionTestUtils.invokeMethod(service, "decodeResponseBody", body, MediaType.APPLICATION_JSON);

        assertEquals("{\"message\":\"操作成功\"}", decoded);
    }

    /** 上游显式声明字符集时应按声明解码响应。 */
    @Test
    void decodesResponseUsingDeclaredCharset() {
        byte[] body = "操作成功".getBytes(StandardCharsets.UTF_16LE);
        MediaType contentType = new MediaType("text", "plain", StandardCharsets.UTF_16LE);

        String decoded = ReflectionTestUtils.invokeMethod(service, "decodeResponseBody", body, contentType);

        assertEquals("操作成功", decoded);
    }

    /** 执行日志应在当前配置范围内按精确 Trace ID 查询。 */
    @Test
    void filtersExecutionLogsByExactTraceIdWithinConfiguration() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApiTriggerService service = spy(new ApiTriggerService(jdbcTemplate, new ObjectMapper(),
            mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties()));
        doReturn(mock(ApiTriggerModels.View.class)).when(service).get(9L);
        ApiTriggerModels.LogView matchingLog = mock(ApiTriggerModels.LogView.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(matchingLog));

        List<ApiTriggerModels.LogView> result = service.logs(9L, " trace-1 ");

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertTrue(sql.getValue().contains("config_id=?"));
        assertTrue(sql.getValue().contains("trace_id=?"));
        assertArrayEquals(new Object[]{9L, "trace-1"}, args.getValue());
        assertEquals(List.of(matchingLog), result);
    }

    /** Trace ID 在当前配置中无匹配日志时应返回空列表。 */
    @Test
    void returnsEmptyExecutionLogsWhenTraceIdDoesNotMatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApiTriggerService service = spy(new ApiTriggerService(jdbcTemplate, new ObjectMapper(),
            mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties()));
        doReturn(mock(ApiTriggerModels.View.class)).when(service).get(9L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        List<ApiTriggerModels.LogView> result = service.logs(9L, "missing-trace");

        assertTrue(result.isEmpty());
    }

    /** 未提供 Trace ID 时执行日志应保持原有最近二百条查询行为。 */
    @Test
    void listsRecentExecutionLogsWithoutTraceIdFilter() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApiTriggerService service = spy(new ApiTriggerService(jdbcTemplate, new ObjectMapper(),
            mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties()));
        doReturn(mock(ApiTriggerModels.View.class)).when(service).get(9L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        service.logs(9L, " ");

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertFalse(sql.getValue().contains("trace_id=?"));
        assertArrayEquals(new Object[]{9L}, args.getValue());
    }

    /** 未声明 Content-Length 的超大响应也必须在读取上限处失败。 */
    @Test
    void rejectsResponseBodyOverConfiguredLimit() {
        PlatformProperties properties = new PlatformProperties();
        properties.getApiTrigger().setResponseMaxBytes(4);
        ApiTriggerService limitedService = new ApiTriggerService(mock(JdbcTemplate.class), new ObjectMapper(),
            mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), properties);

        BusinessException exception = assertThrows(BusinessException.class, () ->
            ReflectionTestUtils.invokeMethod(limitedService, "readLimitedBody",
                new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8))));

        assertEquals("apiTrigger.responseTooLarge", exception.getMessageKey());
    }

    /** 请求正文配置超过入口上限时必须在发起网络连接前拒绝。 */
    @Test
    void rejectsOversizedConfiguredRequestBody() {
        PlatformProperties properties = new PlatformProperties();
        properties.getApiTrigger().setRequestBodyMaxBytes(4);
        ApiTriggerUrlPolicy policy = mock(ApiTriggerUrlPolicy.class);
        ApiTriggerService limitedService = new ApiTriggerService(mock(JdbcTemplate.class), new ObjectMapper(),
            mock(ConfigCryptoService.class), policy, properties);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> limitedService.test(command("http://127.0.0.1/unused", "12345")));

        assertEquals("apiTrigger.requestBodyTooLarge", exception.getMessageKey());
        verify(policy, never()).validate(anyString());
    }

    /** GET 请求应跟随同 Host 的常用相对重定向地址。 */
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsSameHostGetRedirect(int redirectStatus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(redirectStatus, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            byte[] body = "followed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";
            ApiTriggerUrlPolicy policy = mock(ApiTriggerUrlPolicy.class);
            when(policy.validate(anyString())).thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
            ApiTriggerService redirectService = new ApiTriggerService(mock(JdbcTemplate.class), new ObjectMapper(),
                mock(ConfigCryptoService.class), policy, new PlatformProperties());

            ApiTriggerModels.ExecutionResult result = redirectService.test(command(url, ""));

            assertEquals(200, result.httpStatus());
            assertEquals("followed", result.responseBody());
            verify(policy, atLeast(2)).validate(anyString());
        } finally {
            server.stop(0);
        }
    }

    /** POST 的 307/308 跳转应保持请求方法和正文。 */
    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void preservesPostMethodAndBodyAcrossPermanentRedirect(int redirectStatus) throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(redirectStatus, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            ApiTriggerUrlPolicy policy = acceptingPolicy();
            ApiTriggerService redirectService = service(policy);

            ApiTriggerModels.ExecutionResult result = redirectService.test(command(url(server, "/redirect"), "payload"));

            assertEquals(204, result.httpStatus());
            assertEquals("POST", receivedMethod.get());
            assertEquals("payload", receivedBody.get());
        } finally {
            server.stop(0);
        }
    }

    /** 非 GET 请求不能跟随会改变方法语义的 301/302/303。 */
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303})
    void rejectsPostRedirectThatCanChangeMethod(int redirectStatus) throws Exception {
        HttpServer server = redirectServer(redirectStatus, "/target");
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service(acceptingPolicy()).test(command(url(server, "/redirect"), "payload")));

            assertEquals("apiTrigger.redirectMethodForbidden", exception.getMessageKey());
        } finally {
            server.stop(0);
        }
    }

    /** 即使新 Host 通过全局白名单，也不能通过重定向转发当前请求凭证。 */
    @Test
    void rejectsCrossHostRedirect() throws Exception {
        HttpServer server = redirectServer(308, "http://localhost/target");
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service(acceptingPolicy()).test(command(url(server, "/redirect"), "payload")));

            assertEquals("apiTrigger.redirectHostForbidden", exception.getMessageKey());
        } finally {
            server.stop(0);
        }
    }

    /** 重定向循环必须在再次发出重复请求前终止。 */
    @Test
    void rejectsRedirectLoop() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/other");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/other", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirect");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service(acceptingPolicy()).test(command(url(server, "/redirect"), "")));

            assertEquals("apiTrigger.redirectLimitExceeded", exception.getMessageKey());
        } finally {
            server.stop(0);
        }
    }

    /** 缺少 Location 的重定向响应必须以稳定错误终止。 */
    @Test
    void rejectsRedirectWithoutLocation() throws Exception {
        HttpServer server = redirectServer(308, "");
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service(acceptingPolicy()).test(command(url(server, "/redirect"), "")));

            assertEquals("apiTrigger.redirectLocationInvalid", exception.getMessageKey());
        } finally {
            server.stop(0);
        }
    }

    /** 语法损坏的 Location 必须映射为稳定错误。 */
    @Test
    void rejectsMalformedRedirectLocation() throws Exception {
        HttpServer server = redirectServer(308, "http://[");
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service(acceptingPolicy()).test(command(url(server, "/redirect"), "")));

            assertEquals("apiTrigger.redirectLocationInvalid", exception.getMessageKey());
        } finally {
            server.stop(0);
        }
    }

    /** 未声明支持的 3xx 状态不能作为可跟随重定向。 */
    @Test
    void rejectsUnsupportedRedirectStatus() throws Exception {
        HttpServer server = redirectServer(305, "/target");
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service(acceptingPolicy()).test(command(url(server, "/redirect"), "")));

            assertEquals("apiTrigger.redirectLocationInvalid", exception.getMessageKey());
        } finally {
            server.stop(0);
        }
    }

    /** 超过五次的非循环重定向必须停止，避免远端消耗线程和连接资源。 */
    @Test
    void rejectsRedirectChainOverLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int index = 0; index <= 5; index++) {
            int current = index;
            server.createContext("/step-" + index, exchange -> {
                exchange.getResponseHeaders().add("Location", "/step-" + (current + 1));
                exchange.sendResponseHeaders(307, -1);
                exchange.close();
            });
        }
        server.start();
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service(acceptingPolicy()).test(command(url(server, "/step-0"), "")));

            assertEquals("apiTrigger.redirectLimitExceeded", exception.getMessageKey());
        } finally {
            server.stop(0);
        }
    }

    /** 恰好五次重定向仍应成功，验证跳转上限边界。 */
    @Test
    void followsRedirectChainAtLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int index = 0; index < 5; index++) {
            int current = index;
            server.createContext("/step-" + index, exchange -> {
                exchange.getResponseHeaders().add("Location", "/step-" + (current + 1));
                exchange.sendResponseHeaders(307, -1);
                exchange.close();
            });
        }
        server.createContext("/step-5", exchange -> {
            byte[] body = "done".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiTriggerModels.ExecutionResult result = service(acceptingPolicy())
                .test(command(url(server, "/step-0"), ""));

            assertEquals(200, result.httpStatus());
            assertEquals("done", result.responseBody());
        } finally {
            server.stop(0);
        }
    }

    /** HTTPS 请求不能降级跳转到明文 HTTP。 */
    @Test
    void rejectsHttpsDowngradeRedirect() throws Exception {
        ApiTriggerUrlPolicy policy = acceptingPolicy();
        ApiTriggerService redirectService = service(policy);
        Class<?> requestType = Class.forName(ApiTriggerService.class.getName() + "$OutboundRequest");
        var constructor = requestType.getDeclaredConstructor(String.class, URI.class, Map.class, String.class, String.class);
        constructor.setAccessible(true);
        Object request = constructor.newInstance("GET", URI.create("https://example.test/source"), Map.of(), null,
            "application/json");

        BusinessException exception = assertThrows(BusinessException.class, () ->
            ReflectionTestUtils.invokeMethod(redirectService, "redirectedRequest", request, 308,
                "http://example.test/target"));

        assertEquals("apiTrigger.redirectDowngradeForbidden", exception.getMessageKey());
    }

    /** 创建已启动的单跳测试服务。 */
    private static HttpServer redirectServer(int status, String location) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            if (!location.isBlank()) exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 构造接受所有测试地址且仍记录每一跳校验的 URL 策略。 */
    private static ApiTriggerUrlPolicy acceptingPolicy() {
        ApiTriggerUrlPolicy policy = mock(ApiTriggerUrlPolicy.class);
        when(policy.validate(anyString())).thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        return policy;
    }

    /** 构造独立的接口触发服务。 */
    private static ApiTriggerService service(ApiTriggerUrlPolicy policy) {
        return new ApiTriggerService(mock(JdbcTemplate.class), new ObjectMapper(), mock(ConfigCryptoService.class),
            policy, new PlatformProperties());
    }

    /** 返回指定本地服务的绝对地址。 */
    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** 创建最小可执行的临时触发命令。 */
    private static ApiTriggerModels.Command command(String url, String body) {
        return new ApiTriggerModels.Command("test", null, body.isEmpty() ? "GET" : "POST", url, null, null, body,
            "application/json", null, 5, true, false, null, null, null, null, null, null, null);
    }
}
