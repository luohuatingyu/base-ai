package com.baseai.platform.deviceagent;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 WDA 配置加密、回环地址限制和热加载命令。 */
class DeviceAgentAutomationConfigServiceTest {
    private DeviceAgentAutomationConfigService service;
    private JdbcTemplate db;
    private DeviceAgentCommandService commandService;

    /** 建立每测试独立数据库并模拟已配对 Agent。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:device-agent-wda-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(dataSource);
        db.execute("""
            CREATE TABLE automation_device_agent_wda_config (
              agent_id VARCHAR(64) PRIMARY KEY, signing_config_encrypted TEXT,
              launch_mode VARCHAR(16), wda_url VARCHAR(256), appium_server_url VARCHAR(256),
              base_wda_local_port INT, operation_speed VARCHAR(16) DEFAULT 'STANDARD',
              wireless_source_poll_interval_seconds INT DEFAULT 10,
              wireless_source_max_attempts INT DEFAULT 12,
              config_version BIGINT, config_hash CHAR(64),
              created_by BIGINT, updated_by BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_audit (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), event_type VARCHAR(64),
              event_detail JSON, user_id BIGINT)
            """);
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        DeviceAgentRegistrationService registration = mock(DeviceAgentRegistrationService.class);
        when(registration.requireExists("ios-agent-test")).thenReturn(
            new DeviceAgentRegistrationService.ExistingRegistration("PAIRED", null));
        commandService = mock(DeviceAgentCommandService.class);
        service = new DeviceAgentAutomationConfigService(db, new ObjectMapper(),
            new ConfigCryptoService(properties), registration, commandService);
    }

    /** 签名配置必须以密文落库，并向已配对 Agent 下发配置刷新。 */
    @Test
    void encryptsSigningConfigAndDispatchesReload() {
        DeviceAgentModels.AgentWdaConfigView view = service.update("ios-agent-test",
            new DeviceAgentModels.UpdateAgentWdaConfigRequest(
                new DeviceAgentModels.WdaSigningConfig("ABCDEFGHIJ", "Apple Development",
                    "com.example.WebDriverAgentRunner", false),
                "XCODEBUILD", null, "http://127.0.0.1:4723", 8200), 7L);
        String encrypted = db.queryForObject("""
            SELECT signing_config_encrypted FROM automation_device_agent_wda_config WHERE agent_id=?
            """, String.class, "ios-agent-test");

        assertEquals(8200, view.baseWdaLocalPort());
        assertEquals("ABCDEFGHIJ", view.signingConfig().xcodeOrgId());
        assertFalse(encrypted.contains("ABCDEFGHIJ"));
        verify(commandService).create(any(DeviceAgentModels.CreateCommandRequest.class), anyLong());
    }

    /** 外部 Appium 地址和越界端口必须在保存前被拒绝。 */
    @Test
    void rejectsExternalAppiumAndInvalidPort() {
        assertThrows(BusinessException.class, () -> service.update("ios-agent-test",
            new DeviceAgentModels.UpdateAgentWdaConfigRequest(null, "XCODEBUILD", null,
                "https://example.com:4723", 8100), 7L));
        assertThrows(BusinessException.class, () -> service.update("ios-agent-test",
            new DeviceAgentModels.UpdateAgentWdaConfigRequest(null, "XCODEBUILD", null,
                "http://localhost:4723", 80), 7L));
    }

    /** 操作速度必须使用固定档位，并把派生参数写入 MySQL 后通知 Agent。 */
    @Test
    void storesFixedOperationSpeedProfileAndDispatchesReload() {
        DeviceAgentModels.AgentOperationSpeedView view = service.updateOperationSpeed(
            "ios-agent-test", new DeviceAgentModels.UpdateAgentOperationSpeedRequest("fast"), 7L);

        assertEquals("FAST", view.operationSpeed());
        assertEquals(5, view.wirelessSourcePollIntervalSeconds());
        assertEquals(24, view.wirelessSourceMaxAttempts());
        assertEquals("FAST", db.queryForObject("""
            SELECT operation_speed FROM automation_device_agent_wda_config WHERE agent_id=?
            """, String.class, "ios-agent-test"));
        verify(commandService).create(any(DeviceAgentModels.CreateCommandRequest.class), anyLong());
    }

    /** 非固定速度值不得进入系统配置。 */
    @Test
    void rejectsUnknownOperationSpeed() {
        assertThrows(BusinessException.class, () -> service.updateOperationSpeed(
            "ios-agent-test", new DeviceAgentModels.UpdateAgentOperationSpeedRequest("TURBO"), 7L));
    }

    /** 重复保存相同速度档位不得再次写库、审计或下发 UPDATE_CONFIG。 */
    @Test
    void skipsNoChangeOperationSpeedUpdate() {
        service.updateOperationSpeed("ios-agent-test",
            new DeviceAgentModels.UpdateAgentOperationSpeedRequest("FAST"), 7L);
        service.updateOperationSpeed("ios-agent-test",
            new DeviceAgentModels.UpdateAgentOperationSpeedRequest("FAST"), 7L);

        verify(commandService, times(1)).create(
            any(DeviceAgentModels.CreateCommandRequest.class), anyLong());
    }
}
