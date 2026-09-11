package com.baseai.platform.deployment;

import com.baseai.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.http.server.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 Tomcat 与 WebSocket 客户端验证双向桥接和关闭传播。 */
class ServerTerminalBridgeTest {
    private static final AuthUser USER = new AuthUser(7L, "owner", Set.of("USER"), Set.of("operations:server:shell"), AuthenticationType.TOKEN, null, null);

    /** 真实连接传输票据、凭据与交互字节，浏览器关闭后 Agent 连接必须关闭。 */
    @Test void relaysTerminalAndClosesUpstream() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext(TestConfig.class)) {
            var service = context.getBean(ServerTerminalService.class);
            var agent = context.getBean(AgentEndpoint.class);
            int port = context.getWebServer().getPort();
            ReflectionTestUtils.setField(service, "agent", URI.create("ws://localhost:" + port + "/agent"));
            AuthContext.set(USER);
            String ticket;
            try { ticket = service.issue(9L); } finally { AuthContext.clear(); }
            BlockingQueue<String> output = new LinkedBlockingQueue<>();
            WebSocket browser = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/terminal"), new WebSocket.Listener() {
                    /** 请求首帧。 */
                    @Override public void onOpen(WebSocket socket) { socket.request(1); }
                    /** 收集状态消息。 */
                    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                        output.add(data.toString()); socket.request(1); return CompletableFuture.completedFuture(null);
                    }
                    /** 验证二进制内容连续转发。 */
                    @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
                        output.add(java.nio.charset.StandardCharsets.UTF_8.decode(data).toString()); socket.request(1); return CompletableFuture.completedFuture(null);
                    }
                }).get(10, TimeUnit.SECONDS);
            try {
                browser.sendText("{\"type\":\"connect\",\"ticket\":\"" + ticket + "\"}", true).get(5, TimeUnit.SECONDS);
                assertEquals("{\"type\":\"ready\"}", output.poll(10, TimeUnit.SECONDS));
                assertTrue(agent.messages.poll(5, TimeUnit.SECONDS).contains("secret"));
                browser.sendText("{\"type\":\"input\",\"data\":\"pwd\\r\"}", true).get(5, TimeUnit.SECONDS);
                assertTrue(agent.messages.poll(5, TimeUnit.SECONDS).contains("pwd"));
                assertEquals("/tmp\r\n", output.poll(5, TimeUnit.SECONDS));
                browser.sendClose(1000, "").get(5, TimeUnit.SECONDS);
                assertTrue(agent.closed.await(5, TimeUnit.SECONDS));
            } finally { browser.abort(); }
        }
    }

    /** 测试仅替代外部 Agent 与服务器数据库，桥接本身使用正式实现。 */
    @Configuration
    @EnableWebSocket
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class TestConfig implements WebSocketConfigurer {
        /** 启动随机端口 Tomcat。 */
        @Bean TomcatServletWebServerFactory serverFactory() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.addContextCustomizers(context -> context.addServletContainerInitializer(new org.apache.tomcat.websocket.server.WsSci(), null));
            return factory;
        }
        /** 显式注册 MVC Servlet，使测试容器具有真实路由和升级能力。 */
        @Bean org.springframework.boot.web.servlet.ServletRegistrationBean<org.springframework.web.servlet.DispatcherServlet> dispatcher(
            org.springframework.web.context.WebApplicationContext context) {
            return new org.springframework.boot.web.servlet.ServletRegistrationBean<>(new org.springframework.web.servlet.DispatcherServlet(context), "/");
        }
        /** 提供测试外部 Agent。 */
        @Bean AgentEndpoint agent() { return new AgentEndpoint(); }
        /** 保留真实会话服务，仅隔离服务器配置读取。 */
        @Bean ServerTerminalService terminals() {
            ObjectMapper mapper = new ObjectMapper();
            ServerManagementService servers = mock(ServerManagementService.class);
            when(servers.shellTarget(9L)).thenReturn(mapper.createObjectNode().put("mode", "SSH").put("password", "secret"));
            return new ServerTerminalService(servers, mapper, "http://localhost:1", "internal-test-token");
        }
        /** 为桥接测试注入已认证身份，来源和权限另有正式握手测试。 */
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(agent(), "/agent");
            registry.addHandler(terminals(), "/terminal").addInterceptors(new HandshakeInterceptor() {
                /** 注入已通过身份校验的用户。 */
                @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
                    attributes.put("terminalUser", USER);
                    attributes.put("terminalIdentity", (Supplier<AuthUser>) () -> USER);
                    return true;
                }
                /** 测试握手无需额外清理。 */
                @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) { }
            });
        }
    }

    /** 外部 Agent 边界接收配置并返回固定终端字节。 */
    static class AgentEndpoint extends AbstractWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        /** 校验内部认证头并对输入返回终端输出。 */
        @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            assertEquals("internal-test-token", session.getHandshakeHeaders().getFirst("X-Internal-Token"));
            messages.add(message.getPayload());
            if (message.getPayload().contains("pwd")) session.sendMessage(new BinaryMessage("/tmp\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        /** 检测关闭传播。 */
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { closed.countDown(); }
    }
}
