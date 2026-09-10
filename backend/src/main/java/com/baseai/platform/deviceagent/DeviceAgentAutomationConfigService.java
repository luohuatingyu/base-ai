package com.baseai.platform.deviceagent;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 管理设备 Agent 的加密 WDA 签名参数和回环 Appium 配置。 */
@Service
public class DeviceAgentAutomationConfigService {
    private static final Pattern TEAM_ID = Pattern.compile("[A-Za-z0-9]{10}");
    private static final Pattern SIGNING_IDENTITY = Pattern.compile("[A-Za-z0-9 .:_()\\-]{1,128}");
    private static final Pattern BUNDLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{2,127}");
    private static final Set<String> LAUNCH_MODES = Set.of("XCODEBUILD", "PREINSTALLED", "URL");
    private final JdbcTemplate db;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final DeviceAgentRegistrationService registrationService;
    private final DeviceAgentCommandService commandService;

    /** 注入配置数据库、加密、注册和命令服务。 */
    public DeviceAgentAutomationConfigService(
        @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
        ObjectMapper objectMapper, ConfigCryptoService cryptoService,
        DeviceAgentRegistrationService registrationService, DeviceAgentCommandService commandService) {
        this.db = mysqlJdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.registrationService = registrationService;
        this.commandService = commandService;
    }

    /** 查询管理端或 Agent 使用的有效 WDA 配置；未配置时返回安全默认值。 */
    public DeviceAgentModels.AgentWdaConfigView get(String agentId) {
        registrationService.requireExists(agentId);
        try {
            return db.queryForObject("""
                SELECT agent_id, signing_config_encrypted, launch_mode, wda_url,
                       appium_server_url, base_wda_local_port, config_version, updated_at
                FROM automation_device_agent_wda_config WHERE agent_id=?
                """, (resultSet, rowNum) -> view(resultSet), agentId);
        } catch (EmptyResultDataAccessException exception) {
            return defaults(agentId);
        }
    }

    /** 归一化、校验并加密保存 WDA 配置，已配对 Agent 会收到热加载命令。 */
    @Transactional
    public DeviceAgentModels.AgentWdaConfigView update(
        String agentId, DeviceAgentModels.UpdateAgentWdaConfigRequest request, Long userId) {
        DeviceAgentRegistrationService.ExistingRegistration registration =
            registrationService.requireExists(agentId);
        NormalizedConfig normalized = normalize(request);
        String signingJson = writeJson(normalized.signingConfig());
        String encrypted = normalized.signingConfig() == null ? null : cryptoService.encrypt(signingJson);
        String hash = sha256(writeJson(normalized));
        db.update("""
            INSERT INTO automation_device_agent_wda_config
                (agent_id, signing_config_encrypted, launch_mode, wda_url, appium_server_url,
                 base_wda_local_port, config_version, config_hash, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
            ON DUPLICATE KEY UPDATE signing_config_encrypted=VALUES(signing_config_encrypted),
                launch_mode=VALUES(launch_mode), wda_url=VALUES(wda_url),
                appium_server_url=VALUES(appium_server_url),
                base_wda_local_port=VALUES(base_wda_local_port), config_version=config_version+1,
                config_hash=VALUES(config_hash), updated_by=VALUES(updated_by),
                updated_at=CURRENT_TIMESTAMP(6)
            """, agentId, encrypted, normalized.launchMode(), normalized.wdaUrl(),
            normalized.appiumServerUrl(), normalized.baseWdaLocalPort(), hash, userId, userId);
        audit(agentId, "AGENT_WDA_CONFIG_UPDATED", Map.of(
            "launchMode", normalized.launchMode(), "baseWdaLocalPort", normalized.baseWdaLocalPort()), userId);
        if (registration.revokedAt() == null && "PAIRED".equals(registration.pairingStatus())) {
            commandService.create(new DeviceAgentModels.CreateCommandRequest(
                agentId, "UPDATE_CONFIG", Map.of()), userId);
        }
        return get(agentId);
    }

    /** 删除 WDA 配置并通知在线 Agent 回退本机安全默认值。 */
    @Transactional
    public void delete(String agentId, Long userId) {
        DeviceAgentRegistrationService.ExistingRegistration registration =
            registrationService.requireExists(agentId);
        db.update("DELETE FROM automation_device_agent_wda_config WHERE agent_id=?", agentId);
        audit(agentId, "AGENT_WDA_CONFIG_DELETED", Map.of(), userId);
        if (registration.revokedAt() == null && "PAIRED".equals(registration.pairingStatus())) {
            commandService.create(new DeviceAgentModels.CreateCommandRequest(
                agentId, "UPDATE_CONFIG", Map.of()), userId);
        }
    }

