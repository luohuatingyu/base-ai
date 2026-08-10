package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerModels;
import com.baseai.platform.automation.ApiTriggerService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.service.MailDeliveryClient;
import com.baseai.platform.service.MailManagementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowConnectorNodeExecutorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowConnectionService connections;
    private MailManagementService mailManagementService;
    private MailDeliveryClient mailDeliveryClient;
    private ApiTriggerService apiTriggerService;
    private WorkflowConnectorNodeExecutor executor;
    private ObjectNode context;

    /** 为每个连接节点场景创建隔离依赖和表达式上下文。 */
    @BeforeEach
    void setUp() {
        connections = mock(WorkflowConnectionService.class);
        mailManagementService = mock(MailManagementService.class);
        mailDeliveryClient = mock(MailDeliveryClient.class);
        apiTriggerService = mock(ApiTriggerService.class);
        executor = new WorkflowConnectorNodeExecutor(objectMapper, new WorkflowExpressionService(objectMapper), connections,
            mailManagementService, mailDeliveryClient, apiTriggerService, new PlatformProperties());
        context = objectMapper.createObjectNode();
        context.set("input", objectMapper.createObjectNode());
        context.set("nodes", objectMapper.createObjectNode());
        context.set("loop", objectMapper.createObjectNode());
    }

    /** SQL 节点必须通过预编译参数执行只读查询并返回真实业务结果。 */
    @Test
    void executesParameterizedReadOnlySql() throws Exception {
        String url = "jdbc:h2:mem:workflow-connector-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        stored("MYSQL", objectMapper.readTree("""
            {"url":"%s","username":"sa","password":"","allowWrite":false}
            """.formatted(url)));

        JsonNode output = execute("SQL_QUERY", objectMapper.readTree("""
            {"connectionId":1,"query":"SELECT ? AS name","parameters":["Ada"],"maxRows":10}
            """)).output();

        assertEquals(1, output.path("count").asInt());
        assertEquals("Ada", output.path("rows").get(0).path("NAME").asText());
    }

    /** 未授权连接不得执行 SQL 写入。 */
    @Test
    void rejectsSqlWriteWithoutConnectionPermission() throws Exception {
        stored("MYSQL", objectMapper.readTree("""
            {"url":"jdbc:h2:mem:unused","username":"sa","password":"","allowWrite":false}
            """));
        BusinessException exception = assertThrows(BusinessException.class, () -> execute("SQL_QUERY", objectMapper.readTree("""
            {"connectionId":1,"query":"UPDATE orders SET status=?","parameters":["DONE"]}
            """)));
        assertEquals("workflow.sqlWriteForbidden", exception.getMessageKey());
    }

    /** 只读连接必须拒绝以 WITH 伪装的写语句，不能依赖 JDBC readOnly 提示。 */
    @Test
    void rejectsWithStatementOnReadOnlyConnection() throws Exception {
        stored("POSTGRESQL", objectMapper.readTree("""
            {"url":"jdbc:postgresql://unused/orders","username":"app","password":"","allowWrite":false}
            """));
        BusinessException exception = assertThrows(BusinessException.class, () -> execute("SQL_QUERY", objectMapper.readTree("""
            {"connectionId":1,"query":"WITH changed AS (DELETE FROM orders RETURNING id) SELECT * FROM changed","parameters":[]}
            """)));
        assertEquals("workflow.sqlWriteForbidden", exception.getMessageKey());
    }

    /** 可写连接也只开放查询和 DML，不允许通过工作流执行 DDL。 */
    @Test
    void rejectsDdlEvenWhenWritesAreEnabled() throws Exception {
        stored("MYSQL", objectMapper.readTree("""
            {"url":"jdbc:mysql://unused/orders","username":"app","password":"","allowWrite":true}
            """));
        BusinessException exception = assertThrows(BusinessException.class, () -> execute("SQL_QUERY", objectMapper.readTree("""
            {"connectionId":1,"query":"DROP TABLE orders","parameters":[]}
            """)));
        assertEquals("workflow.sqlUnsafe", exception.getMessageKey());
    }

    /** 邮件和即时通知节点必须复用平台受管客户端并返回可审计结果。 */
    @Test
    void delegatesEmailAndNotificationToManagedClients() throws Exception {
        MailManagementService.ResolvedRoute route = new MailManagementService.ResolvedRoute("ORDER", "smtp.example.com", 465,
            "app", "app@example.com", "SSL", "secret", List.of("ops@example.com"), List.of());
        when(mailManagementService.resolveRoute(5L)).thenReturn(route);
        when(mailDeliveryClient.send(route, "Order ready", "done")).thenReturn(Map.of("sent", true));
        assertEquals(true, execute("EMAIL_SEND", objectMapper.readTree("""
            {"routeId":5,"subject":"Order ready","body":"done"}
            """)).output().path("sent").asBoolean());
        verify(mailDeliveryClient).send(route, "Order ready", "done");

        stored("WEBHOOK", objectMapper.readTree("""
            {"url":"https://notify.example.com/hook","method":"POST","headers":{"Authorization":"Bearer token"}}
            """));
        when(apiTriggerService.test(any())).thenReturn(new ApiTriggerModels.ExecutionResult(202, 12, "accepted"));
        JsonNode notification = execute("IM_NOTIFY", objectMapper.readTree("""
            {"connectionId":1,"body":{"text":"hello"}}
            """)).output();
        assertEquals(202, notification.path("httpStatus").asInt());
        assertEquals("accepted", notification.path("body").asText());
    }

    /** 邮件节点主题必填但正文可省略，省略时仍以空正文调用受管邮件客户端。 */
    @Test
    void sendsEmailWhenBodyIsOmitted() throws Exception {
        MailManagementService.ResolvedRoute route = new MailManagementService.ResolvedRoute("ORDER", "smtp.example.com", 465,
            "app", "app@example.com", "SSL", "secret", List.of("ops@example.com"), List.of());
        when(mailManagementService.resolveRoute(5L)).thenReturn(route);
        when(mailDeliveryClient.send(route, "Order ready", "")).thenReturn(Map.of("sent", true));

        JsonNode output = execute("EMAIL_SEND", objectMapper.readTree("""
            {"routeId":5,"subject":"Order ready"}
            """)).output();

        assertEquals(true, output.path("sent").asBoolean());
        verify(mailDeliveryClient).send(route, "Order ready", "");
    }

    /** Tavily 原生适配器必须从加密连接注入 Bearer Header，正文不得包含 API Key。 */
    @Test
    void executesTavilySearchWithManagedBearerCredential() throws Exception {
        stored("TAVILY", objectMapper.readTree("{\"apiKey\":\"tvly-secret\"}"));
        when(apiTriggerService.test(any())).thenReturn(new ApiTriggerModels.ExecutionResult(
            200, 9, "{\"results\":[{\"title\":\"Base AI\"}]}"));

        JsonNode output = execute("TAVILY_TOOL", objectMapper.readTree("""
            {"connectionId":1,"operation":"SEARCH","query":"Base AI","searchDepth":"advanced","maxResults":3}
            """)).output();

        ArgumentCaptor<ApiTriggerModels.Command> captor = ArgumentCaptor.forClass(ApiTriggerModels.Command.class);
        verify(apiTriggerService).test(captor.capture());
        ApiTriggerModels.Command command = captor.getValue();
        assertEquals("https://api.tavily.com/search", command.url());
        assertEquals("Bearer tvly-secret", objectMapper.readTree(command.headers()).path("Authorization").asText());
        assertFalse(command.requestBody().contains("tvly-secret"));
        assertEquals("advanced", objectMapper.readTree(command.requestBody()).path("search_depth").asText());
        assertEquals("Base AI", output.path("json").path("results").get(0).path("title").asText());
    }

    /** Tavily 非成功状态必须中止节点，不能把鉴权失败当作正常业务输出。 */
    @Test
    void rejectsTavilyNonSuccessResponse() throws Exception {
        stored("TAVILY", objectMapper.readTree("{\"apiKey\":\"tvly-secret\"}"));
        when(apiTriggerService.test(any())).thenReturn(new ApiTriggerModels.ExecutionResult(401, 5, "unauthorized"));

        BusinessException exception = assertThrows(BusinessException.class, () -> execute("TAVILY_TOOL",
            objectMapper.readTree("{\"connectionId\":1,\"operation\":\"EXTRACT\",\"urls\":\"https://example.com\"}")));

        assertEquals("workflow.connectionExecutionFailed", exception.getMessageKey());
    }

    /** 连接限定的存储和消息目的地必须在建立外部连接前拒绝越界配置。 */
    @ParameterizedTest
    @MethodSource("forbiddenDestinations")
    void rejectsForbiddenExternalDestinations(String nodeType, String connectionType, String secretJson,
                                              String nodeJson, String expectedKey) throws Exception {
        stored(connectionType, objectMapper.readTree(secretJson));
        BusinessException exception = assertThrows(BusinessException.class,
            () -> execute(nodeType, objectMapper.readTree(nodeJson)));
        assertEquals(expectedKey, exception.getMessageKey());
    }

    /** 返回跨连接类型的越界和非法输入参数。 */
    private static Stream<Arguments> forbiddenDestinations() {
        return Stream.of(
            Arguments.of("S3_OBJECT", "S3", "{\"bucket\":\"files\",\"keyPrefix\":\"tenant/\"}",
                "{\"connectionId\":1,\"operation\":\"LIST\",\"bucket\":\"files\",\"prefix\":\"other/\"}", "workflow.s3PathForbidden"),
            Arguments.of("KAFKA_PUBLISH", "KAFKA", "{\"topicPrefix\":\"tenant.\"}",
                "{\"connectionId\":1,\"topic\":\"other.events\",\"value\":{}}", "workflow.messageDestinationForbidden"),
            Arguments.of("RABBITMQ_PUBLISH", "RABBITMQ", "{\"exchangePrefix\":\"tenant.\"}",
                "{\"connectionId\":1,\"destinationMode\":\"EXCHANGE\",\"exchange\":\"other.events\",\"value\":{}}", "workflow.messageDestinationForbidden"),
            Arguments.of("REDIS_COMMAND", "REDIS", "{\"uri\":\"redis://127.0.0.1:6379\"}",
                "{\"connectionId\":1,\"command\":\"GET\",\"arguments\":[null]}", "workflow.dataInputInvalid")
        );
    }

    /** 配置指定类型的受管连接。 */
    private void stored(String type, JsonNode config) {
        when(connections.resolved(anyLong(), anySet())).thenReturn(new WorkflowConnectionService.StoredConnection(
            1L, "TEST", "Test", type, config, 7L, true, null, null));
    }

    /** 执行单个连接节点。 */
    private WorkflowNodeExecutor.Result execute(String type, JsonNode config) {
        return executor.execute(new WorkflowNodeExecutor.Request("run", "node", type, (ObjectNode) config, context, 7L));
    }
}
