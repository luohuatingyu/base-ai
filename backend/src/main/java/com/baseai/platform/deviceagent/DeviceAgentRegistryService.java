package com.baseai.platform.deviceagent;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 管理 Remote XPC Registry 的端口、期望状态和脱敏运行状态。 */
@Service
public class DeviceAgentRegistryService {
    private static final Set<String> ACTIONS = Set.of("ONLINE", "OFFLINE", "RECREATE");
    private static final Set<String> OBSERVED_STATES = Set.of(
        "NOT_INSTALLED", "OFFLINE", "STARTING", "ONLINE", "RESTARTING", "ERROR", "STALE");
    private static final List<String> COMMAND_TYPES = List.of(
        "REGISTRY_ONLINE", "REGISTRY_OFFLINE", "REGISTRY_RECREATE");
    private static final Duration STATUS_TTL = Duration.ofMinutes(3);
    private final JdbcTemplate db;
    private final ObjectMapper objectMapper;
    private final PlatformProperties properties;
    private final DeviceAgentRegistrationService registrationService;
    private final DeviceAgentCommandService commandService;

    /** 注入数据库、属性、注册与命令依赖。 */
    public DeviceAgentRegistryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                                      ObjectMapper objectMapper, PlatformProperties properties,
                                      DeviceAgentRegistrationService registrationService,
                                      DeviceAgentCommandService commandService) {
        this.db = mysqlJdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.registrationService = registrationService;
        this.commandService = commandService;
    }

    /** 查询管理端 Registry 配置并把过期在线状态显示为 STALE。 */
    public DeviceAgentModels.AgentRegistryView get(String agentId) {
        RegistryRow row = row(agentId);
        String observed = row.observedState();
        if (row.lastReportedAt() != null && Set.of("STARTING", "ONLINE", "RESTARTING").contains(observed)
            && row.lastReportedAt().isBefore(Instant.now().minus(STATUS_TTL))) observed = "STALE";
        int defaultPort = defaultPort();
        return new DeviceAgentModels.AgentRegistryView(
            agentId, defaultPort, row.portOverride(), effectivePort(row.portOverride(), defaultPort),
            row.desiredState(), observed, row.observedPort(), row.tunnelCount(), row.helperVersion(),
            row.lastErrorCode(), row.configVersion(), row.lastReportedAt());
    }

    /** 查询 Agent 协调特权 Helper 所需的固定配置。 */
    public DeviceAgentModels.AgentRegistryConfigView agentConfig(String agentId) {
        RegistryRow row = row(agentId);
        int defaultPort = defaultPort();
        return new DeviceAgentModels.AgentRegistryConfigView(
            agentId, defaultPort, row.portOverride(), effectivePort(row.portOverride(), defaultPort),
            row.desiredState(), row.configVersion());
    }

    /** 更新每 Agent 的端口覆盖值；空值恢复全局默认端口。 */
    @Transactional
    public DeviceAgentModels.AgentRegistryView update(
        String agentId, DeviceAgentModels.UpdateAgentRegistryRequest request, Long userId) {
        RegistryRow current = row(agentId);
        Integer override = request == null ? null : request.portOverride();
        validatePort(override);
        if (!java.util.Objects.equals(current.portOverride(), override)) {
            db.update("""
                UPDATE automation_device_agent_registry
                SET port_override=?, config_version=config_version+1, updated_by=? WHERE agent_id=?
                """, override, userId, agentId);
            audit(agentId, "AGENT_REGISTRY_CONFIG_UPDATED", Map.of(
                "portOverride", override == null ? "DEFAULT" : override), userId);
        }
        return get(agentId);
    }

    /** 下发固定的上线、下线或重建动作，不接受任何命令行参数。 */
    @Transactional
    public DeviceAgentModels.AgentCommandView action(
        String agentId, DeviceAgentModels.AgentRegistryActionRequest request, Long userId) {
        String action = request == null || request.action() == null ? ""
            : request.action().trim().toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) throw new BusinessException("deviceAgent.registryActionInvalid");
        RegistryRow current = row(agentId);
        if ("RECREATE".equals(action) && !"ONLINE".equals(current.desiredState())) {
            throw new BusinessException(409, "deviceAgent.registryRecreateRequiresOnline");
        }
        List<ActiveCommand> active = db.query("""
            SELECT id, command_type, status FROM automation_device_agent_command
            WHERE agent_id=? AND command_type IN (?, ?, ?) AND status IN ('PENDING','LEASED')
            ORDER BY id
            """, (resultSet, rowNum) -> new ActiveCommand(resultSet.getLong("id"),
            resultSet.getString("command_type"), resultSet.getString("status")), agentId,
            COMMAND_TYPES.get(0), COMMAND_TYPES.get(1), COMMAND_TYPES.get(2));
        ActiveCommand leased = active.stream().filter(item -> "LEASED".equals(item.status())).findFirst().orElse(null);
        if (leased != null) throw new BusinessException(409, "deviceAgent.registryCommandConflict");
        String type = "REGISTRY_" + action;
        ActiveCommand same = active.stream().filter(item -> type.equals(item.type())).findFirst().orElse(null);
        if (same != null) return commandService.get(same.id());
        for (ActiveCommand pending : active) commandService.cancel(pending.id());
        if (!"RECREATE".equals(action)) db.update("""
            UPDATE automation_device_agent_registry
            SET desired_state=?, config_version=config_version+1, updated_by=? WHERE agent_id=?
            """, action, userId, agentId);
        DeviceAgentModels.AgentCommandView command = commandService.create(
            new DeviceAgentModels.CreateCommandRequest(agentId, type, Map.of("action", action)), userId);
        audit(agentId, "AGENT_REGISTRY_ACTION_REQUESTED",
            Map.of("action", action, "commandId", command.id()), userId);
        return command;
    }

    /** 接收认证 Agent 上报的脱敏状态。 */
    public void report(String agentId, DeviceAgentModels.AgentRegistryStatusRequest request) {
        row(agentId);
        String state = request == null || request.state() == null ? ""
            : request.state().trim().toUpperCase(Locale.ROOT);
        if (!OBSERVED_STATES.contains(state)) throw new BusinessException("deviceAgent.registryStateInvalid");
        Integer port = request.effectivePort();
        validatePort(port);
        int tunnels = request.tunnelCount() == null ? 0 : request.tunnelCount();
        if (tunnels < 0 || tunnels > 100) throw new BusinessException("deviceAgent.registryStateInvalid");
        db.update("""
            UPDATE automation_device_agent_registry
            SET observed_state=?, observed_port=?, tunnel_count=?, helper_version=?, last_error_code=?,
                last_reported_at=CURRENT_TIMESTAMP(6) WHERE agent_id=?
            """, state, port, tunnels, token(request.helperVersion()), token(request.errorCode()), agentId);
    }

    /** 创建缺失的 Registry 状态行并锁定读取。 */
    private RegistryRow row(String agentId) {
        registrationService.requireExists(agentId);
        db.update("""
            INSERT INTO automation_device_agent_registry (agent_id, desired_state, observed_state)
            VALUES (?, 'OFFLINE', 'NOT_INSTALLED')
            ON DUPLICATE KEY UPDATE agent_id=VALUES(agent_id)
            """, agentId);
        return db.queryForObject("SELECT * FROM automation_device_agent_registry WHERE agent_id=?",
            (resultSet, rowNum) -> registryRow(resultSet), agentId);
    }

    /** 映射 Registry 数据库行。 */
    private RegistryRow registryRow(ResultSet resultSet) throws SQLException {
        Timestamp reported = resultSet.getTimestamp("last_reported_at");
        return new RegistryRow((Integer) resultSet.getObject("port_override"),
            resultSet.getString("desired_state"), resultSet.getString("observed_state"),
            (Integer) resultSet.getObject("observed_port"), resultSet.getInt("tunnel_count"),
            resultSet.getString("helper_version"), resultSet.getString("last_error_code"),
            resultSet.getLong("config_version"), reported == null ? null : reported.toInstant());
    }

    /** 返回配置的合法 Registry 默认端口。 */
    private int defaultPort() {
        int value = properties.getDeviceAgent().getRegistryDefaultPort();
        return value >= 1024 && value <= 65535 ? value : 42314;
    }

    /** 计算端口覆盖后的最终端口。 */
    private int effectivePort(Integer override, int defaultPort) {
        return override == null ? defaultPort : override;
    }

    /** 校验端口边界，空值表示不报告或使用默认值。 */
    private void validatePort(Integer value) {
        if (value != null && (value < 1024 || value > 65535)) {
            throw new BusinessException("deviceAgent.registryPortInvalid");
        }
    }

    /** 只接受有限长度 ASCII 状态标记。 */
    private String token(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Za-z0-9._+-]{1,64}") ? normalized : null;
    }

    /** 写入不包含设备标识或日志的 Registry 审计事件。 */
    private void audit(String agentId, String eventType, Object detail, Long userId) {
        try {
            db.update("""
                INSERT INTO automation_device_agent_audit
                    (agent_id, event_type, event_detail, user_id)
                VALUES (?, ?, ?, ?)
                """, agentId, eventType, objectMapper.writeValueAsString(detail), userId);
        } catch (Exception exception) {
            throw new IllegalStateException("设备 Agent Registry 审计写入失败", exception);
        }
    }

    /** 未结束 Registry 命令。 */
    private record ActiveCommand(Long id, String type, String status) {}
    /** Registry 数据库快照。 */
    private record RegistryRow(Integer portOverride, String desiredState, String observedState,
                               Integer observedPort, int tunnelCount, String helperVersion,
                               String lastErrorCode, long configVersion, Instant lastReportedAt) {}
}
