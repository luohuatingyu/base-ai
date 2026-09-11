package com.baseai.platform.deviceagent;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 管理 IDA 自动化命令租约，设备状态仍写入历史 WDA 列。 */
@Service
public class DeviceAgentCommandService {
    private static final Pattern DEVICE_ID = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> DEVICE_COMMANDS = Set.of("SETUP_IDA", "START_IDA");
    private static final String UPGRADE_COMMAND = "UPGRADE";
    private final JdbcTemplate db;
    private final ObjectMapper objectMapper;
    private final DeviceAgentRegistrationService registrationService;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 注入命令持久化与 Agent 注册校验依赖。 */
    public DeviceAgentCommandService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                                     ObjectMapper objectMapper,
                                     DeviceAgentRegistrationService registrationService) {
        this.db = mysqlJdbcTemplate;
        this.objectMapper = objectMapper;
        this.registrationService = registrationService;
    }

    /** 创建白名单内命令，并校验单设备命令只能投递给当前 Agent 的在线设备。 */
    @Transactional
    public DeviceAgentModels.AgentCommandView create(DeviceAgentModels.CreateCommandRequest request, Long userId) {
        if (request == null) throw new BusinessException("deviceAgent.commandInvalid");
        String agentId = request.agentId() == null ? "" : request.agentId().trim();
        registrationService.requirePaired(agentId);
        String commandType = normalizeCommandType(request.commandType());
        String targetDeviceId = normalizeTargetDevice(request.targetDeviceId(), commandType);
        validateTargetDevice(agentId, targetDeviceId, commandType);
        String params = writeParams(request.commandParams());
        lockAgent(agentId);
        expireLeasesForAgent(agentId);
        if (UPGRADE_COMMAND.equals(commandType)) {
            if (hasActiveLease(agentId)) {
                throw new BusinessException(409, "deviceAgent.upgradeAgentBusy");
            }
        } else if (hasActiveUpgrade(agentId)) {
            throw new BusinessException(409, "deviceAgent.upgradeInProgress");
        }
        List<Long> active = db.query("""
            SELECT id FROM automation_device_agent_command
            WHERE agent_id=? AND command_type=?
              AND ((target_device_id=? ) OR (target_device_id IS NULL AND ? IS NULL))
              AND status IN ('PENDING','LEASED') ORDER BY id LIMIT 1
            """, (resultSet, rowNum) -> resultSet.getLong("id"), agentId, commandType,
            targetDeviceId, targetDeviceId);
        if (!active.isEmpty()) {
            if ("UPDATE_CONFIG".equals(commandType)) return get(active.get(0));
            throw new BusinessException(409, "deviceAgent.commandConflict");
        }
        db.update("""
            INSERT INTO automation_device_agent_command
                (agent_id, target_device_id, command_type, command_params, created_by)
            VALUES (?, ?, ?, ?, ?)
            """, agentId, targetDeviceId, commandType, params, userId == null ? 0L : userId);
        Long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null || id == 0) id = db.queryForObject(
            "SELECT MAX(id) FROM automation_device_agent_command WHERE agent_id=?", Long.class, agentId);
        return get(id);
    }

    /** Agent 原子领取一条能力匹配的命令。 */
    @Transactional
    public DeviceAgentModels.LeaseCommandResponse lease(String agentId,
                                                         DeviceAgentModels.LeaseCommandRequest request) {
        registrationService.requirePaired(agentId);
        lockAgent(agentId);
        expireLeasesForAgent(agentId);
        if (hasLeasedUpgrade(agentId)) return null;
        List<String> capabilities = request == null || request.capabilities() == null ? List.of()
            : request.capabilities().stream().filter(DeviceAgentModels.VALID_COMMAND_TYPES::contains).distinct().toList();
        if (capabilities.isEmpty()) return null;
        List<LeaseCandidate> candidates = db.query("""
            SELECT id, target_device_id, command_type, command_params FROM automation_device_agent_command
            WHERE agent_id=? AND status='PENDING'
            ORDER BY CASE WHEN command_type='UPGRADE' THEN 0 ELSE 1 END, id
            LIMIT 100 FOR UPDATE
            """, (resultSet, rowNum) -> new LeaseCandidate(resultSet.getLong("id"),
            resultSet.getString("target_device_id"),
            resultSet.getString("command_type"), resultSet.getString("command_params")), agentId);
        LeaseCandidate pendingUpgrade = candidates.stream()
            .filter(item -> UPGRADE_COMMAND.equals(item.commandType())).findFirst().orElse(null);
        LeaseCandidate candidate = pendingUpgrade == null
            ? candidates.stream().filter(item -> capabilities.contains(item.commandType())).findFirst().orElse(null)
            : capabilities.contains(UPGRADE_COMMAND) ? pendingUpgrade : null;
        if (candidate == null) return null;
        String token = leaseToken();
        Instant expiresAt = Instant.now().plusSeconds(leaseSeconds(candidate.commandType()));
        int changed = db.update("""
            UPDATE automation_device_agent_command
            SET status='LEASED', lease_token=?, lease_expires_at=?, started_at=CURRENT_TIMESTAMP(6)
            WHERE id=? AND agent_id=? AND status='PENDING'
            """, token, Timestamp.from(expiresAt), candidate.id(), agentId);
        if (changed == 0) return null;
        return new DeviceAgentModels.LeaseCommandResponse(candidate.id(), candidate.targetDeviceId(),
            candidate.commandType(),
            readParams(candidate.params()), token, expiresAt);
    }

    /** Agent 使用绑定租约回报成功或失败终态。 */
    @Transactional
    public void reportResult(String agentId, Long commandId,
                             DeviceAgentModels.ReportCommandResultRequest request) {
        if (request == null || request.leaseToken() == null || request.leaseToken().isBlank()) {
            throw new BusinessException("deviceAgent.commandLeaseInvalid");
        }
        String status = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("COMPLETED", "FAILED").contains(status)) {
            throw new BusinessException("deviceAgent.commandStatusInvalid");
        }
        CommandTarget command = commandTarget(agentId, commandId, request.leaseToken());
        int changed = db.update("""
            UPDATE automation_device_agent_command
            SET status=?, result_summary=?, error_code=?, completed_at=CURRENT_TIMESTAMP(6),
                lease_token=NULL, lease_expires_at=NULL
            WHERE id=? AND agent_id=? AND status='LEASED' AND lease_token=?
              AND lease_expires_at>CURRENT_TIMESTAMP(6)
            """, status, truncate(request.resultSummary(), 2000), truncate(request.errorCode(), 64),
            commandId, agentId, request.leaseToken());
        if (changed == 0) throw new BusinessException(409, "deviceAgent.commandLeaseInvalid");
        updateDeviceIdaState(command, status, request.errorCode());
    }

    /** 管理端取消尚未结束的命令。 */
    public void cancel(Long commandId) {
        int changed = db.update("""
            UPDATE automation_device_agent_command
            SET status='CANCELLED', completed_at=CURRENT_TIMESTAMP(6), lease_token=NULL, lease_expires_at=NULL
            WHERE id=? AND status IN ('PENDING','LEASED')
            """, commandId);
        if (changed == 0) throw new BusinessException(409, "deviceAgent.commandNotFound");
    }

    /** 查询单条命令。 */
    public DeviceAgentModels.AgentCommandView get(Long commandId) {
        try {
            return db.queryForObject("SELECT * FROM automation_device_agent_command WHERE id=?",
                (resultSet, rowNum) -> commandView(resultSet), commandId);
        } catch (EmptyResultDataAccessException exception) {
            throw BusinessException.notFound("deviceAgent.commandNotFound");
        }
    }

    /** 查询 Agent 最近两百条管理命令。 */
    public List<DeviceAgentModels.AgentCommandView> list(String agentId) {
        registrationService.requireExists(agentId);
        return db.query("""
            SELECT * FROM automation_device_agent_command WHERE agent_id=? ORDER BY id DESC LIMIT 200
            """, (resultSet, rowNum) -> commandView(resultSet), agentId);
    }

    /** 定时回收全部过期租约，避免 Agent 崩溃后命令永久卡住。 */
    @Scheduled(fixedDelay = 30000)
    public void expireLeases() {
        db.update("""
            UPDATE automation_device_agent_command
            SET status='EXPIRED', completed_at=CURRENT_TIMESTAMP(6), lease_token=NULL
            WHERE status='LEASED' AND lease_expires_at<=CURRENT_TIMESTAMP(6)
            """);
    }

    /** 回收指定 Agent 的过期租约。 */
    private void expireLeasesForAgent(String agentId) {
        db.update("""
            UPDATE automation_device_agent_command
            SET status='EXPIRED', completed_at=CURRENT_TIMESTAMP(6), lease_token=NULL
            WHERE agent_id=? AND status='LEASED' AND lease_expires_at<=CURRENT_TIMESTAMP(6)
            """, agentId);
    }

    /** 锁定 Agent 注册行，串行化升级创建与命令领取的状态判断。 */
    private void lockAgent(String agentId) {
        db.queryForObject("""
            SELECT id FROM automation_device_agent_registration WHERE agent_id=? FOR UPDATE
            """, Long.class, agentId);
    }

    /** 判断 Agent 是否已经有一条仍在执行期内的命令租约。 */
    private boolean hasActiveLease(String agentId) {
        Integer count = db.queryForObject("""
            SELECT COUNT(*) FROM automation_device_agent_command
            WHERE agent_id=? AND status='LEASED' AND lease_expires_at>CURRENT_TIMESTAMP(6)
            """, Integer.class, agentId);
        return count != null && count > 0;
    }

    /** 判断 Agent 是否处于升级排队或执行阶段。 */
    private boolean hasActiveUpgrade(String agentId) {
        Integer count = db.queryForObject("""
            SELECT COUNT(*) FROM automation_device_agent_command
            WHERE agent_id=? AND command_type='UPGRADE' AND status IN ('PENDING','LEASED')
            """, Integer.class, agentId);
        return count != null && count > 0;
    }

    /** 判断升级命令是否已被领取，执行期间不得继续派发其他命令。 */
    private boolean hasLeasedUpgrade(String agentId) {
        Integer count = db.queryForObject("""
            SELECT COUNT(*) FROM automation_device_agent_command
            WHERE agent_id=? AND command_type='UPGRADE' AND status='LEASED'
            """, Integer.class, agentId);
        return count != null && count > 0;
    }

    /** 校验命令类型严格属于设备自动化和 Agent 自维护白名单。 */
    private String normalizeCommandType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!DeviceAgentModels.VALID_COMMAND_TYPES.contains(normalized)) {
            throw new BusinessException("deviceAgent.commandInvalid");
        }
        return normalized;
    }

    /** 规范设备级命令目标，主机级命令禁止夹带设备标识。 */
    private String normalizeTargetDevice(String value, String commandType) {
        String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (DEVICE_COMMANDS.contains(commandType)) {
            if (normalized == null || !DEVICE_ID.matcher(normalized).matches()) {
                throw new BusinessException("deviceAgent.commandTargetInvalid");
            }
            return normalized;
        }
        if (normalized != null && !normalized.isBlank()) {
            throw new BusinessException("deviceAgent.commandTargetInvalid");
        }
        return null;
    }

    /** 校验目标设备归属、在线状态和启动前 IDA 就绪状态。 */
    private void validateTargetDevice(String agentId, String deviceId, String commandType) {
        if (deviceId == null) return;
        List<DeviceTargetState> states = db.query("""
            SELECT connected, wda_status FROM automation_device_agent_device
            WHERE agent_id=? AND device_id=?
            """, (resultSet, rowNum) -> new DeviceTargetState(
            resultSet.getBoolean("connected"), resultSet.getString("wda_status")), agentId, deviceId);
        if (states.isEmpty()) throw new BusinessException("deviceAgent.deviceNotFound");
        DeviceTargetState state = states.get(0);
        if (!state.connected()) throw new BusinessException(409, "deviceAgent.deviceOffline");
        if ("START_IDA".equals(commandType) && !"READY".equals(state.idaStatus())) {
            throw new BusinessException(409, "deviceAgent.idaNotReady");
        }
    }

    /** 查询绑定租约对应的命令目标，避免失败响应篡改其他设备。 */
    private CommandTarget commandTarget(String agentId, Long commandId, String leaseToken) {
        List<CommandTarget> commands = db.query("""
            SELECT agent_id, command_type, target_device_id FROM automation_device_agent_command
            WHERE id=? AND agent_id=? AND status='LEASED' AND lease_token=?
              AND lease_expires_at>CURRENT_TIMESTAMP(6)
            """, (resultSet, rowNum) -> new CommandTarget(resultSet.getString("agent_id"),
            resultSet.getString("command_type"), resultSet.getString("target_device_id")),
            commandId, agentId, leaseToken);
        if (commands.isEmpty()) throw new BusinessException(409, "deviceAgent.commandLeaseInvalid");
        return commands.get(0);
    }

    /** 根据 IDA 命令终态回写设备状态，供管理端展示真实执行结果。 */
    private void updateDeviceIdaState(CommandTarget command, String status, String errorCode) {
        if (command.targetDeviceId() == null || !DEVICE_COMMANDS.contains(command.commandType())) return;
        boolean success = "COMPLETED".equals(status);
        String idaStatus = success ? "READY" : "ERROR";
        boolean running = success && "START_IDA".equals(command.commandType());
        db.update("""
            UPDATE automation_device_agent_device
            SET wda_status=?, wda_running=?, wda_port_error_code=?, last_error_code=?
            WHERE agent_id=? AND device_id=?
            """, idaStatus, running, success ? null : truncate(errorCode, 64),
            success ? null : truncate(errorCode, 64), command.agentId(), command.targetDeviceId());
    }

    /** 返回不同命令的合理租约时间，安装和升级允许较长执行时间。 */
    private long leaseSeconds(String commandType) {
        return switch (commandType) {
            case "UPGRADE", "SETUP_IDA" -> 1200;
            case "START_IDA", "REGISTRY_RECREATE" -> 600;
            default -> 300;
        };
    }

    /** 序列化并限制命令参数大小。 */
    private String writeParams(Object params) {
        try {
            String value = objectMapper.writeValueAsString(params == null ? Map.of() : params);
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768) {
                throw new BusinessException("deviceAgent.commandInvalid");
            }
            return value;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("deviceAgent.commandInvalid");
        }
    }

    /** 读取命令 JSON 参数。 */
    private Object readParams(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return objectMapper.readValue(value, Object.class); }
        catch (Exception exception) { return Map.of(); }
    }

    /** 映射命令视图。 */
    private DeviceAgentModels.AgentCommandView commandView(ResultSet resultSet) throws SQLException {
        return new DeviceAgentModels.AgentCommandView(resultSet.getLong("id"),
            resultSet.getString("agent_id"), resultSet.getString("target_device_id"),
            resultSet.getString("command_type"),
            readParams(resultSet.getString("command_params")), resultSet.getString("status"),
            resultSet.getString("result_summary"), resultSet.getString("error_code"),
            instant(resultSet, "lease_expires_at"), instant(resultSet, "started_at"),
            instant(resultSet, "completed_at"), instant(resultSet, "created_at"));
    }

    /** 生成不可预测命令租约令牌。 */
    private String leaseToken() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** 截断 Agent 返回文本，避免放大日志和响应。 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    /** 读取可空时间戳。 */
    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 候选租约数据库快照。 */
    private record LeaseCandidate(Long id, String targetDeviceId, String commandType, String params) {}
    /** 设备目标当前状态。 */
    private record DeviceTargetState(boolean connected, String idaStatus) {}
    /** 已租赁命令的设备目标。 */
    private record CommandTarget(String agentId, String commandType, String targetDeviceId) {}
}
