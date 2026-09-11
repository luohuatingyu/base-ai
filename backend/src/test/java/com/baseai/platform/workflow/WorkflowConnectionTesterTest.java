package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.knowledge.VectorStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 覆盖连接测试器的指标采集与最近检测结果留存逻辑。 */
class WorkflowConnectionTesterTest {
    private WorkflowConnectionService connectionService;
    private VectorStoreService vectorStoreService;
    private WorkflowConnectionTester tester;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 使用模拟的连接存储与向量探测服务构建被测对象。 */
    @BeforeEach
    void setUp() {
        connectionService = Mockito.mock(WorkflowConnectionService.class);
        vectorStoreService = Mockito.mock(VectorStoreService.class);
        tester = new WorkflowConnectionTester(connectionService, null, objectMapper, vectorStoreService, null);
    }

    /** 构造内部连接记录。 */
    private WorkflowConnectionService.StoredConnection connection(String type, JsonNode config) {
        return new WorkflowConnectionService.StoredConnection(9L, "TEST", "Test", type, config, 7L, true, null, null);
    }

    /** 构造合法插件配置。 */
    private JsonNode pluginConfig() {
        var config = objectMapper.createObjectNode();
        config.put("pluginComponentId", 1L);
        config.putObject("credentials").put("apiKey", "k");
        return config;
    }

    /** 构造包含 URL 的 JDBC 配置。 */
    private JsonNode jdbcConfig(String url) {
        var config = objectMapper.createObjectNode();
        config.put("url", url);
        config.put("username", "sa");
        config.put("password", "");
        return config;
    }

    /** 插件连接通过身份校验后必须留存成功结果并返回延迟与指标。 */
    @Test
    void recordsSuccessfulTestResultForPluginConnection() {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("PLUGIN", pluginConfig()));

        Map<String, Object> result = tester.test(9L);

        assertEquals(true, result.get("connected"));
        assertEquals("PLUGIN", result.get("connectionType"));
        assertTrue((Integer) result.get("latencyMs") >= 0);
        verify(connectionService).recordTestResult(eq(9L), eq(true), anyInt(), eq("{}"));
    }

    /** 插件连接缺少组件身份时必须留存失败结果并抛出业务异常。 */
    @Test
    void recordsFailedTestResultForInvalidPluginConnection() {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("PLUGIN", objectMapper.createObjectNode()));

        assertThrows(BusinessException.class, () -> tester.test(9L));
        verify(connectionService).recordTestResult(eq(9L), eq(false), anyInt(), anyString());
    }

    /** JDBC 连接失败时必须留存失败结果且不写入成功指标。 */
    @Test
    void recordsFailedTestResultForUnreachableJdbcConnection() {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("MYSQL", jdbcConfig("jdbc:invalid://host/db")));

        assertThrows(BusinessException.class, () -> tester.test(9L));
        verify(connectionService).recordTestResult(eq(9L), eq(false), anyInt(), anyString());
    }

    /** PostgreSQL 向量探测成功时必须同时留存向量能力与检测结果。 */
    @Test
    void recordsVectorCapabilityAndTestResultForSupportedPostgres() {
        WorkflowConnectionService.StoredConnection postgres = connection("POSTGRESQL",
            jdbcConfig("jdbc:h2:mem:vector-ok-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"));
        when(connectionService.ownedForTest(9L)).thenReturn(postgres);
        when(vectorStoreService.probe(postgres)).thenReturn(new VectorStoreService.Capability(true, "pgvector", "0.7", ""));

        Map<String, Object> result = tester.test(9L);

        assertEquals(true, result.get("connected"));
        assertEquals(true, result.get("vectorSupported"));
        verify(connectionService).recordVectorCapability(9L, "SUPPORTED", "pgvector", "0.7", "");
        verify(connectionService).recordTestResult(eq(9L), eq(true), anyInt(), anyString());
    }

    /** 向量能力不受支持时连通结果必须为失败并留存原因。 */
    @Test
    void recordsUnsupportedVectorCapabilityAsFailedTest() {
        WorkflowConnectionService.StoredConnection postgres = connection("POSTGRESQL",
            jdbcConfig("jdbc:h2:mem:vector-bad-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"));
        when(connectionService.ownedForTest(9L)).thenReturn(postgres);
        when(vectorStoreService.probe(postgres)).thenReturn(new VectorStoreService.Capability(false, "", "", "no extension"));

        Map<String, Object> result = tester.test(9L);

        assertEquals(false, result.get("connected"));
        assertEquals(false, result.get("vectorSupported"));
        verify(connectionService).recordVectorCapability(9L, "UNSUPPORTED", "", "", "no extension");
        verify(connectionService).recordTestResult(eq(9L), eq(false), anyInt(), anyString());
    }

    /** 非向量类型不得触发向量能力写入。 */
    @Test
    void skipsVectorCapabilityForNonVectorTypes() {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("PLUGIN", pluginConfig()));

        tester.test(9L);

        verify(connectionService, never()).recordVectorCapability(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    /** OSS 连接测试失败时必须留存最近检测结果。 */
    @Test
    void recordsFailedTestResultForUnreachableOssConnection() {
        JsonNode config = objectMapper.createObjectNode()
            .put("endpoint", "http://localhost:1")
            .put("bucket", "test-bucket")
            .put("accessKey", "ak")
            .put("secretKey", "sk");
        when(connectionService.ownedForTest(9L)).thenReturn(connection("OSS", config));

        assertThrows(BusinessException.class, () -> tester.test(9L));
        verify(connectionService).recordTestResult(eq(9L), eq(false), anyInt(), anyString());
    }
}
