package com.baseai.platform.deviceagent;

import com.baseai.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 持久化 Agent 只读上报的匿名 iOS 设备池。 */
@Service
public class DeviceAgentDeviceService {
    private static final Pattern DEVICE_ID = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> DEVICE_STATUS = Set.of("AVAILABLE", "OFFLINE", "ERROR");
    private static final Set<String> CONNECTION_TYPES = Set.of("USB", "WIRELESS", "UNKNOWN");
    private final JdbcTemplate db;
    private final DeviceAgentRegistrationService registrationService;
    private final DeviceAgentCommandService commandService;

    /** 注入 MySQL、Agent 注册和命令服务。 */
    public DeviceAgentDeviceService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                                    DeviceAgentRegistrationService registrationService,
                                    DeviceAgentCommandService commandService) {
        this.db = mysqlJdbcTemplate;
        this.registrationService = registrationService;
        this.commandService = commandService;
    }

    /** 用 Agent 的完整只读快照同步设备池，本次缺失设备统一标记离线。 */
    @Transactional
    public void synchronize(String agentId, DeviceAgentModels.AgentDeviceInventoryRequest request) {
        registrationService.requirePaired(agentId);
        List<DeviceAgentModels.AgentDeviceReport> devices = request == null || request.devices() == null
            ? List.of() : request.devices();
        if (devices.size() > 100) throw new BusinessException("deviceAgent.deviceInvalid");
        Set<String> uniqueIds = new HashSet<>();
        List<NormalizedDevice> normalized = devices.stream().map(this::normalize).toList();
        if (normalized.stream().anyMatch(device -> !uniqueIds.add(device.deviceId()))) {
            throw new BusinessException("deviceAgent.deviceInvalid");
        }
        db.update("""
            UPDATE automation_device_agent_device SET connected=FALSE, status='OFFLINE' WHERE agent_id=?
            """, agentId);
        for (NormalizedDevice device : normalized) upsert(agentId, device);
    }

    /** 查询一个 Agent 的设备池，已连接设备优先展示。 */
    public List<DeviceAgentModels.AgentDeviceView> list(String agentId) {
        registrationService.requireExists(agentId);
        return db.query("""
            SELECT * FROM automation_device_agent_device
            WHERE agent_id=? ORDER BY connected DESC, device_name, device_id
            """, (resultSet, rowNum) -> deviceView(resultSet), agentId);
    }

    /** 为管理端去重创建一次只读设备检测命令。 */
    public DeviceAgentModels.AgentCommandView detect(String agentId, Long userId) {
        return commandService.create(new DeviceAgentModels.CreateCommandRequest(
            agentId, "DETECT_DEVICE", java.util.Map.of()), userId);
    }

    /** 插入或更新单设备只读元数据。 */
    private void upsert(String agentId, NormalizedDevice device) {
        db.update("""
            INSERT INTO automation_device_agent_device
                (agent_id, device_id, device_name, model, platform, os_version, connected,
                 connection_type, status, last_error_code, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE device_name=VALUES(device_name), model=VALUES(model),
                platform=VALUES(platform), os_version=VALUES(os_version), connected=VALUES(connected),
                connection_type=VALUES(connection_type), status=VALUES(status),
                last_error_code=VALUES(last_error_code), last_seen_at=CURRENT_TIMESTAMP(6)
            """, agentId, device.deviceId(), device.deviceName(), device.model(), device.platform(),
            device.osVersion(), device.connected(), device.connectionType(), device.status(),
            device.lastErrorCode());
    }

    /** 规范并校验一台设备的只读上报数据。 */
    private NormalizedDevice normalize(DeviceAgentModels.AgentDeviceReport report) {
        if (report == null) throw new BusinessException("deviceAgent.deviceInvalid");
        String deviceId = normalizeDeviceId(report.deviceId());
        String platform = optional(report.platform(), 20, "iOS");
        if (!"iOS".equalsIgnoreCase(platform)) throw new BusinessException("deviceAgent.deviceInvalid");
        String connectionType = optional(report.connectionType(), 16, "UNKNOWN").toUpperCase(Locale.ROOT);
        if (!CONNECTION_TYPES.contains(connectionType)) throw new BusinessException("deviceAgent.deviceInvalid");
        String status = optional(report.status(), 32,
            Boolean.TRUE.equals(report.connected()) ? "AVAILABLE" : "OFFLINE").toUpperCase(Locale.ROOT);
        if (!DEVICE_STATUS.contains(status)) throw new BusinessException("deviceAgent.deviceInvalid");
        boolean connected = Boolean.TRUE.equals(report.connected());
        if (!connected) status = "OFFLINE";
        return new NormalizedDevice(deviceId, optional(report.deviceName(), 128, "iOS Device"),
            optional(report.model(), 80, null), "iOS", optional(report.osVersion(), 40, null),
            connected, connectionType, status, optional(report.lastErrorCode(), 64, null));
    }

    /** 校验设备匿名标识必须是小写 SHA-256 摘要。 */
    private String normalizeDeviceId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!DEVICE_ID.matcher(normalized).matches()) throw new BusinessException("deviceAgent.deviceInvalid");
        return normalized;
    }

    /** 规范可选短文本。 */
    private String optional(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new BusinessException("deviceAgent.deviceInvalid");
        return normalized;
    }

    /** 映射管理端设备视图。 */
    private DeviceAgentModels.AgentDeviceView deviceView(ResultSet resultSet) throws SQLException {
        return new DeviceAgentModels.AgentDeviceView(resultSet.getString("agent_id"),
            resultSet.getString("device_id"), resultSet.getString("device_name"),
            resultSet.getString("model"), resultSet.getString("platform"),
            resultSet.getString("os_version"), resultSet.getBoolean("connected"),
            resultSet.getString("connection_type"), resultSet.getString("status"),
            resultSet.getString("last_error_code"), instant(resultSet, "last_seen_at"));
    }

    /** 读取可空时间戳。 */
    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 规范后的设备上报快照。 */
    private record NormalizedDevice(String deviceId, String deviceName, String model, String platform,
                                    String osVersion, boolean connected, String connectionType,
                                    String status, String lastErrorCode) {}
}
