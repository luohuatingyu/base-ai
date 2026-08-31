package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowPluginNodeExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowPluginRegistryService registry;
    private WorkflowPluginWorkerClient workers;
    private WorkflowConnectionService connections;
    private WorkflowPluginNodeExecutor executor;

    /** 创建隔离的插件注册表、Worker 和连接替身。 */
    @BeforeEach
    void setUp() {
        registry = mock(WorkflowPluginRegistryService.class);
        workers = mock(WorkflowPluginWorkerClient.class);
        connections = mock(WorkflowConnectionService.class);
        executor = new WorkflowPluginNodeExecutor(mapper, new WorkflowExpressionService(mapper), registry, workers, connections);
    }

    /** 无凭据动作必须校验固定身份、必填参数并返回 Worker 业务输出。 */
    @Test
    void executesPinnedPluginComponent() throws Exception {
        var component = component(mapper.readTree("[{\"name\":\"query\",\"type\":\"string\",\"required\":true}]"),
            mapper.createArrayNode());
        when(registry.requireRuntimeComponent(7L)).thenReturn(component);
        when(workers.invoke(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(mapper.readTree("{\"ok\":true}"));

        var result = executor.execute(request("""
            {"pluginComponentId":7,"packageFingerprint":"%s","componentExternalId":"action",
             "componentType":"ACTION","parameters":{"query":"hello"}}
            """.formatted("a".repeat(64))));

        assertEquals(true, result.output().path("ok").asBoolean());
        verify(connections, never()).requireOwnedAndEnabled(any(), any(), any());
    }

    /** 凭据必须来自同一组件且 Worker 调用只接收 credentials 子对象。 */
    @Test
    void usesOwnedEncryptedPluginCredentials() throws Exception {
        var component = component(mapper.createArrayNode(), mapper.readTree("""
            [{"name":"apiKey","type":"secret-input","required":true}]
            """));
        when(registry.requireRuntimeComponent(7L)).thenReturn(component);
        when(connections.requireOwnedAndEnabled(9L, 3L, Set.of("PLUGIN"))).thenReturn(
            new WorkflowConnectionService.StoredConnection(9L, "P", "Plugin", "PLUGIN",
                mapper.readTree("{\"pluginComponentId\":7,\"credentials\":{\"apiKey\":\"secret\"}}"),
                3L, true, null, null));
        when(workers.invoke(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                assertEquals("secret", invocation.getArgument(5, com.fasterxml.jackson.databind.JsonNode.class)
                    .path("apiKey").asText());
                return mapper.createObjectNode().put("ok", true);
            });

        executor.execute(request("""
            {"pluginComponentId":7,"packageFingerprint":"%s","componentExternalId":"action",
             "componentType":"ACTION","connectionId":9,"parameters":{}}
            """.formatted("a".repeat(64))));
    }

    /** 非对象凭据配置必须返回稳定业务消息键，不能因缺失资源退化为 500。 */
    @Test
    void rejectsInvalidCredentialConfigurationWithLocalizedBusinessError() throws Exception {
        var component = component(mapper.createArrayNode(), mapper.readTree("""
            [{"name":"apiKey","type":"secret-input","required":true}]
            """));
        when(registry.requireRuntimeComponent(7L)).thenReturn(component);
        when(connections.requireOwnedAndEnabled(9L, 3L, Set.of("PLUGIN"))).thenReturn(
            new WorkflowConnectionService.StoredConnection(9L, "P", "Plugin", "PLUGIN",
                mapper.readTree("{\"pluginComponentId\":7,\"credentials\":\"invalid\"}"),
                3L, true, null, null));

        BusinessException exception = assertThrows(BusinessException.class, () -> executor.execute(request("""
            {"pluginComponentId":7,"packageFingerprint":"%s","componentExternalId":"action",
             "componentType":"ACTION","connectionId":9,"parameters":{}}
            """.formatted("a".repeat(64)))));

        assertEquals(400, exception.getStatus());
        assertEquals("workflow.connectionConfigInvalid", exception.getMessageKey());
        verify(workers, never()).invoke(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 伪造包摘要或缺少必填参数必须在 Worker 调用前失败。 */
    @Test
    void rejectsForgedIdentityAndMissingParameters() throws Exception {
        when(registry.requireRuntimeComponent(7L)).thenReturn(component(
            mapper.readTree("[{\"name\":\"query\",\"required\":true}]"), mapper.createArrayNode()));

        assertThrows(BusinessException.class, () -> executor.execute(request("""
            {"pluginComponentId":7,"packageFingerprint":"%s","componentExternalId":"action",
             "componentType":"ACTION","parameters":{}}
            """.formatted("b".repeat(64)))));
        verify(workers, never()).invoke(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 已作为运行输入传入的插件事件应成为入口输出，不能在执行图中重复订阅。 */
    @Test
    void passesPluginTriggerEventIntoWorkflow() throws Exception {
        when(registry.requireRuntimeComponent(7L)).thenReturn(new WorkflowPluginRegistryService.RuntimeComponent(
            7L, "DIFY", "pkg", "1", "a".repeat(64), true, "event", "TRIGGER",
            mapper.createArrayNode(), mapper.createArrayNode(), "SUPPORTED"));

        var result = executor.execute(request("PLUGIN_TRIGGER", """
            {"pluginComponentId":7,"packageFingerprint":"%s","componentExternalId":"event",
             "componentType":"TRIGGER","parameters":{}}
            """.formatted("a".repeat(64))));

        assertEquals(1, result.output().path("x").asInt());
        verify(workers, never()).invoke(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 构造固定版本运行组件。 */
    private WorkflowPluginRegistryService.RuntimeComponent component(com.fasterxml.jackson.databind.JsonNode schema,
                                                                     com.fasterxml.jackson.databind.JsonNode credentials) {
        return new WorkflowPluginRegistryService.RuntimeComponent(7L, "N8N", "pkg", "1", "a".repeat(64), true,
            "action", "ACTION", schema, credentials, "SUPPORTED");
    }

    /** 构造插件节点执行请求。 */
    private WorkflowNodeExecutor.Request request(String config) throws Exception {
        return request("PLUGIN_ACTION", config);
    }

    /** 构造指定类型的插件节点执行请求。 */
    private WorkflowNodeExecutor.Request request(String type, String config) throws Exception {
        var context = mapper.createObjectNode();
        context.set("input", mapper.createObjectNode().put("x", 1));
        return new WorkflowNodeExecutor.Request("run", "node", type,
            (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(config), context, 3L);
    }
}
