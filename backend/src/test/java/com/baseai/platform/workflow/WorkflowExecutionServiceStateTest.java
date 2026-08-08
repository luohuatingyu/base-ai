package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceRuntime;
import com.baseai.platform.trace.TraceRuntimeRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowExecutionServiceStateTest {
    private JdbcTemplate jdbcTemplate;
    private WorkflowService workflowService;
    private ThreadPoolTaskExecutor executor;
    private TaskTraceService taskTraceService;
    private TraceRuntimeRegistry runtimeRegistry;
    private WorkflowExecutionService service;
    private ObjectMapper objectMapper;

    /** 创建最小真实运行表和隔离依赖。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-state-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource); objectMapper = new ObjectMapper();
        jdbcTemplate.execute("CREATE TABLE workflow_definition(id BIGINT PRIMARY KEY,code VARCHAR(80))");
        jdbcTemplate.execute("CREATE TABLE workflow_version(id BIGINT PRIMARY KEY,workflow_id BIGINT,version_number INT)");
        jdbcTemplate.execute("""
            CREATE TABLE workflow_run(id VARCHAR(36) PRIMARY KEY,workflow_id BIGINT,workflow_version_id BIGINT,parent_run_id VARCHAR(36),
            trace_id VARCHAR(64),trigger_type VARCHAR(20),status VARCHAR(20),input_encrypted CLOB,output_encrypted CLOB,
            error_message VARCHAR(2000),owner_user_id BIGINT,api_key_id BIGINT,cancel_requested BOOLEAN DEFAULT false,
            execution_instance_id VARCHAR(120),lease_expires_at TIMESTAMP,log_bytes BIGINT DEFAULT 0,started_at TIMESTAMP,
            finished_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_node_run(id BIGINT AUTO_INCREMENT PRIMARY KEY,workflow_run_id VARCHAR(36),node_id VARCHAR(100),
            node_name VARCHAR(120),node_type VARCHAR(24),sequence_no INT,iteration_path VARCHAR(200),status VARCHAR(20),
            input_encrypted CLOB,output_encrypted CLOB,error_message VARCHAR(2000),started_at TIMESTAMP,finished_at TIMESTAMP)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_wait_state(workflow_run_id VARCHAR(36) PRIMARY KEY,node_id VARCHAR(100),resume_at TIMESTAMP,
            state_encrypted CLOB,status VARCHAR(20),updated_at TIMESTAMP)
            """);
        jdbcTemplate.update("INSERT INTO workflow_definition VALUES (1,'ORDERS')");
        jdbcTemplate.update("INSERT INTO workflow_version VALUES (2,1,1)");
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        properties.getWorkflow().setMaxPayloadBytes(32); properties.getWorkflow().setMaxRunLogBytes(48);
        workflowService = mock(WorkflowService.class); executor = mock(ThreadPoolTaskExecutor.class);
        taskTraceService = mock(TaskTraceService.class); runtimeRegistry = mock(TraceRuntimeRegistry.class);
        service = new WorkflowExecutionService(jdbcTemplate, objectMapper, new ConfigCryptoService(properties), workflowService,
            new WorkflowExpressionService(objectMapper), mock(WorkflowAgentClient.class),
            mock(com.baseai.platform.service.AiChatClient.class), mock(com.baseai.platform.automation.ApiTriggerService.class),
            mock(WorkflowNodeExecutorRegistry.class), mock(WorkflowAccessService.class), executor, taskTraceService,
            runtimeRegistry, properties);
        AuthContext.set(new AuthUser(7L, "owner", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
    }

    /** 清理认证上下文。 */
    @AfterEach
    void tearDown() { AuthContext.clear(); }

    /** 已取消运行不能被迟到的执行线程覆盖为成功。 */
    @Test
    void cancelledStatusIsTerminalAgainstLateSuccess() {
        jdbcTemplate.update("""
            INSERT INTO workflow_run(id,workflow_id,workflow_version_id,trace_id,trigger_type,status,input_encrypted,
            output_encrypted,owner_user_id,cancel_requested) VALUES ('run-1',1,2,'trace-1','MANUAL','CANCELLED','','',7,true)
            """);

        assertFalse(service.completeRunSuccess("run-1", objectMapper.createObjectNode().put("ok", true)));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject("SELECT status FROM workflow_run WHERE id='run-1'", String.class));
    }

    /** 已取消子运行也不能被迟到异常覆盖为失败。 */
    @Test
    void cancelledStatusIsTerminalAgainstLateFailure() {
        jdbcTemplate.update("""
            INSERT INTO workflow_run(id,workflow_id,workflow_version_id,trace_id,trigger_type,status,input_encrypted,
            output_encrypted,owner_user_id,cancel_requested) VALUES ('run-failed',1,2,'trace-failed','SUB_WORKFLOW','CANCELLED','','',7,true)
            """);

        assertFalse(service.completeRunFailure("run-failed", "late failure"));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
            "SELECT status FROM workflow_run WHERE id='run-failed'", String.class));
    }

    /** 队列拒绝必须把已持久化运行置为失败并返回 503，而不是永久停留 QUEUED。 */
    @Test
    void queueRejectionFailsPersistedRun() {
        WorkflowModels.StoredVersion version = new WorkflowModels.StoredVersion(2L, 1L, "ORDERS", 1,
            objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(), 7L);
        when(workflowService.executable("ORDERS", true)).thenReturn(version);
        when(taskTraceService.create(any(), any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn("trace-2");
        when(runtimeRegistry.create("trace-2")).thenReturn(new TraceRuntime("trace-2"));
        when(executor.submit(any(Runnable.class))).thenThrow(new TaskRejectedException("full"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.startPublished("ORDERS", Map.of()));

        assertEquals(503, exception.getStatus());
        assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT status FROM workflow_run", String.class));
    }

    /** 节点日志按单次运行累计计费，不能通过多个合规小输出放大数据库占用。 */
    @Test
    void enforcesCumulativeRunLogBudget() {
        jdbcTemplate.update("""
            INSERT INTO workflow_run(id,workflow_id,workflow_version_id,trace_id,trigger_type,status,input_encrypted,
            output_encrypted,owner_user_id,cancel_requested) VALUES ('run-log',1,2,'trace-log','MANUAL','RUNNING','','',7,false)
            """);
        var node = objectMapper.createObjectNode().put("id", "node").put("type", "START");
        assertThrows(BusinessException.class, () -> {
            ReflectionTestUtils.invokeMethod(service, "startNodeRun", "run-log", node, "START", 1, "",
                objectMapper.getNodeFactory().textNode("x".repeat(30)));
            ReflectionTestUtils.invokeMethod(service, "startNodeRun", "run-log", node, "START", 2, "",
                objectMapper.getNodeFactory().textNode("x".repeat(30)));
        });
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflow_node_run WHERE workflow_run_id='run-log'", Integer.class));
    }

    /** 新实例只能回收过期租约，其他实例仍活跃的运行必须保持不变。 */
    @Test
    void recoveryOnlyFailsExpiredLeases() {
        jdbcTemplate.update("""
            INSERT INTO workflow_run(id,workflow_id,workflow_version_id,trace_id,trigger_type,status,input_encrypted,
            output_encrypted,owner_user_id,cancel_requested,execution_instance_id,lease_expires_at)
            VALUES ('expired',1,2,'trace-expired','MANUAL','RUNNING','','',7,false,'old',?),
                   ('active',1,2,'trace-active','MANUAL','RUNNING','','',7,false,'other',?)
            """, java.sql.Timestamp.from(Instant.now().minusSeconds(30)), java.sql.Timestamp.from(Instant.now().plusSeconds(30)));

        service.recoverExpiredLeases();

        assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT status FROM workflow_run WHERE id='expired'", String.class));
        assertEquals("RUNNING", jdbcTemplate.queryForObject("SELECT status FROM workflow_run WHERE id='active'", String.class));
    }

    /** 等待恢复提交被拒绝时必须回到 WAITING，供下一轮调度重试。 */
    @Test
    void resumeQueueRejectionReturnsCheckpointToWaiting() {
        jdbcTemplate.update("""
            INSERT INTO workflow_run(id,workflow_id,workflow_version_id,trace_id,trigger_type,status,input_encrypted,
            output_encrypted,owner_user_id,cancel_requested) VALUES ('waiting',1,2,'trace-wait','WAIT','WAITING','','',7,false)
            """);
        jdbcTemplate.update("""
            INSERT INTO workflow_wait_state VALUES ('waiting','wait-node',?,'encrypted','WAITING',CURRENT_TIMESTAMP)
            """, java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        when(runtimeRegistry.create("trace-wait")).thenReturn(new TraceRuntime("trace-wait"));
        when(executor.submit(any(Runnable.class))).thenThrow(new TaskRejectedException("full"));

        service.resumeDueWaits();

        assertEquals("WAITING", jdbcTemplate.queryForObject("SELECT status FROM workflow_run WHERE id='waiting'", String.class));
        assertEquals("WAITING", jdbcTemplate.queryForObject("SELECT status FROM workflow_wait_state WHERE workflow_run_id='waiting'", String.class));
    }
}
