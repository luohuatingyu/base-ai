package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TraceTrackingPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestContextFilterTest {
    private final PlatformProperties properties = new PlatformProperties();
    private final RequestContextFilter filter = new RequestContextFilter(new ObjectMapper(), new TraceTrackingPolicy(properties));
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestContextFilter.class);
    private ListAppender<ILoggingEvent> appender;

    /** 每个测试挂载内存日志采集器。 */
    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** 测试结束后清理日志采集器和 MDC。 */
    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    /** 长文本请求和响应不得被截断，且下游仍可读取完整请求体。 */
    @Test
    void logsCompleteTextBodiesWithoutTruncation() throws Exception {
        String body = "{\"payload\":\"" + "x".repeat(20_000) + "\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test/body");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            assertEquals(body, new String(wrappedRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            wrappedResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            wrappedResponse.getWriter().write(body);
        });

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + right);
        assertTrue(logs.contains("event=http_request_body"));
        assertTrue(logs.contains("event=http_response_body"));
        assertTrue(logs.contains(body));
    }

    /** 图片请求和图片响应只记录头部，不记录二进制正文。 */
    @Test
    void skipsImageBodies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test/image");
        request.setContentType("image/png");
        request.setContent(new byte[] {1, 2, 3, 4});
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            wrappedResponse.setContentType("image/png");
            wrappedResponse.getOutputStream().write(new byte[] {5, 6, 7});
        });

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + right);
        assertTrue(logs.contains("event=http_request_headers"));
        assertTrue(logs.contains("event=http_response_headers"));
        assertFalse(logs.contains("event=http_request_body"));
        assertFalse(logs.contains("event=http_response_body"));
    }

    /** 请求、业务和响应日志按执行顺序输出。 */
    @Test
    void keepsRequestBusinessResponseOrder() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test/order");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            MDC.put("traceId", "trace-order");
            LoggerFactory.getLogger("order-test").info("event=business_middle");
            wrappedResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            wrappedResponse.getWriter().write("{\"ok\":true}");
        });

        var ordered = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.contains("event=http_")).toList();
        assertEquals(4, ordered.size());
        assertTrue(ordered.get(0).contains("event=http_request_headers"));
        assertTrue(ordered.get(1).contains("event=http_request_body"));
        assertTrue(ordered.get(2).contains("event=http_response_headers"));
        assertTrue(ordered.get(3).contains("event=http_response_body"));
    }

    /** 邮件管理接口必须整体排除 HTTP 明细日志，避免密码和收件人泄漏。 */
    @Test
    void excludesMailManagementDetails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mail/accounts");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"password\":\"must-not-be-logged\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) ->
            wrappedResponse.getWriter().write("mail excluded"));

        assertTrue(appender.list.isEmpty());
        assertFalse(response.containsHeader("X-Request-Id"));
        assertEquals("mail excluded", response.getContentAsString());
    }
}
