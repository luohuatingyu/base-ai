package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TraceTrackingPolicy;
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
    private final RequestContextFilter filter = new RequestContextFilter(new TraceTrackingPolicy(properties));
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

    /** 长文本请求和响应不进入日志，且下游仍可读写完整正文。 */
    @Test
    void omitsCompleteTextBodiesWithoutChangingRequestOrResponse() throws Exception {
        String secret = "must-not-enter-http-logs";
        String body = "{\"payload\":\"" + secret + "-" + "x".repeat(20_000) + "\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test/body");
        request.setQueryString("access_token=query-secret");
        request.addHeader("Authorization", "Bearer header-secret");
        request.addHeader("Cookie", "session=cookie-secret");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            assertEquals(body, new String(wrappedRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            wrappedResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ((jakarta.servlet.http.HttpServletResponse) wrappedResponse)
                .setHeader("Set-Cookie", "session=response-cookie-secret");
            wrappedResponse.getWriter().write(body);
        });

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + right);
        assertTrue(logs.contains("event=http_request method=POST path=/api/test/body"));
        assertTrue(logs.contains("event=http_response method=POST path=/api/test/body status=200"));
        assertFalse(logs.contains(secret));
        assertFalse(logs.contains("query-secret"));
        assertFalse(logs.contains("header-secret"));
        assertFalse(logs.contains("cookie-secret"));
        assertFalse(logs.contains("response-cookie-secret"));
        assertEquals(body, response.getContentAsString());
    }

    /** 图片请求和响应同样只记录元信息，不记录二进制正文。 */
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
        assertTrue(logs.contains("event=http_request method=POST path=/api/test/image"));
        assertTrue(logs.contains("event=http_response method=POST path=/api/test/image status=200"));
        assertFalse(logs.contains("request_headers"));
        assertFalse(logs.contains("response_headers"));
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
        assertEquals(2, ordered.size());
        assertTrue(ordered.get(0).contains("event=http_request"));
        assertTrue(ordered.get(1).contains("event=http_response"));
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
