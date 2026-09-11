package com.baseai.platform.deployment;

import com.baseai.platform.security.*;
import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 覆盖一次性票据、会话容量、非法输入及身份撤销。 */
class ServerTerminalServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ServerManagementService servers = mock(ServerManagementService.class);
    private final ServerTerminalService service = new ServerTerminalService(servers, mapper, "http://localhost:8091", "test-internal-token");
    private final AuthUser user = new AuthUser(7L, "owner", Set.of("USER"), Set.of("operations:server:shell"), AuthenticationType.TOKEN, null, null);

    /** 每例清除身份及连接。 */
    @AfterEach void cleanup() { AuthContext.clear(); service.destroy(); }

    /** 票据绑定用户、仅消费一次且只返回目标编号。 */
    @Test void bindsTicketToUserAndRejectsReplay() {
        AuthContext.set(user);
        String ticket = service.issue(9L);
        assertEquals(43, ticket.length());
        assertEquals(9L, service.consume(ticket, 7L));
        assertThrows(BusinessException.class, () -> service.consume(ticket, 7L));
        String other = service.issue(9L);
        assertThrows(BusinessException.class, () -> service.consume(other, 8L));
        assertThrows(BusinessException.class, () -> service.consume(other, 7L));
        verify(servers, times(2)).shellTarget(9L);
    }

    /** 过期票据不能再使用。 */
    @Test void rejectsExpiredTicket() throws Exception {
        AuthContext.set(user);
        String ticket = service.issue(9L);
        var tickets = (Map<?, ?>) ReflectionTestUtils.getField(service, "tickets");
        Object issued = tickets.get(ticket);
        var constructor = issued.getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object expired = constructor.newInstance(9L, 7L, java.time.Instant.now().minusSeconds(1));
        @SuppressWarnings("unchecked") Map<String, Object> mutable = (Map<String, Object>) tickets;
        mutable.put(ticket, expired);
        assertThrows(BusinessException.class, () -> service.consume(ticket, 7L));
    }

    /** 单用户待连接票据有上限，未登录不能签发。 */
    @Test void limitsPendingTickets() {
        assertThrows(BusinessException.class, () -> service.issue(9L));
        AuthContext.set(user);
        for (int index = 0; index < 4; index++) service.issue(9L);
        assertThrows(BusinessException.class, () -> service.issue(9L));
    }

    /** 有效输入含空文本、控制字符和窗口边界。 */
    @ParameterizedTest
    @ValueSource(strings={"{\"type\":\"input\",\"data\":\"\"}", "{\"type\":\"input\",\"data\":\"\\u0003\"}",
        "{\"type\":\"resize\",\"cols\":2,\"rows\":1}", "{\"type\":\"resize\",\"cols\":500,\"rows\":200}"})
    void acceptsProtocolBoundaries(String value) throws Exception { assertDoesNotThrow(() -> ServerTerminalService.validateInput(mapper.readTree(value))); }

    /** 非法类型、尺寸及巨大整数不应被静默截断。 */
    @ParameterizedTest
    @ValueSource(strings={"{}", "{\"type\":\"input\",\"data\":1}", "{\"type\":\"execute\"}",
        "{\"type\":\"resize\",\"cols\":0,\"rows\":24}", "{\"type\":\"resize\",\"cols\":80,\"rows\":201}",
        "{\"type\":\"resize\",\"cols\":4294967376,\"rows\":24}", "{\"type\":\"resize\",\"cols\":2.5,\"rows\":24}"})
    void rejectsInvalidProtocol(String value) { assertThrows(IllegalArgumentException.class, () -> ServerTerminalService.validateInput(mapper.readTree(value))); }

    /** 输入大小有明确上限。 */
    @Test void limitsInput() {
        var input = mapper.createObjectNode().put("type", "input").put("data", "a".repeat(16384));
        assertDoesNotThrow(() -> ServerTerminalService.validateInput(input));
        input.put("data", "a".repeat(16385));
        assertThrows(IllegalArgumentException.class, () -> ServerTerminalService.validateInput(input));
    }

    /** 权限或登录被撤销时定时清理会关闭连接。 */
    @Test void closesRevokedSessionAndRejectsUnauthenticatedConnection() throws Exception {
        WebSocketSession anonymous = session("anonymous", null);
        service.afterConnectionEstablished(anonymous);
        verify(anonymous).close(CloseStatus.POLICY_VIOLATION);
        WebSocketSession browser = session("owner", user);
        browser.getAttributes().put("terminalIdentity", (Supplier<AuthUser>) () -> { throw new IllegalStateException(); });
        service.afterConnectionEstablished(browser);
        service.expire();
        verify(browser).close(CloseStatus.POLICY_VIOLATION);
    }

    /** 伪造首帧不得产生 Agent 连接，重复关闭可安全执行。 */
    @Test void rejectsInvalidFirstFrame() throws Exception {
        WebSocketSession browser = session("invalid", user);
        service.afterConnectionEstablished(browser);
        service.handleMessage(browser, new TextMessage("{\"type\":\"input\",\"data\":\"whoami\"}"));
        verify(browser).close(CloseStatus.POLICY_VIOLATION);
        service.afterConnectionClosed(browser, CloseStatus.NORMAL);
        verifyNoInteractions(servers);
    }

    /** 单用户会话上限和未提交票据的连接超时都必须释放资源。 */
    @Test void limitsConnectionsAndExpiresPendingHandshake() throws Exception {
        for (int index = 0; index < 4; index++) service.afterConnectionEstablished(session("session-" + index, user));
        WebSocketSession excess = session("excess", user);
        service.afterConnectionEstablished(excess);
        verify(excess).close(CloseStatus.POLICY_VIOLATION);
        var bridges = (Map<?, ?>) ReflectionTestUtils.getField(service, "bridges");
        Object pending = bridges.get("session-0");
        ReflectionTestUtils.setField(pending, "created", java.time.Instant.now().minusSeconds(11));
        service.expire();
        assertFalse(bridges.containsKey("session-0"));
        assertEquals(3, bridges.size());
        service.afterConnectionEstablished(session("replacement", user));
        assertEquals(4, bridges.size());
    }

    /** 创建仅替代网络边界的浏览器会话。 */
    private WebSocketSession session(String id, AuthUser owner) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        if (owner != null) attributes.put("terminalUser", owner);
        attributes.put("terminalIdentity", (Supplier<AuthUser>) () -> owner);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
