package com.baseai.platform.deviceagent;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 Remote XPC Registry 配置、状态和固定动作。 */
class DeviceAgentRegistryServiceTest {
    private DeviceAgentRegistryService service;

    /** 建立 Registry、命令和审计表并模拟已注册 Agent。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:device-agent-registry-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(dataSource);
        db.execute("""
            CREATE TABLE automation_device_agent_registry (
              agent_id VARCHAR(64) PRIMARY KEY, port_override INT, desired_state VARCHAR(16),
              observed_state VARCHAR(24), observed_port INT, tunnel_count INT, helper_version VARCHAR(64),
              last_error_code VARCHAR(64), config_version BIGINT DEFAULT 1, last_reported_at TIMESTAMP,
              created_by BIGINT, updated_by BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_command (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), command_type VARCHAR(32),
              status VARCHAR(16) DEFAULT 'PENDING')
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_audit (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), event_type VARCHAR(64),
              event_detail JSON, user_id BIGINT)
            """);
        DeviceAgentRegistrationService registration = mock(DeviceAgentRegistrationService.class);
        when(registration.requireExists("ios-agent-test")).thenReturn(
            new DeviceAgentRegistrationService.ExistingRegistration("PAIRED", null));
        DeviceAgentCommandService commandService = mock(DeviceAgentCommandService.class);
        when(commandService.create(any(DeviceAgentModels.CreateCommandRequest.class), anyLong()))
            .thenReturn(command(11L));
        PlatformProperties properties = new PlatformProperties();
        service = new DeviceAgentRegistryService(db, new ObjectMapper(), properties,
            registration, commandService);
    }

    /** 端口覆盖、期望上线和 Agent 状态上报应形成完整状态视图。 */
    @Test
    void managesRegistryConfigActionAndStatus() {
        DeviceAgentModels.AgentRegistryView configured = service.update("ios-agent-test",
            new DeviceAgentModels.UpdateAgentRegistryRequest(43000), 7L);
        DeviceAgentModels.AgentCommandView action = service.action("ios-agent-test",
            new DeviceAgentModels.AgentRegistryActionRequest("ONLINE"), 7L);
        service.report("ios-agent-test", new DeviceAgentModels.AgentRegistryStatusRequest(
            "ONLINE", 43000, 2, "1.0.0", null));
        DeviceAgentModels.AgentRegistryView reported = service.get("ios-agent-test");

        assertEquals(43000, configured.effectivePort());
        assertEquals(11L, action.id());
        assertEquals("ONLINE", reported.desiredState());
        assertEquals("ONLINE", reported.observedState());
        assertEquals(2, reported.tunnelCount());
    }

    /** 越界端口、任意命令字符串和离线重建必须被拒绝。 */
    @Test
    void rejectsUnsafeRegistryOperations() {
        assertThrows(BusinessException.class, () -> service.update("ios-agent-test",
            new DeviceAgentModels.UpdateAgentRegistryRequest(80), 7L));
        assertThrows(BusinessException.class, () -> service.action("ios-agent-test",
            new DeviceAgentModels.AgentRegistryActionRequest("RUN --shell"), 7L));
        assertThrows(BusinessException.class, () -> service.action("ios-agent-test",
            new DeviceAgentModels.AgentRegistryActionRequest("RECREATE"), 7L));
    }

    /** 构造 Registry 命令视图。 */
    private DeviceAgentModels.AgentCommandView command(Long id) {
        return new DeviceAgentModels.AgentCommandView(id, "ios-agent-test", null,
            "REGISTRY_ONLINE", Map.of(), "PENDING", null, null,
            null, null, null, null);
    }
}