    /** 映射数据库配置并解密只含签名元数据的 JSON。 */
    private DeviceAgentModels.AgentWdaConfigView view(ResultSet resultSet) throws SQLException {
        DeviceAgentModels.WdaSigningConfig signing = null;
        String encrypted = resultSet.getString("signing_config_encrypted");
        if (encrypted != null && !encrypted.isBlank()) {
            try {
                signing = objectMapper.readValue(cryptoService.decrypt(encrypted),
                    DeviceAgentModels.WdaSigningConfig.class);
            } catch (Exception exception) {
                throw new IllegalStateException("设备 Agent WDA 配置解密失败", exception);
            }
        }
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new DeviceAgentModels.AgentWdaConfigView(
            resultSet.getString("agent_id"), signing, resultSet.getString("launch_mode"),
            resultSet.getString("wda_url"), resultSet.getString("appium_server_url"),
            resultSet.getInt("base_wda_local_port"), resultSet.getLong("config_version"),
            updatedAt == null ? null : updatedAt.toInstant());
    }

    /** 返回不会访问远程服务的默认配置。 */
    private DeviceAgentModels.AgentWdaConfigView defaults(String agentId) {
        return new DeviceAgentModels.AgentWdaConfigView(
            agentId, null, "XCODEBUILD", null, "http://127.0.0.1:4723", 8100, 0, null);
    }

    /** 归一化并完成签名、启动模式、回环地址和端口校验。 */
    private NormalizedConfig normalize(DeviceAgentModels.UpdateAgentWdaConfigRequest request) {
        if (request == null) throw new BusinessException("deviceAgent.wdaConfigInvalid");
        DeviceAgentModels.WdaSigningConfig signing = normalizeSigning(request.signingConfig());
        String mode = optional(request.launchMode());
        mode = mode == null ? "XCODEBUILD" : mode.toUpperCase(Locale.ROOT);
        if (!LAUNCH_MODES.contains(mode)) throw new BusinessException("deviceAgent.wdaConfigInvalid");
        String wdaUrl = loopbackUrl(request.wdaUrl(), true);
        if ("URL".equals(mode) != (wdaUrl != null)) {
            throw new BusinessException("deviceAgent.wdaConfigInvalid");
        }
        String appiumUrl = loopbackUrl(request.appiumServerUrl(), false);
        if (appiumUrl == null) appiumUrl = "http://127.0.0.1:4723";
        int port = request.baseWdaLocalPort() == null ? 8100 : request.baseWdaLocalPort();
        validatePort(port);
        return new NormalizedConfig(signing, mode, wdaUrl, appiumUrl, port);
    }

    /** 校验不会包含私钥内容或命令字符的签名元数据。 */
    private DeviceAgentModels.WdaSigningConfig normalizeSigning(
        DeviceAgentModels.WdaSigningConfig value) {
        if (value == null) return null;
        String teamId = optional(value.xcodeOrgId());
        if (teamId != null) teamId = teamId.toUpperCase(Locale.ROOT);
        String identity = optional(value.xcodeSigningId());
        String bundleId = optional(value.updatedWdaBundleId());
        if (teamId != null && !TEAM_ID.matcher(teamId).matches()
            || identity != null && !SIGNING_IDENTITY.matcher(identity).matches()
            || identity != null && teamId == null
            || bundleId != null && !BUNDLE_ID.matcher(bundleId).matches()) {
            throw new BusinessException("deviceAgent.wdaConfigInvalid");
        }
        return new DeviceAgentModels.WdaSigningConfig(
            teamId, identity, bundleId,
            Boolean.TRUE.equals(value.allowProvisioningDeviceRegistration()));
    }

    /** 只允许 HTTP 回环地址，阻止 Agent 访问外部 Appium 或 WDA 服务。 */
    private String loopbackUrl(String value, boolean allowPath) {
        String normalized = optional(value);
        if (normalized == null) return null;
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(host);
            boolean safePath = allowPath || uri.getPath() == null || uri.getPath().isBlank()
                || "/".equals(uri.getPath());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback || !safePath
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || normalized.length() > 256) {
                throw new IllegalArgumentException();
            }
            return normalized.replaceAll("/+$", "");
        } catch (RuntimeException exception) {
            throw new BusinessException("deviceAgent.wdaConfigInvalid");
        }
    }

    /** 校验非特权本地端口。 */
    private void validatePort(int port) {
        if (port < 1024 || port > 65535) throw new BusinessException("deviceAgent.wdaConfigInvalid");
    }

    /** 将空白字符串收敛为空值。 */
    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 将配置序列化为稳定 JSON。 */
    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("设备 Agent WDA 配置序列化失败", exception); }
    }

    /** 计算配置变更摘要。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("设备 Agent WDA 配置摘要失败", exception);
        }
    }

    /** 写入不包含签名身份和 URL 的安全审计摘要。 */
    private void audit(String agentId, String eventType, Object detail, Long userId) {
        db.update("""
            INSERT INTO automation_device_agent_audit
                (agent_id, event_type, event_detail, user_id)
            VALUES (?, ?, ?, ?)
            """, agentId, eventType, writeJson(detail), userId);
    }

    /** 归一化后的 WDA 配置。 */
    private record NormalizedConfig(DeviceAgentModels.WdaSigningConfig signingConfig,
                                    String launchMode, String wdaUrl, String appiumServerUrl,
                                    int baseWdaLocalPort) {}
}
