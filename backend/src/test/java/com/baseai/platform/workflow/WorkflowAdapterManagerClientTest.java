package com.baseai.platform.workflow;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.InternalRequestSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowAdapterManagerClientTest {
    private HttpServer server;

    /** 停止测试内部 HTTP 服务，避免遗留监听端口。 */
    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** 客户端必须携带 HMAC、固定来源路径和严格布尔命令。 */
    @Test
    void sendsAuthenticatedFixedSourceControlRequest() throws Exception {
        AtomicBoolean signed = new AtomicBoolean();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/adapters/N8N", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            body.set(new String(requestBody, StandardCharsets.UTF_8));
            signed.set(InternalRequestSigner.verify("m".repeat(24), exchange.getRequestMethod(),
                exchange.getRequestURI().toString(), requestBody,
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.TIMESTAMP),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.NONCE),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.TARGET),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.CONTENT_SHA256),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.SIGNATURE), Instant.now(), 60));
            byte[] response = "{\"source\":\"N8N\",\"status\":\"ENABLING\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        PlatformProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());

        var state = new WorkflowAdapterManagerClient(new ObjectMapper(), properties).setEnabled("n8n", true);

        assertEquals("ENABLING", state.status());
        assertEquals(true, signed.get());
        assertEquals("{\"enabled\":true}", body.get());
    }

    /** 控制服务地址不得包含凭据，避免内部令牌被转发到攻击者选择的身份。 */
    @Test
    void rejectsManagerUrlWithCredentials() {
        PlatformProperties properties = properties("http://user:password@adapter-manager:8090");

        assertThrows(IllegalStateException.class,
            () -> new WorkflowAdapterManagerClient(new ObjectMapper(), properties));
    }

    /** 创建包含强内部令牌的控制客户端配置。 */
    private PlatformProperties properties(String url) {
        PlatformProperties properties = new PlatformProperties();
        properties.getWorkflow().setAdapterManagerUrl(url);
        properties.getWorkflow().setAdapterManagerInternalToken("m".repeat(24));
        return properties;
    }
}
