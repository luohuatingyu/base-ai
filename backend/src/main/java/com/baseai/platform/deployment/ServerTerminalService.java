package com.baseai.platform.deployment;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;
import java.security.SecureRandom;
import java.util.function.Supplier;

/** 管理一次性票据与有界终端桥接，终端数据不写入审计日志。 */
@Service
public class ServerTerminalService extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ServerTerminalService.class);
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    private final ServerManagementService servers;
    private final ObjectMapper mapper;
    private final URI agent;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();

    /** 注入已有凭据解析服务及内部 Agent 地址。 */
    public ServerTerminalService(ServerManagementService servers, ObjectMapper mapper,
        @Value("${app.deployment-agent.url:}") String agentUrl,
        @Value("${app.deployment-agent.internal-token:}") String token) {
        this.servers = servers;
        this.mapper = mapper;
        this.agent = agentUrl.isBlank() ? null : URI.create(agentUrl.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/terminal");
        this.token = token;
    }

    /** 校验目标后签发有效期三十秒的随机票据，每用户最多四个待连接票据。 */
    public synchronized String issue(Long serverId) {
        if (agent == null || token.isBlank()) throw new BusinessException("server.agentNotConfigured");
        servers.shellTarget(serverId);
        Long userId = AuthContext.require().id();
        tickets.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
        if (tickets.size() >= 128 || tickets.values().stream().filter(ticket -> ticket.userId().equals(userId)).count() >= 4) {
            throw new BusinessException("server.running");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(value, new Ticket(serverId, userId, Instant.now().plusSeconds(30)));
        return value;
    }

    /** 原子消费票据，同时绑定握手登录用户，拒绝过期和重放。 */
    synchronized Long consume(String value, Long userId) {
        Ticket ticket = tickets.remove(value);
        if (ticket == null || ticket.expires().isBefore(Instant.now()) || !ticket.userId().equals(userId)) {
            throw BusinessException.forbidden("auth.permissionDenied");
        }
        return ticket.serverId();
    }

    /** 为已认证握手分配容量；第一条消息必须在十秒内提交票据。 */
    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AuthUser user = (AuthUser) session.getAttributes().get("terminalUser");
        if (user == null || bridges.size() >= 32 || bridges.values().stream().filter(bridge -> bridge.user.id().equals(user.id())).count() >= 4) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.setTextMessageSizeLimit(32768);
        session.setBinaryMessageSizeLimit(32768);
        bridges.put(session.getId(), new Bridge(new ConcurrentWebSocketSessionDecorator(session, 10000, 262144), user));
    }

    /** 第一帧消费票据并连接 Agent，之后只接受输入和尺寸协议。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Bridge bridge = bridges.get(session.getId());
        if (bridge == null) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        try {
            JsonNode input = mapper.readTree(message.getPayload());
            if (input == null || !input.isObject()) throw new IllegalArgumentException();
            if (bridge.upstream == null) {
                if (!"connect".equals(input.path("type").asText())) throw new IllegalArgumentException();
                Long serverId = consume(input.path("ticket").asText(), bridge.user.id());
                AuthContext.set(bridge.user);
                String config;
                try { config = mapper.writeValueAsString(servers.shellTarget(serverId)); }
                finally { AuthContext.clear(); }
                WebSocket upstream = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .header("X-Internal-Token", token).buildAsync(agent, bridge).get(12, TimeUnit.SECONDS);
                bridge.upstream = upstream;
                if (!bridge.browser.isOpen() || !bridges.containsKey(session.getId())) { upstream.abort(); return; }
                upstream.sendText(config, true).get(10, TimeUnit.SECONDS);
                bridge.serverId = serverId;
                bridge.browser.sendMessage(new TextMessage("{\"type\":\"ready\"}"));
                log.info("SSH terminal opened userId={} serverId={}", bridge.user.id(), serverId);
            } else {
                validateInput(input);
                bridge.upstream.sendText(message.getPayload(), true).get(10, TimeUnit.SECONDS);
            }
            bridge.lastInput = Instant.now();
        } catch (Exception exception) {
            close(bridge, CloseStatus.POLICY_VIOLATION);
        }
    }

    /** 只允许有界输入和明确尺寸，不接受任意 Agent 指令。 */
    static void validateInput(JsonNode input) {
        String type = input.path("type").asText();
        if ("input".equals(type) && input.path("data").isTextual() && input.path("data").asText().length() <= 16384) return;
        if ("resize".equals(type) && input.path("cols").isIntegralNumber() && input.path("rows").isIntegralNumber()
            && input.path("cols").canConvertToInt() && input.path("rows").canConvertToInt()
            && input.path("cols").asInt() >= 2 && input.path("cols").asInt() <= 500
            && input.path("rows").asInt() >= 1 && input.path("rows").asInt() <= 200) return;
        throw new IllegalArgumentException("Invalid terminal message");
    }

    /** 浏览器关闭后立即终止上游，使 Agent 回收进程及凭据。 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Bridge bridge = bridges.get(session.getId());
        if (bridge != null) close(bridge, status);
    }

    /** 传输错误也必须释放会话。 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        Bridge bridge = bridges.get(session.getId());
        if (bridge != null) close(bridge, CloseStatus.SERVER_ERROR);
    }

    /** 每五秒清理未认证、空闲和到达最长时限的会话。 */
    @Scheduled(fixedDelay = 5000)
    public void expire() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(now));
        bridges.values().forEach(bridge -> {
            try {
                AuthUser current = bridge.identity.get();
                if (!current.id().equals(bridge.user.id())) throw new IllegalStateException();
                if (bridge.serverId != null) {
                    AuthContext.set(current);
                    try { servers.shellTarget(bridge.serverId); } finally { AuthContext.clear(); }
                }
            } catch (Exception exception) { close(bridge, CloseStatus.POLICY_VIOLATION); return; }
            if (bridge.created.plusSeconds(1800).isBefore(now) || bridge.lastInput.plusSeconds(300).isBefore(now)
                || (bridge.upstream == null && bridge.created.plusSeconds(10).isBefore(now))) close(bridge, CloseStatus.SESSION_NOT_RELIABLE);
        });
    }

    /** 应用停止时终止所有会话。 */
    @PreDestroy
    public void destroy() { bridges.values().forEach(bridge -> close(bridge, CloseStatus.GOING_AWAY)); tickets.clear(); }

    /** 幂等释放两端连接并仅记录会话元数据。 */
    private void close(Bridge bridge, CloseStatus status) {
        boolean removed = bridges.remove(bridge.browser.getId(), bridge);
        if (bridge.upstream != null) bridge.upstream.abort();
        try { bridge.browser.close(status); } catch (Exception ignored) { }
        if (removed) log.info("SSH terminal closed userId={} serverId={}", bridge.user.id(), bridge.serverId);
    }

    private record Ticket(Long serverId, Long userId, Instant expires) { }

    /** 逐帧转发 Agent 输出，客户端慢时通过写入时限和缓冲上限断开。 */
    private final class Bridge implements WebSocket.Listener {
        private final WebSocketSession browser;
        private final AuthUser user;
        private final Supplier<AuthUser> identity;
        private final Instant created = Instant.now();
        private volatile Instant lastInput = created;
        private volatile WebSocket upstream;
        private volatile Long serverId;

        /** 绑定浏览器会话和用户，不保存 SSH 凭据。 */
        @SuppressWarnings("unchecked")
        private Bridge(WebSocketSession browser, AuthUser user) {
            this.browser = browser; this.user = user;
            this.identity = (Supplier<AuthUser>) browser.getAttributes().get("terminalIdentity");
        }

        /** 请求一帧，避免无界读取。 */
        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        /** 二进制流由终端按 UTF-8 连续解码，支持跨帧字符。 */
        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            try { browser.sendMessage(new BinaryMessage(data)); socket.request(1); }
            catch (Exception exception) { close(this, CloseStatus.SERVER_ERROR); }
            return CompletableFuture.completedFuture(null);
        }

        /** Agent 文本错误仅触发关闭，不泄露内部诊断或连接信息。 */
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            close(this, CloseStatus.SERVER_ERROR);
            return CompletableFuture.completedFuture(null);
        }

        /** 远端退出时同步关闭浏览器。 */
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            close(this, CloseStatus.NORMAL);
            return CompletableFuture.completedFuture(null);
        }

        /** 网络异常时同步回收双方资源。 */
        @Override public void onError(WebSocket socket, Throwable error) { close(this, CloseStatus.SERVER_ERROR); }
    }
}
