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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用 MySQL 兼容 H2 验证 Agent 配对和独立凭据持久化。 */
class DeviceAgentRegistrationServiceTest {
    private DeviceAgentRegistrationService service;
    private JdbcTemplate db;

    /** 建立每测试独立数据库和最小真实表结构。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:device-agent-registration-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(dataSource);
        createSchema();
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        service = new DeviceAgentRegistrationService(db, new ObjectMapper(),
            new ConfigCryptoService(properties), properties);
    }

    /** 一次性配对码只能领取一次，Secret 独立生成且数据库不保存明文。 */
    @Test
    void pairingCanBeClaimedOnlyOnceAndStoresEncryptedSecret() {
        DeviceAgentModels.CreatePairingResponse pairing = service.createPairing(
            new DeviceAgentModels.CreatePairingRequest("ios-agent-test", List.of("READ_ONLY_DIAGNOSTICS"),
                "https://base.example.com", "Test Mac"), 7L, "127.0.0.1");

        DeviceAgentModels.ClaimPairingResponse claimed = service.claim(
            new DeviceAgentModels.ClaimPairingRequest(pairing.pairingCode()));
        String stored = db.queryForObject("""
            SELECT agent_secret_encrypted FROM automation_device_agent_registration WHERE agent_id=?
            """, String.class, "ios-agent-test");

        assertEquals("ios-agent-test", claimed.agentId());
        assertTrue(claimed.agentSecret().length() >= 32);
        assertFalse(stored.contains(claimed.agentSecret()));
        assertNotEquals(pairing.pairingCode(), stored);
        assertThrows(BusinessException.class, () -> service.claim(
            new DeviceAgentModels.ClaimPairingRequest(pairing.pairingCode())));
    }

    /** 通用 WDA 自动化能力可配对，企业微信业务能力必须被拒绝。 */
    @Test
    void pairingAcceptsAutomationAndRejectsBusinessFeature() {
        DeviceAgentModels.CreatePairingResponse pairing = service.createPairing(
            new DeviceAgentModels.CreatePairingRequest("ios-agent-agent",
                List.of("APPIUM_WDA_AUTOMATION"), null, "Test Mac"), 7L, "127.0.0.1");
        service.claim(new DeviceAgentModels.ClaimPairingRequest(pairing.pairingCode()));

        assertEquals("ENABLED", db.queryForObject("""
            SELECT feature_automation_status FROM automation_device_agent_registration WHERE agent_id=?
            """, String.class, "ios-agent-agent"));
        BusinessException exception = assertThrows(BusinessException.class, () -> service.createPairing(
            new DeviceAgentModels.CreatePairingRequest("ios-agent-test", List.of("WECOM_ACCOUNT"),
                null, "Test Mac"), 7L, "127.0.0.1"));

        assertEquals("deviceAgent.featureInvalid", exception.getMessageKey());
    }

    /** 配对记录支持按 Agent 分页，并可显式包含撤销记录。 */
    @Test
    void pairingHistorySupportsFilteringPaginationAndInactiveRecords() {
        service.createPairing(new DeviceAgentModels.CreatePairingRequest(
            "ios-agent-one", List.of("READ_ONLY_DIAGNOSTICS"), null, "One"), 7L, "127.0.0.1");
        service.createPairing(new DeviceAgentModels.CreatePairingRequest(
            "ios-agent-two", List.of("READ_ONLY_DIAGNOSTICS"), null, "Two"), 7L, "127.0.0.1");
        Long firstId = db.queryForObject("""
            SELECT id FROM automation_device_agent_pairing WHERE agent_id='ios-agent-one'
            """, Long.class);
        service.revokePairing(firstId, 7L, "127.0.0.1");

        DeviceAgentModels.PageResult<DeviceAgentModels.PairingCodeView> active =
            service.pairings(null, false, 1, 10);
        DeviceAgentModels.PageResult<DeviceAgentModels.PairingCodeView> firstPage =
            service.pairings(null, true, 1, 1);
        DeviceAgentModels.PageResult<DeviceAgentModels.PairingCodeView> filtered =
            service.pairings("ios-agent-one", true, 1, 10);

        assertEquals(1, active.total());
        assertEquals("ios-agent-two", active.items().get(0).agentId());
        assertEquals(2, firstPage.total());
        assertEquals(1, firstPage.items().size());
        assertEquals(1, filtered.total());
        assertEquals("REVOKED", filtered.items().get(0).status());
    }

    /** Agent 状态筛选仅接受配对生命周期中的固定值。 */
    @Test
    void registrationStatusFilterRejectsUnknownValue() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.registrations(1, 10, null, "ONLINE"));
        assertEquals("deviceAgent.requestInvalid", exception.getMessageKey());
    }

    /** 创建测试所需的 MySQL 兼容表。 */
    private void createSchema() {
        db.execute("""
            CREATE TABLE automation_device_agent_registration (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64) UNIQUE NOT NULL,
              device_name VARCHAR(128), pairing_status VARCHAR(16) DEFAULT 'PENDING',
              agent_secret_encrypted TEXT, backend_url VARCHAR(512),
              feature_diagnostics_status VARCHAR(16) DEFAULT 'ENABLED',
              feature_automation_status VARCHAR(16) DEFAULT 'DISABLED',
              feature_autostart_status VARCHAR(16) DEFAULT 'DISABLED', last_online_at TIMESTAMP,
              last_agent_version VARCHAR(40), last_xcuitest_driver_version VARCHAR(40),
              last_heartbeat_status VARCHAR(16), available_versions JSON,
              last_error_code VARCHAR(64), is_default BOOLEAN DEFAULT FALSE, revoked_at TIMESTAMP,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_pairing (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), code_hash CHAR(64) UNIQUE,
              code_ciphertext TEXT, requested_features VARCHAR(1000), failed_attempts INT DEFAULT 0,
              expires_at TIMESTAMP, used_at TIMESTAMP, revoked_at TIMESTAMP, created_by BIGINT,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_audit (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), event_type VARCHAR(64),
              event_detail JSON, user_id BIGINT, ip_address VARCHAR(64),
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_state (
              agent_id VARCHAR(64) PRIMARY KEY, status VARCHAR(16), ios_version VARCHAR(40),
              readiness_status VARCHAR(16), readiness_checks JSON, last_error_code VARCHAR(64),
              last_diagnostics_at TIMESTAMP, last_heartbeat_at TIMESTAMP, updated_at TIMESTAMP)
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
    }
}
