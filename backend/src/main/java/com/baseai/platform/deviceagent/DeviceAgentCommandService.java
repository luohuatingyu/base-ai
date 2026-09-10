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

/** 管理只读诊断、设备发现、改址和升级命令的租约状态。 */
@Service
public class DeviceAgentCommandService {
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

    /** 创建白名单内命令；白名单不含任何设备自动化控制动作。 */
    @Transactional
    public DeviceAgentModels.AgentCommandView create(DeviceAgentModels.CreateCommandRequest request, Long userId) {
        if (request == null) throw new BusinessException("deviceAgent.commandInvalid");
        String agentId = request.agentId() == null ? "" : request.agentId().trim();
        registrationService.requirePaired(agentId);
        String commandType = normalizeCommandType(request.commandType());
        String params = writeParams(request.commandParams());
        Integer pending = db.queryForObject("""
            SELECT COUNT(*) FROM automation_device_agent_command
            WHERE agent_id=? AND command_type=? AND status IN ('PENDING','LEASED')
            """, Integer.class, agentId, commandType);
        if (pending != null && pending > 0) throw new BusinessException(409, "deviceAgent.commandConflict");
        db.update("""
            INSERT INTO automation_device_agent_command
                (agent_id, command_type, command_params, created_by)
            VALUES (?, ?, ?, ?)
            """, agentId, commandType, params, userId == null ? 0L : userId);
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
        expireLeasesForAgent(agentId);
        List<String> capabilities = request == null || request.capabilities() == null ? List.of()
            : request.capabilities().stream().filter(DeviceAgentModels.VALID_COMMAND_TYPES::contains).distinct().toList();
        if (capabilities.isEmpty()) return null;
        List<LeaseCandidate> candidates = db.query("""
            SELECT id, command_type, command_params FROM automation_device_agent_command
            WHERE agent_id=? AND status='PENDING' ORDER BY id LIMIT 100 FOR UPDATE
            """, (resultSet, rowNum) -> new LeaseCandidate(resultSet.getLong("id"),
            resultSet.getString("command_type"), resultSet.getString("command_params")), agentId);
        LeaseCandidate candidate = candidates.stream().filter(item -> capabilities.contains(item.commandType()))
            .findFirst().orElse(null);
        if (candidate == null) return null;
        String token = leaseToken();
        Instant expiresAt = Instant.now().plusSeconds("UPGRADE".equals(candidate.commandType()) ? 1200 : 300);
        int changed = db.update("""
            UPDATE automation_device_agent_command
            SET status='LEASED', lease_token=?, lease_expires_at=?, started_at=CURRENT_TIMESTAMP(6)
            WHERE id=? AND agent_id=? AND status='PENDING'
            """, token, Timestamp.from(expiresAt), candidate.id(), agentId);
        if (changed == 0) return null;
        return new DeviceAgentModels.LeaseCommandResponse(candidate.id(), candidate.commandType(),
            readParams(candidate.params()), token, expiresAt);
    }

    /** Agent 使用绑定租约回报成功或失败终态。 */
    public void reportResult(String agentId, Long commandId,
                             DeviceAgentModels.ReportCommandResultRequest request) {
        if (request == null || request.leaseToken() == null || request.leaseToken().isBlank()) {
            throw new BusinessException("deviceAgent.commandLeaseInvalid");
        }
        String status = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("COMPLETED", "FAILED").contains(status)) {
            throw new BusinessException("deviceAgent.commandStatusInvalid");
        }
        int changed = db.update("""
            UPDATE automation_device_agent_command
            SET status=?, result_summary=?, error_code=?, completed_at=CURRENT_TIMESTAMP(6),
                lease_token=NULL, lease_expires_at=NULL
            WHERE id=? AND agent_id=? AND status='LEASED' AND lease_token=?
              AND lease_expires_at>CURRENT_TIMESTAMP(6)
            """, status, truncate(request.resultSummary(), 2000), truncate(request.errorCode(), 64),
            commandId, agentId, request.leaseToken());
        if (changed == 0) throw new BusinessException(409, "deviceAgent.commandLeaseInvalid");
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

    /** 校验命令类型严格属于只读管理和 Agent 自维护白名单。 */
    private String normalizeCommandType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!DeviceAgentModels.VALID_COMMAND_TYPES.contains(normalized)) {
            throw new BusinessException("deviceAgent.commandInvalid");
        }
        return normalized;
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
            resultSet.getString("agent_id"), resultSet.getString("command_type"),
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
    private record LeaseCandidate(Long id, String commandType, String params) {}
}
