package com.baseai.platform.deviceagent;

import com.baseai.platform.common.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证匿名设备池完整快照、去重和离线回收。 */
class DeviceAgentDeviceServiceTest {
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);
    private DeviceAgentDeviceService service;

    /** 建立 MySQL 兼容设备表并模拟已配对 Agent。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:device-agent-device-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(dataSource);
        db.execute("""
            CREATE TABLE automation_device_agent_device (
              agent_id VARCHAR(64), device_id CHAR(64), device_name VARCHAR(128), model VARCHAR(80),
              platform VARCHAR(20), os_version VARCHAR(40), connected BOOLEAN, connection_type VARCHAR(16),
              status VARCHAR(32), wda_status VARCHAR(16), wda_running BOOLEAN, wda_local_port INT,
              observed_wda_local_port INT, wda_port_error_code VARCHAR(64), last_error_code VARCHAR(64),
              last_seen_at TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (agent_id, device_id), UNIQUE (agent_id, wda_local_port))
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_wda_config (
              agent_id VARCHAR(64) PRIMARY KEY, base_wda_local_port INT)
            """);
        db.execute("""
            CREATE TABLE automation_device_agent_audit (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64), event_type VARCHAR(64),
              event_detail JSON, user_id BIGINT)
            """);
        DeviceAgentRegistrationService registration = mock(DeviceAgentRegistrationService.class);
        when(registration.requirePaired(anyString())).thenReturn(
            new DeviceAgentRegistrationService.ExistingRegistration("PAIRED", null));
        service = new DeviceAgentDeviceService(db, registration, mock(DeviceAgentCommandService.class));
    }

    /** 多设备快照应保存匿名摘要，并在空快照后全部转为离线。 */
    @Test
    void synchronizesMultipleDevicesAndMarksMissingDevicesOffline() {
        service.synchronize("ios-agent-test", new DeviceAgentModels.AgentDeviceInventoryRequest(List.of(
            report(FIRST, "Phone A", true), report(SECOND, "Phone B", true))));

        List<DeviceAgentModels.AgentDeviceView> online = service.list("ios-agent-test");
        assertEquals(2, online.size());
        assertEquals(FIRST, online.get(0).deviceId());
        assertFalse(online.get(0).deviceId().contains("000081"));

        service.synchronize("ios-agent-test", new DeviceAgentModels.AgentDeviceInventoryRequest(List.of()));

        assertFalse(service.list("ios-agent-test").get(0).connected());
        assertEquals("OFFLINE", service.list("ios-agent-test").get(0).status());
    }

    /** 同一完整快照中的重复设备摘要应整体拒绝。 */
    @Test
    void rejectsDuplicateDeviceIds() {
        assertThrows(BusinessException.class, () -> service.synchronize("ios-agent-test",
            new DeviceAgentModels.AgentDeviceInventoryRequest(List.of(
                report(FIRST, "Phone A", true), report(FIRST, "Phone B", true)))));
    }

    /** 多设备同步必须从基准端口开始稳定分配互不冲突的 IDA 端口。 */
    @Test
    void assignsStableUniqueIdaPortsAndReturnsAssignments() {
        DeviceAgentModels.AgentDeviceInventoryResponse first = service.synchronize("ios-agent-test",
            new DeviceAgentModels.AgentDeviceInventoryRequest(List.of(
                report(FIRST, "Phone A", true), report(SECOND, "Phone B", true))));
        DeviceAgentModels.AgentDeviceInventoryResponse second = service.synchronize("ios-agent-test",
            new DeviceAgentModels.AgentDeviceInventoryRequest(List.of(
                report(FIRST, "Phone A", true), report(SECOND, "Phone B", true))));

        assertEquals(2, first.devices().size());
        assertNotEquals(first.devices().get(0).idaLocalPort(), first.devices().get(1).idaLocalPort());
        assertEquals(first.devices(), second.devices());
    }

    /** 管理端不能把两台设备配置到相同 IDA 端口。 */
    @Test
    void rejectsConflictingManagedPort() {
        service.synchronize("ios-agent-test", new DeviceAgentModels.AgentDeviceInventoryRequest(List.of(
            report(FIRST, "Phone A", true), report(SECOND, "Phone B", true))));
        Integer occupied = service.list("ios-agent-test").stream()
            .filter(device -> FIRST.equals(device.deviceId())).findFirst().orElseThrow().idaLocalPort();

        BusinessException exception = assertThrows(BusinessException.class, () -> service.updateIdaPort(
            "ios-agent-test", SECOND,
            new DeviceAgentModels.UpdateAgentDeviceIdaPortRequest(occupied), 7L));
        assertEquals("deviceAgent.idaPortConflict", exception.getMessageKey());
    }

    /** 构造不含原始 UDID 和控制状态的设备上报。 */
    private DeviceAgentModels.AgentDeviceReport report(String id, String name, boolean connected) {
        return new DeviceAgentModels.AgentDeviceReport(id, name, "iPhone", "iOS", "18.0",
            connected, "USB", connected ? "AVAILABLE" : "OFFLINE", null);
    }
}
