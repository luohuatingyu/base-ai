package com.baseai.platform.deviceagent;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证通用 Agent 命令白名单、租约令牌和身份隔离。 */
class DeviceAgentCommandServiceTest {
    private DeviceAgentCommandService service;
    private JdbcTemplate db;

    /** 建立独立命令表并模拟已配对 Agent。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:device-agent-command-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(dataSource);
        db.execute("""
            CREATE TABLE automation_device_agent_command (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), command_type VARCHAR(32),
              command_params JSON, status VARCHAR(16) DEFAULT 'PENDING', lease_token VARCHAR(96),
              lease_expires_at TIMESTAMP, result_summary VARCHAR(2000), error_code VARCHAR(64),
              started_at TIMESTAMP, completed_at TIMESTAMP, created_by BIGINT,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        DeviceAgentRegistrationService registration = mock(DeviceAgentRegistrationService.class);
        when(registration.requirePaired(anyString())).thenReturn(
            new DeviceAgentRegistrationService.ExistingRegistration("PAIRED", null));
        service = new DeviceAgentCommandService(db, new ObjectMapper(), registration);
    }

    /** WDA 和 Appium 命令必须被通用设备 Agent 拒绝。 */
    @Test
    void rejectsRemovedDeviceControlCommands() {
        for (String command : List.of("SETUP_WDA", "START_WDA", "REGISTRY_ONLINE", "VERIFY_ACCOUNT")) {
            BusinessException exception = assertThrows(BusinessException.class, () -> service.create(
                new DeviceAgentModels.CreateCommandRequest("ios-agent-test", command, Map.of()), 7L));
            assertEquals("deviceAgent.commandInvalid", exception.getMessageKey());
        }
    }

    /** 合法命令应被单个 Agent 领取，并只能使用匹配租约回报。 */
    @Test
    void leasesCommandAndRejectsAnotherAgentResult() {
        db.update("""
            INSERT INTO automation_device_agent_command
              (agent_id, command_type, command_params, created_by) VALUES (?, 'DIAGNOSTICS', '{}', 7)
            """, "ios-agent-test");

        DeviceAgentModels.LeaseCommandResponse lease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("DIAGNOSTICS")));

        assertNotNull(lease);
        assertNotNull(lease.leaseToken());
        assertThrows(BusinessException.class, () -> service.reportResult("ios-agent-other", lease.commandId(),
            new DeviceAgentModels.ReportCommandResultRequest(lease.leaseToken(), "COMPLETED", "ok", null)));
        service.reportResult("ios-agent-test", lease.commandId(),
            new DeviceAgentModels.ReportCommandResultRequest(lease.leaseToken(), "COMPLETED", "ok", null));
        assertEquals("COMPLETED", service.get(lease.commandId()).status());
    }
}
