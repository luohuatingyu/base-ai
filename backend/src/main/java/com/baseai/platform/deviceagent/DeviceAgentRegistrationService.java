package com.baseai.platform.deviceagent;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 管理通用设备自动化 Agent 的配对、功能、健康和诊断状态。 */
@Service
public class DeviceAgentRegistrationService {
    private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,63}");
    private static final char[] PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<DeviceAgentModels.DiagnosticCheck>> CHECK_LIST = new TypeReference<>() {};
    private final JdbcTemplate db;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final PlatformProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 注入 MySQL、JSON、加密和 Agent 安全配置。 */
    public DeviceAgentRegistrationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                                          ObjectMapper objectMapper, ConfigCryptoService cryptoService,
                                          PlatformProperties properties) {
        this.db = mysqlJdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.properties = properties;
    }

    /** 生成可编辑且不泄漏服务端主机信息的 Agent 身份建议。 */
    public DeviceAgentModels.AgentIdentitySuggestion identitySuggestion() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return new DeviceAgentModels.AgentIdentitySuggestion("ios-agent-" + suffix, "Mac Agent " + suffix);
    }

    /** 创建或补发一次性配对码，并撤销之前仍有效的配对码。 */
    @Transactional
    public DeviceAgentModels.CreatePairingResponse createPairing(DeviceAgentModels.CreatePairingRequest request,
                                                                  Long userId, String ipAddress) {
        String agentId = normalizeAgentId(request == null ? null : request.agentId());
        List<String> features = normalizeFeatures(request.requestedFeatures());
        String backendUrl = normalizeBackendUrl(request.backendUrl());
        String deviceName = trim(request.deviceName(), 128);
        ExistingRegistration existing = findRegistrationState(agentId);
        if (existing != null && existing.revokedAt() != null) throw new BusinessException("deviceAgent.agentRevoked");
        if (existing == null) {
            db.update("""
                INSERT INTO automation_device_agent_registration
                    (agent_id, device_name, pairing_status, backend_url)
                VALUES (?, ?, 'PENDING', ?)
                """, agentId, deviceName, backendUrl);
            Integer defaults = db.queryForObject(
                "SELECT COUNT(*) FROM automation_device_agent_registration WHERE is_default=TRUE", Integer.class);
            if (defaults != null && defaults == 0) db.update(
                "UPDATE automation_device_agent_registration SET is_default=TRUE WHERE agent_id=?", agentId);
        } else {
            db.update("""
                UPDATE automation_device_agent_registration
                SET device_name=COALESCE(?, device_name), backend_url=COALESCE(?, backend_url)
                WHERE agent_id=?
                """, deviceName, backendUrl, agentId);
        }
        db.update("""
            UPDATE automation_device_agent_pairing SET revoked_at=CURRENT_TIMESTAMP(6)
            WHERE agent_id=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP(6)
            """, agentId);
        String code = generatePairingCode();
        Instant expiresAt = Instant.now().plusSeconds(pairingTtlSeconds());
        db.update("""
            INSERT INTO automation_device_agent_pairing
                (agent_id, code_hash, code_ciphertext, requested_features, expires_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """, agentId, pairingHash(code), cryptoService.encrypt(code), writeJson(features),
            Timestamp.from(expiresAt), userId);
        audit(agentId, "AGENT_PAIRING_CREATED", Map.of("features", features), userId, ipAddress);
        return new DeviceAgentModels.CreatePairingResponse(code, agentId, expiresAt, pairingTtlSeconds());
    }

    /** 分页查询配对记录，可选择包含已使用、撤销和过期记录。 */
    public DeviceAgentModels.PageResult<DeviceAgentModels.PairingCodeView> pairings(
        String agentId, boolean includeInactive, int page, int size) {
        String normalized = agentId == null || agentId.isBlank() ? null : normalizeAgentId(agentId);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(100, Math.max(1, size));
        Long total = db.queryForObject("""
            SELECT COUNT(*) FROM automation_device_agent_pairing
            WHERE (? IS NULL OR agent_id=?)
              AND (? OR (used_at IS NULL AND revoked_at IS NULL
                         AND expires_at>CURRENT_TIMESTAMP(6)))
            """, Long.class, normalized, normalized, includeInactive);
        List<DeviceAgentModels.PairingCodeView> items = db.query("""
            SELECT id, agent_id, requested_features, failed_attempts, expires_at, used_at,
                   revoked_at, created_by, created_at
            FROM automation_device_agent_pairing
            WHERE (? IS NULL OR agent_id=?)
              AND (? OR (used_at IS NULL AND revoked_at IS NULL
                         AND expires_at>CURRENT_TIMESTAMP(6)))
            ORDER BY id DESC LIMIT ? OFFSET ?
            """, (resultSet, rowNum) -> pairingView(resultSet), normalized, normalized,
            includeInactive, normalizedSize, (normalizedPage - 1) * normalizedSize);
        return new DeviceAgentModels.PageResult<>(items, total == null ? 0 : total,
            normalizedPage, normalizedSize);
    }

    /** 仅允许查看仍有效配对码的明文。 */
    public DeviceAgentModels.PairingCodeSecretView pairingSecret(Long pairingId) {
        try {
            return db.queryForObject("""
                SELECT p.agent_id, p.code_ciphertext, p.expires_at, p.used_at, p.revoked_at,
                       r.pairing_status
                FROM automation_device_agent_pairing p
                JOIN automation_device_agent_registration r ON r.agent_id=p.agent_id
                WHERE p.id=?
                """, (resultSet, rowNum) -> {
                    Instant expiresAt = instant(resultSet, "expires_at");
                    if (resultSet.getTimestamp("used_at") != null || resultSet.getTimestamp("revoked_at") != null
                        || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
                        throw new BusinessException("deviceAgent.pairingNotActive");
                    }
                    return new DeviceAgentModels.PairingCodeSecretView(
                        cryptoService.decrypt(resultSet.getString("code_ciphertext")),
                        resultSet.getString("agent_id"), "PAIRED".equals(resultSet.getString("pairing_status")),
                        expiresAt, Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds()));
                }, pairingId);
        } catch (EmptyResultDataAccessException exception) {
            throw BusinessException.notFound("deviceAgent.pairingInvalid");
        }
    }

    /** 撤销一条仍未使用的配对码。 */
    public void revokePairing(Long pairingId, Long userId, String ipAddress) {
        int changed = db.update("""
            UPDATE automation_device_agent_pairing SET revoked_at=CURRENT_TIMESTAMP(6)
            WHERE id=? AND used_at IS NULL AND revoked_at IS NULL
            """, pairingId);
        if (changed == 0) throw BusinessException.notFound("deviceAgent.pairingInvalid");
        audit(null, "AGENT_PAIRING_REVOKED", Map.of("pairingId", pairingId), userId, ipAddress);
    }

    /** 删除已使用、已撤销或已过期的配对记录。 */
    public void deletePairingRecord(Long pairingId) {
        int changed = db.update("""
            DELETE FROM automation_device_agent_pairing
            WHERE id=? AND (used_at IS NOT NULL OR revoked_at IS NOT NULL OR expires_at<=CURRENT_TIMESTAMP(6))
            """, pairingId);
        if (changed == 0) throw new BusinessException("deviceAgent.pairingNotActive");
    }

    /** 原子领取配对码并生成每 Agent 独立的高熵 HMAC Secret。 */
    @Transactional
    public DeviceAgentModels.ClaimPairingResponse claim(DeviceAgentModels.ClaimPairingRequest request) {
        String code = normalizePairingCode(request == null ? null : request.pairingCode());
        PairingRow pairing;
        try {
            pairing = db.queryForObject("""
                SELECT p.id, p.agent_id, p.requested_features, p.expires_at, p.used_at, p.revoked_at,
                       r.revoked_at AS agent_revoked_at, r.backend_url
                FROM automation_device_agent_pairing p
                JOIN automation_device_agent_registration r ON r.agent_id=p.agent_id
                WHERE p.code_hash=? FOR UPDATE
                """, (resultSet, rowNum) -> new PairingRow(resultSet.getLong("id"),
                resultSet.getString("agent_id"), readStringList(resultSet.getString("requested_features")),
                instant(resultSet, "expires_at"), instant(resultSet, "used_at"),
                instant(resultSet, "revoked_at"), instant(resultSet, "agent_revoked_at"),
                resultSet.getString("backend_url")), pairingHash(code));
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException("deviceAgent.pairingInvalid");
        }
        if (pairing == null || pairing.usedAt() != null || pairing.revokedAt() != null
            || pairing.agentRevokedAt() != null || !pairing.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException("deviceAgent.pairingInvalid");
        }
        String agentSecret = randomSecret();
        db.update("""
            UPDATE automation_device_agent_registration
            SET pairing_status='PAIRED', agent_secret_encrypted=?, revoked_at=NULL
            WHERE agent_id=?
            """, cryptoService.encrypt(agentSecret), pairing.agentId());
        db.update("UPDATE automation_device_agent_pairing SET used_at=CURRENT_TIMESTAMP(6) WHERE id=?", pairing.id());
        db.update("""
            UPDATE automation_device_agent_registration
            SET feature_diagnostics_status=?, feature_automation_status=?, feature_autostart_status=?
            WHERE agent_id=?
            """, pairing.features().contains("READ_ONLY_DIAGNOSTICS") ? "ENABLED" : "DISABLED",
            pairing.features().contains("APPIUM_WDA_AUTOMATION") ? "ENABLED" : "DISABLED",
            pairing.features().contains("AUTOSTART") ? "ENABLED" : "DISABLED", pairing.agentId());
        audit(pairing.agentId(), "AGENT_PAIRING_CLAIMED", Map.of(), null, null);
        return new DeviceAgentModels.ClaimPairingResponse(pairing.agentId(), agentSecret,
            pairing.backendUrl(), pairing.features());
    }

    /** 查询管理端 Agent 分页列表。 */
    public DeviceAgentModels.PageResult<DeviceAgentModels.AgentRegistrationView> registrations(
        int page, int size, String keyword, String status) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(100, Math.max(1, size));
        String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        String normalizedStatus = status == null || status.isBlank() ? null
            : normalizeEnum(status, List.of("PENDING", "PAIRED", "REVOKED"),
                "deviceAgent.requestInvalid");
        Long total = db.queryForObject("""
            SELECT COUNT(*) FROM automation_device_agent_registration
            WHERE (agent_id LIKE ? OR COALESCE(device_name, '') LIKE ?)
              AND (? IS NULL OR pairing_status=?)
            """, Long.class, pattern, pattern, normalizedStatus, normalizedStatus);
        List<DeviceAgentModels.AgentRegistrationView> items = db.query("""
            SELECT r.*,
                   (SELECT a.created_at FROM automation_device_agent_audit a
                    WHERE a.agent_id=r.agent_id AND a.event_type='AGENT_AUTH_FAILED'
                    ORDER BY a.id DESC LIMIT 1) AS last_auth_failure_at,
                   (SELECT JSON_UNQUOTE(JSON_EXTRACT(a.event_detail, '$.reason'))
                    FROM automation_device_agent_audit a
                    WHERE a.agent_id=r.agent_id AND a.event_type='AGENT_AUTH_FAILED'
                    ORDER BY a.id DESC LIMIT 1) AS last_auth_failure_reason
            FROM automation_device_agent_registration r
            WHERE (r.agent_id LIKE ? OR COALESCE(r.device_name, '') LIKE ?)
              AND (? IS NULL OR r.pairing_status=?)
            ORDER BY r.is_default DESC, r.id DESC LIMIT ? OFFSET ?
            """, (resultSet, rowNum) -> registrationView(resultSet), pattern, pattern,
            normalizedStatus, normalizedStatus, normalizedSize,
            (normalizedPage - 1) * normalizedSize);
        return new DeviceAgentModels.PageResult<>(items, total == null ? 0 : total,
            normalizedPage, normalizedSize);
    }

    /** 查询一个 Agent 管理视图。 */
    public DeviceAgentModels.AgentRegistrationView registration(String agentId) {
        try {
            return db.queryForObject("""
                SELECT r.*, NULL AS last_auth_failure_at, NULL AS last_auth_failure_reason
                FROM automation_device_agent_registration r WHERE r.agent_id=?
                """, (resultSet, rowNum) -> registrationView(resultSet), normalizeAgentId(agentId));
        } catch (EmptyResultDataAccessException exception) {
            throw BusinessException.notFound("deviceAgent.agentNotFound");
        }
    }

    /** 将指定已配对且未撤销 Agent 设为唯一默认实例。 */
    @Transactional
    public void setDefault(String agentId) {
        requirePaired(agentId);
        db.update("UPDATE automation_device_agent_registration SET is_default=FALSE WHERE is_default=TRUE");
        db.update("UPDATE automation_device_agent_registration SET is_default=TRUE WHERE agent_id=?",
            normalizeAgentId(agentId));
    }

    /** 更新 Agent 可读名称。 */
    public void updateDeviceName(String agentId, String deviceName) {
        requireExists(agentId);
        db.update("UPDATE automation_device_agent_registration SET device_name=? WHERE agent_id=?",
            trim(deviceName, 128), normalizeAgentId(agentId));
    }

    /** 保存回连地址并返回 Agent 当前是否可接收改址命令。 */
    public boolean updateBackendUrl(String agentId, String backendUrl) {
        ExistingRegistration existing = requireExists(agentId);
        db.update("UPDATE automation_device_agent_registration SET backend_url=? WHERE agent_id=?",
            normalizeBackendUrl(backendUrl), normalizeAgentId(agentId));
        return existing.revokedAt() == null && "PAIRED".equals(existing.pairingStatus());
    }

    /** 更新诊断、WDA 自动化和自启动功能状态。 */
    public void updateFeatures(String agentId, DeviceAgentModels.UpdateFeaturesRequest request) {
        requireExists(agentId);
        String diagnostics = featureStatus(request == null ? null : request.featureDiagnostics());
        String automation = featureStatus(request == null ? null : request.featureAutomation());
        String autostart = featureStatus(request == null ? null : request.featureAutostart());
        db.update("""
            UPDATE automation_device_agent_registration
            SET feature_diagnostics_status=?, feature_automation_status=?, feature_autostart_status=?
            WHERE agent_id=?
            """, diagnostics, automation, autostart, normalizeAgentId(agentId));
    }

    /** 撤销 Agent 并使所有待执行命令与配对码立即失效。 */
    @Transactional
    public void revoke(String agentId, String reason, Long userId, String ipAddress) {
        requireExists(agentId);
        String normalized = normalizeAgentId(agentId);
        db.update("""
            UPDATE automation_device_agent_registration
            SET pairing_status='REVOKED', revoked_at=CURRENT_TIMESTAMP(6), is_default=FALSE,
                agent_secret_encrypted=NULL WHERE agent_id=?
            """, normalized);
        db.update("""
            UPDATE automation_device_agent_command SET status='CANCELLED', completed_at=CURRENT_TIMESTAMP(6)
            WHERE agent_id=? AND status IN ('PENDING','LEASED')
            """, normalized);
        db.update("""
            UPDATE automation_device_agent_pairing SET revoked_at=CURRENT_TIMESTAMP(6)
            WHERE agent_id=? AND used_at IS NULL AND revoked_at IS NULL
            """, normalized);
        audit(normalized, "AGENT_REVOKED", Map.of("reason", reason == null ? "" : trim(reason, 500)),
            userId, ipAddress);
    }

    /** 删除 Agent 及其受外键管理的设备配置，审计记录按设计保留。 */
    public void delete(String agentId, Long userId, String ipAddress) {
        String normalized = normalizeAgentId(agentId);
        audit(normalized, "AGENT_DELETED", Map.of(), userId, ipAddress);
        if (db.update("DELETE FROM automation_device_agent_registration WHERE agent_id=?", normalized) == 0) {
            throw BusinessException.notFound("deviceAgent.agentNotFound");
        }
    }

    /** 返回 HMAC 过滤器需要的解密 Secret，撤销或未配对时不返回。 */
    public String secretForAuthentication(String agentId) {
        try {
            String encrypted = db.queryForObject("""
                SELECT agent_secret_encrypted FROM automation_device_agent_registration
                WHERE agent_id=? AND pairing_status='PAIRED' AND revoked_at IS NULL
                """, String.class, normalizeAgentId(agentId));
            return encrypted == null ? null : cryptoService.decrypt(encrypted);
        } catch (EmptyResultDataAccessException | BusinessException exception) {
            return null;
        }
    }

    /** Agent 每次成功认证后刷新在线时间，不执行任何设备控制。 */
    public void touchAuthenticated(String agentId) {
        db.update("""
            UPDATE automation_device_agent_registration SET last_online_at=CURRENT_TIMESTAMP(6)
            WHERE agent_id=? AND revoked_at IS NULL
            """, normalizeAgentId(agentId));
    }

    /** 返回 Agent 功能配置。 */
    public DeviceAgentModels.AgentConfigView agentConfig(String agentId) {
        try {
            return db.queryForObject("""
                SELECT agent_id, pairing_status, feature_diagnostics_status,
                       feature_automation_status, feature_autostart_status, last_online_at
                FROM automation_device_agent_registration
                WHERE agent_id=? AND pairing_status='PAIRED' AND revoked_at IS NULL
                """, (resultSet, rowNum) -> new DeviceAgentModels.AgentConfigView(
                resultSet.getString("agent_id"), resultSet.getString("pairing_status"),
                resultSet.getString("feature_diagnostics_status"),
                resultSet.getString("feature_automation_status"),
                resultSet.getString("feature_autostart_status"), instant(resultSet, "last_online_at")),
                normalizeAgentId(agentId));
        } catch (EmptyResultDataAccessException exception) {
            throw BusinessException.notFound("deviceAgent.agentNotFound");
        }
    }

    /** 保存 Agent 健康快照及本机可回退版本清单。 */
    @Transactional
    public void reportHealth(String agentId, DeviceAgentModels.AgentHealthRequest request) {
        requirePaired(agentId);
        if (request == null) throw new BusinessException("deviceAgent.requestInvalid");
        String status = normalizeEnum(request.status(), List.of("ONLINE", "DEGRADED", "OFFLINE"),
            "deviceAgent.requestInvalid");
        String versions = writeJson(normalizeVersions(request.availableVersions()));
        String normalized = normalizeAgentId(agentId);
        db.update("""
            UPDATE automation_device_agent_registration
            SET last_online_at=CURRENT_TIMESTAMP(6), last_agent_version=?, last_heartbeat_status=?,
                last_xcuitest_driver_version=?, available_versions=?, last_error_code=? WHERE agent_id=?
            """, trim(request.agentVersion(), 40), status, trim(request.xcuitestDriverVersion(), 40), versions,
            trim(request.lastErrorCode(), 64), normalized);
        db.update("""
            INSERT INTO automation_device_agent_state
                (agent_id, status, ios_version, last_error_code, last_heartbeat_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE status=VALUES(status), ios_version=VALUES(ios_version),
                last_error_code=VALUES(last_error_code), last_heartbeat_at=CURRENT_TIMESTAMP(6)
            """, normalized, status, trim(request.iosVersion(), 40), trim(request.lastErrorCode(), 64));
    }

    /** 保存只读诊断结果，严格限制检查项数量与状态枚举。 */
    public void reportDiagnostics(String agentId, DeviceAgentModels.AgentDiagnosticsRequest request) {
        requirePaired(agentId);
        if (request == null || request.checks() == null || request.checks().size() > 50) {
            throw new BusinessException("deviceAgent.requestInvalid");
        }
        List<DeviceAgentModels.DiagnosticCheck> checks = request.checks().stream().map(check -> {
            if (check == null) throw new BusinessException("deviceAgent.requestInvalid");
            return new DeviceAgentModels.DiagnosticCheck(
                trimRequired(check.code(), 64, "deviceAgent.requestInvalid"),
                normalizeEnum(check.status(), List.of("PASS", "WARN", "FAIL"), "deviceAgent.requestInvalid"),
                trim(check.message(), 500));
        }).toList();
        String status = normalizeEnum(request.status(), List.of("PASS", "WARN", "FAIL"),
            "deviceAgent.requestInvalid");
        db.update("""
            INSERT INTO automation_device_agent_state
                (agent_id, readiness_status, readiness_checks, last_error_code, last_diagnostics_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE readiness_status=VALUES(readiness_status),
                readiness_checks=VALUES(readiness_checks), last_error_code=VALUES(last_error_code),
                last_diagnostics_at=CURRENT_TIMESTAMP(6)
            """, normalizeAgentId(agentId), status, writeJson(checks), trim(request.errorCode(), 64));
    }

    /** 计算管理端就绪状态，心跳过期时强制显示 OFFLINE。 */
    public DeviceAgentModels.AgentReadinessView readiness(String agentId) {
        requireExists(agentId);
        try {
            return db.queryForObject("""
                SELECT status, readiness_status, readiness_checks, last_error_code,
                       last_heartbeat_at, last_diagnostics_at
                FROM automation_device_agent_state WHERE agent_id=?
                """, (resultSet, rowNum) -> {
                    Instant heartbeat = instant(resultSet, "last_heartbeat_at");
                    boolean stale = heartbeat == null || heartbeat.isBefore(Instant.now().minusSeconds(
                        Math.max(30, properties.getDeviceAgent().getHeartbeatStaleSeconds())));
                    return new DeviceAgentModels.AgentReadinessView(
                        stale ? "OFFLINE" : resultSet.getString("readiness_status"),
                        stale ? "OFFLINE" : resultSet.getString("status"), stale,
                        instant(resultSet, "last_diagnostics_at"),
                        readChecks(resultSet.getString("readiness_checks")),
                        resultSet.getString("last_error_code"));
                }, normalizeAgentId(agentId));
        } catch (EmptyResultDataAccessException exception) {
            return new DeviceAgentModels.AgentReadinessView("UNKNOWN", "OFFLINE", true,
                null, List.of(), null);
        }
    }

    /** 记录 HMAC 认证失败原因，供管理页面排障。 */
    public void recordAuthFailure(String agentId, String reason, String requestPath, String ipAddress) {
        audit(agentId, "AGENT_AUTH_FAILED", Map.of("reason", reason, "request", requestPath), null, ipAddress);
    }

    /** 校验并返回已存在注册状态。 */
    public ExistingRegistration requireExists(String agentId) {
        ExistingRegistration existing = findRegistrationState(normalizeAgentId(agentId));
        if (existing == null) throw BusinessException.notFound("deviceAgent.agentNotFound");
        return existing;
    }

    /** 校验 Agent 已配对且未撤销。 */
    public ExistingRegistration requirePaired(String agentId) {
        ExistingRegistration existing = requireExists(agentId);
        if (existing.revokedAt() != null || "REVOKED".equals(existing.pairingStatus())) {
            throw new BusinessException("deviceAgent.agentRevoked");
        }
        if (!"PAIRED".equals(existing.pairingStatus())) throw new BusinessException("deviceAgent.agentNotPaired");
        return existing;
    }

    /** 查询最小注册状态，供跨服务状态校验。 */
    private ExistingRegistration findRegistrationState(String agentId) {
        try {
            return db.queryForObject("""
                SELECT pairing_status, revoked_at FROM automation_device_agent_registration WHERE agent_id=?
                """, (resultSet, rowNum) -> new ExistingRegistration(resultSet.getString("pairing_status"),
                instant(resultSet, "revoked_at")), agentId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    /** 映射管理端 Agent 视图。 */
    private DeviceAgentModels.AgentRegistrationView registrationView(ResultSet resultSet) throws SQLException {
        return new DeviceAgentModels.AgentRegistrationView(
            resultSet.getLong("id"), resultSet.getString("agent_id"), resultSet.getString("device_name"),
            resultSet.getString("pairing_status"), instant(resultSet, "revoked_at"),
            instant(resultSet, "last_online_at"), resultSet.getString("last_agent_version"),
            resultSet.getString("last_xcuitest_driver_version"), resultSet.getString("last_heartbeat_status"),
            resultSet.getString("feature_diagnostics_status"), resultSet.getString("feature_automation_status"),
            resultSet.getString("feature_autostart_status"), instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"), instant(resultSet, "last_auth_failure_at"),
            resultSet.getString("last_auth_failure_reason"), resultSet.getBoolean("is_default"),
            readStringList(resultSet.getString("available_versions")), resultSet.getString("last_error_code"),
            resultSet.getString("backend_url"));
    }

    /** 映射配对码列表视图并动态计算状态。 */
    private DeviceAgentModels.PairingCodeView pairingView(ResultSet resultSet) throws SQLException {
        Instant expiresAt = instant(resultSet, "expires_at");
        Instant usedAt = instant(resultSet, "used_at");
        Instant revokedAt = instant(resultSet, "revoked_at");
        String status = usedAt != null ? "USED" : revokedAt != null ? "REVOKED"
            : expiresAt != null && !expiresAt.isAfter(Instant.now()) ? "EXPIRED" : "ACTIVE";
        return new DeviceAgentModels.PairingCodeView(resultSet.getLong("id"), resultSet.getString("agent_id"),
            status, readStringList(resultSet.getString("requested_features")),
            resultSet.getInt("failed_attempts"), expiresAt, usedAt, revokedAt,
            resultSet.getLong("created_by"), instant(resultSet, "created_at"));
    }

    /** 写入 JSON，序列化失败视为服务端配置错误。 */
    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("设备 Agent JSON 序列化失败", exception); }
    }

    /** 读取 JSON 字符串列表，空值兼容旧记录。 */
    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return List.copyOf(objectMapper.readValue(value, STRING_LIST)); }
        catch (Exception exception) { return List.of(); }
    }

    /** 读取诊断 JSON，损坏记录按空列表降级而不影响管理页面。 */
    private List<DeviceAgentModels.DiagnosticCheck> readChecks(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return List.copyOf(objectMapper.readValue(value, CHECK_LIST)); }
        catch (Exception exception) { return List.of(); }
    }

    /** 写入不含设备身份与命令输出的审计事件。 */
    private void audit(String agentId, String eventType, Object detail, Long userId, String ipAddress) {
        db.update("""
            INSERT INTO automation_device_agent_audit
                (agent_id, event_type, event_detail, user_id, ip_address)
            VALUES (?, ?, ?, ?, ?)
            """, agentId, eventType, writeJson(detail), userId, trim(ipAddress, 64));
    }

    /** 校验 Agent ID。 */
    private String normalizeAgentId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!AGENT_ID.matcher(normalized).matches()) throw new BusinessException("deviceAgent.agentInvalid");
        return normalized;
    }

    /** 校验设备管理功能，业务账号和任务能力不在白名单中。 */
    private List<String> normalizeFeatures(List<String> values) {
        List<String> normalized = values == null ? List.of("READ_ONLY_DIAGNOSTICS")
            : values.stream().filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!DeviceAgentModels.VALID_FEATURES.containsAll(normalized)) {
            throw new BusinessException("deviceAgent.featureInvalid");
        }
        return normalized;
    }

    /** 校验功能状态枚举。 */
    private String featureStatus(String value) {
        return normalizeEnum(value, DeviceAgentModels.VALID_FEATURE_STATUS.stream().toList(),
            "deviceAgent.featureInvalid");
    }

    /** 校验 HTTP(S) 回连地址，禁止凭据、查询串和片段。 */
    private String normalizeBackendUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || value.length() > 512) throw new IllegalArgumentException();
            return value.trim().replaceAll("/+$", "");
        } catch (RuntimeException exception) {
            throw new BusinessException("deviceAgent.requestInvalid");
        }
    }

    /** 规范有限枚举。 */
    private String normalizeEnum(String value, List<String> allowed, String messageKey) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BusinessException(messageKey);
        return normalized;
    }

    /** 规范 Agent 本机版本清单并限制最多二十项。 */
    private List<String> normalizeVersions(List<String> versions) {
        if (versions == null) return List.of();
        return versions.stream().filter(value -> value != null && !value.isBlank())
            .map(value -> trim(value, 40)).distinct().limit(20).toList();
    }

    /** 规范配对码显示分隔符和大小写。 */
    private String normalizePairingCode(String value) {
        String normalized = value == null ? "" : value.replace("-", "").replace(" ", "")
            .toUpperCase(Locale.ROOT);
        if (normalized.length() != 16) throw new BusinessException("deviceAgent.pairingInvalid");
        return normalized;
    }

    /** 生成便于人工输入的随机配对码。 */
    private String generatePairingCode() {
        StringBuilder value = new StringBuilder(19);
        for (int index = 0; index < 16; index++) {
            if (index > 0 && index % 4 == 0) value.append('-');
            value.append(PAIRING_ALPHABET[secureRandom.nextInt(PAIRING_ALPHABET.length)]);
        }
        return value.toString();
    }

    /** 生成每 Agent 独立的 256 位随机 Secret。 */
    private String randomSecret() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** 使用平台配置密钥作为 Pepper 对配对码做 HMAC。 */
    private String pairingHash(String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getConfigEncryptionKey().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalizePairingCode(code).getBytes(StandardCharsets.UTF_8)));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("设备 Agent 配对码摘要失败", exception);
        }
    }

    /** 返回配置后的配对码有效期并设置安全上下界。 */
    private int pairingTtlSeconds() {
        return Math.min(3600, Math.max(300, properties.getDeviceAgent().getPairingTtlSeconds()));
    }

    /** 截断可选文本并把空白规范为空值。 */
    private String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    /** 校验必填文本并限制长度。 */
    private String trimRequired(String value, int maxLength, String messageKey) {
        String normalized = trim(value, maxLength);
        if (normalized == null) throw new BusinessException(messageKey);
        return normalized;
    }

    /** 读取可空时间戳。 */
    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** Agent 最小注册状态。 */
    public record ExistingRegistration(String pairingStatus, Instant revokedAt) {}
    /** 配对领取事务内部快照。 */
    private record PairingRow(Long id, String agentId, List<String> features, Instant expiresAt,
                              Instant usedAt, Instant revokedAt, Instant agentRevokedAt,
                              String backendUrl) {}
}
