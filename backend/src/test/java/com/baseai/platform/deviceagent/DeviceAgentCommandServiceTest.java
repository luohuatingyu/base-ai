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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证通用 Agent 命令白名单、租约令牌和身份隔离。 */
class DeviceAgentCommandServiceTest {
    private static final String DEVICE_ID = "a".repeat(64);
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
            CREATE TABLE automation_device_agent_registration (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64) UNIQUE,
              pairing_status VARCHAR(16))
            """);
        db.update("""
            INSERT INTO automation_device_agent_registration (agent_id, pairing_status)
            VALUES ('ios-agent-test', 'PAIRED')
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_command (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), target_device_id CHAR(64),
              command_type VARCHAR(32),
              command_params JSON, status VARCHAR(16) DEFAULT 'PENDING', lease_token VARCHAR(96),
              lease_expires_at TIMESTAMP, result_summary VARCHAR(2000), error_code VARCHAR(64),
              started_at TIMESTAMP, completed_at TIMESTAMP, created_by BIGINT,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_device (
              agent_id VARCHAR(64), device_id CHAR(64), connected BOOLEAN,
              wda_status VARCHAR(16), wda_running BOOLEAN, wda_port_error_code VARCHAR(64),
              last_error_code VARCHAR(64), PRIMARY KEY (agent_id, device_id))
            """);
        db.update("""
            INSERT INTO automation_device_agent_device
              (agent_id, device_id, connected, wda_status, wda_running)
            VALUES ('ios-agent-test', ?, TRUE, 'MISSING', FALSE)
            """, DEVICE_ID);
        DeviceAgentRegistrationService registration = mock(DeviceAgentRegistrationService.class);
        when(registration.requirePaired(anyString())).thenReturn(
            new DeviceAgentRegistrationService.ExistingRegistration("PAIRED", null));
        service = new DeviceAgentCommandService(db, new ObjectMapper(), registration);
    }

    /** 企业微信业务命令和缺少设备目标的 WDA 命令必须被拒绝。 */
    @Test
    void rejectsBusinessAndUntargetedDeviceCommands() {
        BusinessException business = assertThrows(BusinessException.class, () -> service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "VERIFY_ACCOUNT", Map.of()), 7L));
        BusinessException untargeted = assertThrows(BusinessException.class, () -> service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "SETUP_WDA", Map.of()), 7L));

        assertEquals("deviceAgent.commandInvalid", business.getMessageKey());
        assertEquals("deviceAgent.commandTargetInvalid", untargeted.getMessageKey());
    }

    /** WDA 安装和启动命令应绑定匿名目标，并把执行终态回写到目标设备。 */
    @Test
    void executesTargetedWdaLifecycleAndUpdatesDeviceState() {
        DeviceAgentModels.AgentCommandView setup = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", DEVICE_ID,
                "SETUP_WDA", Map.of()), 7L);
        DeviceAgentModels.LeaseCommandResponse setupLease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("SETUP_WDA")));
        service.reportResult("ios-agent-test", setup.id(), new DeviceAgentModels.ReportCommandResultRequest(
            setupLease.leaseToken(), "COMPLETED", "ready", null));

        assertEquals(DEVICE_ID, setup.targetDeviceId());
        assertEquals("READY", db.queryForObject("""
            SELECT wda_status FROM automation_device_agent_device WHERE agent_id='ios-agent-test'
            """, String.class));
        assertFalse(Boolean.TRUE.equals(db.queryForObject("""
            SELECT wda_running FROM automation_device_agent_device WHERE agent_id='ios-agent-test'
            """, Boolean.class)));

        DeviceAgentModels.AgentCommandView start = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", DEVICE_ID,
                "START_WDA", Map.of()), 7L);
        DeviceAgentModels.LeaseCommandResponse startLease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("START_WDA")));
        service.reportResult("ios-agent-test", start.id(), new DeviceAgentModels.ReportCommandResultRequest(
            startLease.leaseToken(), "COMPLETED", "online", null));
        assertTrue(Boolean.TRUE.equals(db.queryForObject("""
            SELECT wda_running FROM automation_device_agent_device WHERE agent_id='ios-agent-test'
            """, Boolean.class)));
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

    /** Agent 正在执行任务时必须拒绝升级，不能把升级命令放入队列。 */
    @Test
    void rejectsUpgradeWhileAnotherCommandIsExecuting() {
        DeviceAgentModels.AgentCommandView diagnostics = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "DIAGNOSTICS", Map.of()), 7L);
        DeviceAgentModels.LeaseCommandResponse lease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("DIAGNOSTICS")));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "UPGRADE", Map.of()), 7L));

        assertEquals("deviceAgent.upgradeAgentBusy", exception.getMessageKey());
        assertEquals("LEASED", service.get(diagnostics.id()).status());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM automation_device_agent_command", Integer.class));
        assertNotNull(lease);
    }

    /** 升级应抢在旧排队任务前执行，并在终态前拒绝和停止领取其他任务。 */
    @Test
    void prioritizesUpgradeAndBlocksOtherCommandsUntilCompletion() {
        DeviceAgentModels.AgentCommandView diagnostics = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "DIAGNOSTICS", Map.of()), 7L);
        DeviceAgentModels.AgentCommandView upgrade = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "UPGRADE", Map.of()), 7L);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "HEALTH_CHECK", Map.of()), 7L));
        DeviceAgentModels.LeaseCommandResponse upgradeLease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("DIAGNOSTICS", "UPGRADE")));

        assertEquals("deviceAgent.upgradeInProgress", exception.getMessageKey());
        assertNotNull(upgradeLease);
        assertEquals(upgrade.id(), upgradeLease.commandId());
        assertNull(service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("DIAGNOSTICS", "UPGRADE"))));

        service.reportResult("ios-agent-test", upgrade.id(),
            new DeviceAgentModels.ReportCommandResultRequest(
                upgradeLease.leaseToken(), "COMPLETED", "upgraded", null));
        DeviceAgentModels.LeaseCommandResponse resumed = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("DIAGNOSTICS")));

        assertNotNull(resumed);
        assertEquals(diagnostics.id(), resumed.commandId());
    }

    /** 待升级时旧版 Agent 即使未声明升级能力，也不能越过升级命令领取普通任务。 */
    @Test
    void blocksOtherLeasesWhenUpgradeCapabilityIsMissing() {
        service.create(new DeviceAgentModels.CreateCommandRequest(
            "ios-agent-test", "DIAGNOSTICS", Map.of()), 7L);
        service.create(new DeviceAgentModels.CreateCommandRequest(
            "ios-agent-test", "UPGRADE", Map.of()), 7L);

        DeviceAgentModels.LeaseCommandResponse lease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("DIAGNOSTICS")));

        assertNull(lease);
        assertEquals(2, db.queryForObject("""
            SELECT COUNT(*) FROM automation_device_agent_command WHERE status='PENDING'
            """, Integer.class));
    }

    /** 升级失败进入终态后应解除门禁，让旧版本 Agent 继续领取任务。 */
    @Test
    void resumesTaskLeasingAfterUpgradeFailure() {
        DeviceAgentModels.AgentCommandView upgrade = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "UPGRADE", Map.of()), 7L);
        DeviceAgentModels.LeaseCommandResponse upgradeLease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("UPGRADE")));
        assertNotNull(upgradeLease);
        service.reportResult("ios-agent-test", upgrade.id(),
            new DeviceAgentModels.ReportCommandResultRequest(
                upgradeLease.leaseToken(), "FAILED", "upgrade failed", "UPGRADE_INSTALL_FAILED"));

        DeviceAgentModels.AgentCommandView healthCheck = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "HEALTH_CHECK", Map.of()), 7L);
        DeviceAgentModels.LeaseCommandResponse resumed = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("HEALTH_CHECK")));

        assertNotNull(resumed);
        assertEquals(healthCheck.id(), resumed.commandId());
    }

    /** 没有升级任务时继续保留普通命令独立领取的既有行为。 */
    @Test
    void preservesOrdinaryCommandLeasingWithoutUpgrade() {
        DeviceAgentModels.AgentCommandView diagnostics = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "DIAGNOSTICS", Map.of()), 7L);
        DeviceAgentModels.AgentCommandView healthCheck = service.create(
            new DeviceAgentModels.CreateCommandRequest("ios-agent-test", "HEALTH_CHECK", Map.of()), 7L);

        DeviceAgentModels.LeaseCommandResponse diagnosticsLease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("DIAGNOSTICS")));
        DeviceAgentModels.LeaseCommandResponse healthCheckLease = service.lease("ios-agent-test",
            new DeviceAgentModels.LeaseCommandRequest(List.of("HEALTH_CHECK")));

        assertNotNull(diagnosticsLease);
        assertNotNull(healthCheckLease);
        assertEquals(diagnostics.id(), diagnosticsLease.commandId());
        assertEquals(healthCheck.id(), healthCheckLease.commandId());
    }
}
