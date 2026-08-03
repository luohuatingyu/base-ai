package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MailDeliveryClientTest {
    private MockRestServiceServer server;
    private MailDeliveryClient client;

    /** 创建由模拟 Worker 驱动的真实 RestClient。 */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://worker");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MailDeliveryClient(builder.build());
    }

    /** Worker 请求必须包含完整路由且成功确认应转换为普通结果。 */
    @Test
    void sendsResolvedRouteToWorker() {
        server.expect(requestTo("http://worker/email/send"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                {"smtp":{"host":"smtp.example.com","port":587,"username":"sender@example.com",
                "fromAddress":"sender@example.com","tlsMode":"STARTTLS","password":"secret"},
                "toAddresses":["to@example.com"],"ccAddresses":["cc@example.com"],
                "subject":"Subject","body":"Body"}
                """))
            .andRespond(withSuccess("{\"sent\":true}", MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.send(route(), "Subject", "Body");

        assertThat(result).containsEntry("sent", true);
        server.verify();
    }

    /** Worker 空响应必须转换为稳定网关错误。 */
    @Test
    void rejectsEmptyWorkerResponse() {
        server.expect(requestTo("http://worker/email/send")).andRespond(withSuccess());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> client.send(route(), "Subject", "Body"));

        assertThat(exception.getStatus()).isEqualTo(502);
        assertThat(exception.getMessageKey()).isEqualTo("mail.workerEmptyResponse");
    }

    /** Worker HTTP 错误不得向上层泄露响应细节。 */
    @Test
    void hidesWorkerResponseFailure() {
        server.expect(requestTo("http://worker/email/send"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("secret provider detail"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> client.send(route(), "Subject", "Body"));

        assertThat(exception.getMessageKey()).isEqualTo("mail.sendFailed");
        assertThat(exception.getMessage()).doesNotContain("secret provider detail");
    }

    /** Worker 网络异常必须转换为稳定不可用错误。 */
    @Test
    void mapsWorkerNetworkFailure() {
        server.expect(requestTo("http://worker/email/send"))
            .andRespond(request -> { throw new ResourceAccessException("secret network detail"); });

        BusinessException exception = assertThrows(BusinessException.class,
            () -> client.send(route(), "Subject", "Body"));

        assertThat(exception.getMessageKey()).isEqualTo("mail.workerUnavailable");
        assertThat(exception.getMessage()).doesNotContain("secret network detail");
    }

    /** 创建不包含真实凭证的内部路由。 */
    private MailManagementService.ResolvedRoute route() {
        return new MailManagementService.ResolvedRoute("ORDER_FAILURE", "smtp.example.com", 587,
            "sender@example.com", "sender@example.com", "STARTTLS", "secret",
            List.of("to@example.com"), List.of("cc@example.com"));
    }
}
