package com.baseai.platform.deviceagent;

import com.baseai.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
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

/** 协调设备 IDA 端口，持久化使用历史 WDA 列以兼容存量设备数据。 */
@Service
public class DeviceAgentDeviceService {
    private static final Pattern DEVICE_ID = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> DEVICE_STATUS = Set.of("AVAILABLE", "BUSY", "OFFLINE", "ERROR");
    private static final Set<String> IDA_STATUS = Set.of("UNKNOWN", "READY", "MISSING", "ERROR");
    private static final Set<String> CONNECTION_TYPES = Set.of("USB", "WIRELESS", "NETWORK", "UNKNOWN");
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

    /** 用 Agent 完整快照同步设备池、分配唯一端口并返回有效期望配置。 */
    @Transactional
    public DeviceAgentModels.AgentDeviceInventoryResponse synchronize(
        String agentId, DeviceAgentModels.AgentDeviceInventoryRequest request) {
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
            UPDATE automation_device_agent_device
            SET connected=FALSE, status='OFFLINE', wda_running=FALSE WHERE agent_id=?
            """, agentId);
        for (NormalizedDevice device : normalized) upsert(agentId, device);
        assignMissingPorts(agentId, normalized);
        List<DeviceAgentModels.AgentDevicePortAssignment> assignments = normalized.stream()
            .map(device -> new DeviceAgentModels.AgentDevicePortAssignment(device.deviceId(),
                desiredPort(agentId, device.deviceId())))
            .toList();
        return new DeviceAgentModels.AgentDeviceInventoryResponse(assignments);
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

    /** 更新单设备 IDA 期望端口，数据库唯一约束阻止同 Agent 端口冲突。 */
    @Transactional
    public DeviceAgentModels.AgentDeviceView updateIdaPort(
        String agentId, String deviceId, DeviceAgentModels.UpdateAgentDeviceIdaPortRequest request, Long userId) {
        registrationService.requireExists(agentId);
        String normalizedDeviceId = normalizeDeviceId(deviceId);
        Integer port = request == null ? null : request.idaLocalPort();
        validatePort(port);
        try {
            int changed = db.update("""
                UPDATE automation_device_agent_device
                SET wda_local_port=?, wda_port_error_code=NULL
                WHERE agent_id=? AND device_id=?
                """, port, agentId, normalizedDeviceId);
            if (changed == 0) throw BusinessException.notFound("deviceAgent.deviceNotFound");
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "deviceAgent.idaPortConflict");
        }
        audit(agentId, "AGENT_DEVICE_IDA_PORT_UPDATED", normalizedDeviceId, port, userId);
        if (port == null) assignMissingPorts(agentId, List.of(deviceSnapshot(agentId, normalizedDeviceId)));
        return find(agentId, normalizedDeviceId);
    }

    /** 插入或更新单设备只读元数据。 */
    private void upsert(String agentId, NormalizedDevice device) {
        db.update("""
            INSERT INTO automation_device_agent_device
                (agent_id, device_id, device_name, model, platform, os_version, connected,
                 connection_type, status, wda_status, wda_running, observed_wda_local_port,
                 wda_port_error_code, last_error_code, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE device_name=VALUES(device_name), model=VALUES(model),
                platform=VALUES(platform), os_version=VALUES(os_version), connected=VALUES(connected),
                connection_type=VALUES(connection_type), status=VALUES(status),
                wda_status=VALUES(wda_status), wda_running=VALUES(wda_running),
                observed_wda_local_port=VALUES(observed_wda_local_port),
                wda_port_error_code=VALUES(wda_port_error_code),
                last_error_code=VALUES(last_error_code), last_seen_at=CURRENT_TIMESTAMP(6)
            """, agentId, device.deviceId(), device.deviceName(), device.model(), device.platform(),
            device.osVersion(), device.connected(), device.connectionType(), device.status(),
            device.idaStatus(), device.idaRunning(), device.observedIdaLocalPort(),
            device.idaPortErrorCode(), device.lastErrorCode());
    }

    /** 规范并校验一台设备的只读上报数据。 */
    private NormalizedDevice normalize(DeviceAgentModels.AgentDeviceReport report) {
        if (report == null) throw new BusinessException("deviceAgent.deviceInvalid");
        String deviceId = normalizeDeviceId(report.deviceId());
        String platform = optional(report.platform(), 20, "iOS");
        if (!Set.of("IOS", "IPADOS").contains(platform.toUpperCase(Locale.ROOT))) {
            throw new BusinessException("deviceAgent.deviceInvalid");
        }
        String connectionType = optional(report.connectionType(), 16, "UNKNOWN").toUpperCase(Locale.ROOT);
        if (!CONNECTION_TYPES.contains(connectionType)) throw new BusinessException("deviceAgent.deviceInvalid");
        String status = optional(report.status(), 32,
            Boolean.TRUE.equals(report.connected()) ? "AVAILABLE" : "OFFLINE").toUpperCase(Locale.ROOT);
        if (!DEVICE_STATUS.contains(status)) throw new BusinessException("deviceAgent.deviceInvalid");
        boolean connected = Boolean.TRUE.equals(report.connected());
        if (!connected) status = "OFFLINE";
        String idaStatus = optional(report.idaStatus(), 16, "UNKNOWN").toUpperCase(Locale.ROOT);
        if (!IDA_STATUS.contains(idaStatus)) throw new BusinessException("deviceAgent.deviceInvalid");
        Integer observedPort = report.idaLocalPort();
        validatePort(observedPort);
        boolean idaRunning = connected && Boolean.TRUE.equals(report.idaRunning());
        return new NormalizedDevice(deviceId, optional(report.deviceName(), 128, "iOS Device"),
            optional(report.model(), 80, null), platform.equalsIgnoreCase("iPadOS") ? "iPadOS" : "iOS",
            optional(report.osVersion(), 40, null), connected, connectionType, status, idaStatus,
            idaRunning, observedPort, optional(report.idaPortErrorCode(), 64, null),
            optional(report.lastErrorCode(), 64, null));
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

    /** 为新设备从配置基准端口开始分配尚未占用的端口。 */
    private void assignMissingPorts(String agentId, List<NormalizedDevice> devices) {
        Set<Integer> used = new HashSet<>(db.query("""
            SELECT wda_local_port FROM automation_device_agent_device
            WHERE agent_id=? AND wda_local_port IS NOT NULL
            """, (resultSet, rowNum) -> resultSet.getInt("wda_local_port"), agentId));
        int candidate = basePort(agentId);
        for (NormalizedDevice device : devices) {
            if (desiredPort(agentId, device.deviceId()) != null) continue;
            while (used.contains(candidate) && candidate <= 65535) candidate++;
            if (candidate > 65535) throw new BusinessException("deviceAgent.idaPortUnavailable");
            db.update("""
                UPDATE automation_device_agent_device SET wda_local_port=?
                WHERE agent_id=? AND device_id=? AND wda_local_port IS NULL
                """, candidate, agentId, device.deviceId());
            used.add(candidate++);
        }
        reconcileObservedPorts(agentId, devices);
    }

    /** 标记 Agent 实际端口与管理端期望不一致的设备。 */
    private void reconcileObservedPorts(String agentId, List<NormalizedDevice> devices) {
        Set<Integer> observed = new HashSet<>();
        for (NormalizedDevice device : devices) {
            Integer desired = desiredPort(agentId, device.deviceId());
            Integer actual = device.observedIdaLocalPort();
            String error = device.idaPortErrorCode();
            if (actual != null && !observed.add(actual)) error = "IDA_PORT_COLLISION";
            else if (actual != null && !actual.equals(desired)) error = "IDA_PORT_MISMATCH";
            if (error != null) db.update("""
                UPDATE automation_device_agent_device
                SET wda_status='ERROR', wda_running=FALSE, wda_port_error_code=?
                WHERE agent_id=? AND device_id=?
                """, error, agentId, device.deviceId());
        }
    }

    /** 查询 Agent 配置的 IDA 基准端口，未配置时使用安全默认值。 */
    private int basePort(String agentId) {
        List<Integer> values = db.query("""
            SELECT base_wda_local_port FROM automation_device_agent_wda_config WHERE agent_id=?
            """, (resultSet, rowNum) -> resultSet.getInt("base_wda_local_port"), agentId);
        return values.isEmpty() ? 8100 : values.get(0);
    }

    /** 查询单设备当前期望 IDA 端口。 */
    private Integer desiredPort(String agentId, String deviceId) {
        List<Integer> values = db.query("""
            SELECT wda_local_port FROM automation_device_agent_device
            WHERE agent_id=? AND device_id=? AND wda_local_port IS NOT NULL
            """, (resultSet, rowNum) -> resultSet.getInt("wda_local_port"), agentId, deviceId);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 查询已存在设备并构造端口重分配所需最小快照。 */
    private NormalizedDevice deviceSnapshot(String agentId, String deviceId) {
        List<NormalizedDevice> devices = db.query("""
            SELECT * FROM automation_device_agent_device WHERE agent_id=? AND device_id=?
            """, (resultSet, rowNum) -> new NormalizedDevice(deviceId,
            resultSet.getString("device_name"), resultSet.getString("model"),
            resultSet.getString("platform"), resultSet.getString("os_version"),
            resultSet.getBoolean("connected"), resultSet.getString("connection_type"),
            resultSet.getString("status"), resultSet.getString("wda_status"),
            resultSet.getBoolean("wda_running"),
            (Integer) resultSet.getObject("observed_wda_local_port"),
            resultSet.getString("wda_port_error_code"), resultSet.getString("last_error_code")),
            agentId, deviceId);
        if (devices.isEmpty()) throw BusinessException.notFound("deviceAgent.deviceNotFound");
        return devices.get(0);
    }

    /** 查询单设备管理视图。 */
    private DeviceAgentModels.AgentDeviceView find(String agentId, String deviceId) {
        List<DeviceAgentModels.AgentDeviceView> devices = db.query("""
            SELECT * FROM automation_device_agent_device WHERE agent_id=? AND device_id=?
            """, (resultSet, rowNum) -> deviceView(resultSet), agentId, deviceId);
        if (devices.isEmpty()) throw BusinessException.notFound("deviceAgent.deviceNotFound");
        return devices.get(0);
    }

    /** 校验 IDA 端口位于非特权有效范围内。 */
    private void validatePort(Integer value) {
        if (value != null && (value < 1024 || value > 65535)) {
            throw new BusinessException("deviceAgent.idaPortInvalid");
        }
    }

    /** 写入不包含设备名称或原始 UDID 的端口变更审计。 */
    private void audit(String agentId, String eventType, String deviceId, Integer port, Long userId) {
        db.update("""
            INSERT INTO automation_device_agent_audit
                (agent_id, event_type, event_detail, user_id)
            VALUES (?, ?, JSON_OBJECT('deviceId', ?, 'idaLocalPort', ?), ?)
            """, agentId, eventType, deviceId, port, userId);
    }

    /** 映射管理端设备视图。 */
    private DeviceAgentModels.AgentDeviceView deviceView(ResultSet resultSet) throws SQLException {
        return new DeviceAgentModels.AgentDeviceView(resultSet.getString("agent_id"),
            resultSet.getString("device_id"), resultSet.getString("device_name"),
            resultSet.getString("model"), resultSet.getString("platform"),
            resultSet.getString("os_version"), resultSet.getBoolean("connected"),
            resultSet.getString("connection_type"), resultSet.getString("status"),
            resultSet.getString("wda_status"), resultSet.getBoolean("wda_running"),
            (Integer) resultSet.getObject("wda_local_port"),
            (Integer) resultSet.getObject("observed_wda_local_port"),
            resultSet.getString("wda_port_error_code"), resultSet.getString("last_error_code"),
            instant(resultSet, "last_seen_at"));
    }

    /** 读取可空时间戳。 */
    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 规范后的设备上报快照。 */
    private record NormalizedDevice(String deviceId, String deviceName, String model, String platform,
                                    String osVersion, boolean connected, String connectionType,
                                    String status, String idaStatus, boolean idaRunning,
                                    Integer observedIdaLocalPort, String idaPortErrorCode,
                                    String lastErrorCode) {}
}
