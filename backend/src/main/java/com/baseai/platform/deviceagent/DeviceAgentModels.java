package com.baseai.platform.deviceagent;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 通用 iOS 设备自动化 Agent 协议模型。 */
public final class DeviceAgentModels {
    public static final Set<String> VALID_FEATURES = Set.of(
        "READ_ONLY_DIAGNOSTICS", "APPIUM_WDA_AUTOMATION", "AUTOSTART");
    public static final Set<String> VALID_FEATURE_STATUS = Set.of("DISABLED", "ENABLED", "FAILED");
    public static final Set<String> VALID_COMMAND_TYPES = Set.of(
        "DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING", "DETECT_DEVICE",
        "SETUP_WDA", "START_WDA", "REGISTRY_ONLINE", "REGISTRY_OFFLINE", "REGISTRY_RECREATE",
        "UPGRADE", "UPDATE_BACKEND_URL");

    private DeviceAgentModels() {}

    /** 创建或补发配对码的管理请求。 */
    public record CreatePairingRequest(String agentId, List<String> requestedFeatures,
                                       String backendUrl, String deviceName) {}
    /** 管理页面使用的 Agent 身份建议。 */
    public record AgentIdentitySuggestion(String agentId, String deviceName) {}
    /** 新建配对码结果。 */
    public record CreatePairingResponse(String pairingCode, String agentId, Instant expiresAt, int ttlSeconds) {}
    /** Agent 首次领取配对码请求。 */
    public record ClaimPairingRequest(String pairingCode) {}
    /** Agent 首次领取配对码结果，Secret 只返回一次。 */
    public record ClaimPairingResponse(String agentId, String agentSecret, String backendUrl,
                                       List<String> requestedFeatures) {}
    /** 管理端配对码元数据，不返回哈希或明文。 */
    public record PairingCodeView(Long id, String agentId, String status, List<String> requestedFeatures,
                                  int failedAttempts, Instant expiresAt, Instant usedAt,
                                  Instant revokedAt, Long createdBy, Instant createdAt) {}
    /** 管理员主动查看仍有效配对码的结果。 */
    public record PairingCodeSecretView(String pairingCode, String agentId, boolean agentPaired,
                                        Instant expiresAt, long expiresInSeconds) {}
    /** 管理端设备 Agent 汇总视图。 */
    public record AgentRegistrationView(Long id, String agentId, String deviceName, String pairingStatus,
                                        Instant revokedAt, Instant lastOnlineAt, String lastAgentVersion,
                                        String lastXcuitestDriverVersion, String lastHeartbeatStatus,
                                        String featureDiagnostics, String featureAutomation,
                                        String featureAutostart, Instant createdAt, Instant updatedAt,
                                        Instant lastAuthFailureAt, String lastAuthFailureReason,
                                        boolean isDefault, List<String> availableVersions,
                                        String lastErrorCode, String backendUrl) {}
    /** 分页查询结果。 */
    public record PageResult<T>(List<T> items, long total, int page, int size) {}
    /** 管理端更新可读设备名称。 */
    public record UpdateAgentDeviceNameRequest(String deviceName) {}
    /** 管理端更新 Agent 回连地址。 */
    public record UpdateAgentBackendUrlRequest(String backendUrl) {}
    /** 回连地址更新结果。 */
    public record UpdateAgentBackendUrlResult(String effectiveUrl, boolean dispatched, Long commandId) {}
    /** 管理端更新诊断、WDA 自动化和自启动开关。 */
    public record UpdateFeaturesRequest(String featureDiagnostics, String featureAutomation,
                                        String featureAutostart) {}
    /** 管理端撤销 Agent 请求。 */
    public record RevokeAgentRequest(String reason) {}
    /** Agent 拉取的功能配置。 */
    public record AgentConfigView(String agentId, String pairingStatus, String featureDiagnostics,
                                  String featureAutomation, String featureAutostart,
                                  Instant lastOnlineAt) {}
    /** 管理端创建主机级或设备级维护命令。 */
    public record CreateCommandRequest(String agentId, String targetDeviceId,
                                       String commandType, Object commandParams) {
        /** 兼容主机级命令调用方。 */
        public CreateCommandRequest(String agentId, String commandType, Object commandParams) {
            this(agentId, null, commandType, commandParams);
        }
    }
    /** Agent 命令完整视图。 */
    public record AgentCommandView(Long id, String agentId, String targetDeviceId,
                                   String commandType, Object commandParams,
                                   String status, String resultSummary, String errorCode,
                                   Instant leaseExpiresAt, Instant startedAt,
                                   Instant completedAt, Instant createdAt) {}
    /** Agent 声明当前能够执行的命令类型。 */
    public record LeaseCommandRequest(List<String> capabilities) {}
    /** Agent 领取的短期命令租约。 */
    public record LeaseCommandResponse(Long commandId, String targetDeviceId,
                                       String commandType, Object commandParams,
                                       String leaseToken, Instant leaseExpiresAt) {}
    /** Agent 回报命令终态。 */
    public record ReportCommandResultRequest(String leaseToken, String status,
                                             String resultSummary, String errorCode) {}
    /** Agent 周期健康上报。 */
    public record AgentHealthRequest(String status, String agentVersion, String iosVersion,
                                     String xcuitestDriverVersion, String lastErrorCode,
                                     List<String> availableVersions) {}
    /** 单项只读诊断结果。 */
    public record DiagnosticCheck(String code, String status, String message) {}
    /** Agent 只读诊断上报。 */
    public record AgentDiagnosticsRequest(String status, List<DiagnosticCheck> checks, String errorCode) {}
    /** 管理端展示的 Agent 就绪状态。 */
    public record AgentReadinessView(String status, String agentStatus, boolean stale,
                                     Instant checkedAt, List<DiagnosticCheck> checks,
                                     String lastErrorCode) {}
    /** Agent 上报的一台匿名 iOS 设备，deviceId 必须是不可逆摘要。 */
    public record AgentDeviceReport(String deviceId, String deviceName, String model, String platform,
                                    String osVersion, Boolean connected, String connectionType,
                                    String status, String wdaStatus, Boolean wdaRunning,
                                    Integer wdaLocalPort, String wdaPortErrorCode,
                                    String lastErrorCode) {
        /** 兼容旧版只读 Agent 上报。 */
        public AgentDeviceReport(String deviceId, String deviceName, String model, String platform,
                                 String osVersion, Boolean connected, String connectionType,
                                 String status, String lastErrorCode) {
            this(deviceId, deviceName, model, platform, osVersion, connected, connectionType,
                status, "UNKNOWN", false, null, null, lastErrorCode);
        }
    }
    /** 一次完整设备池快照。 */
    public record AgentDeviceInventoryRequest(List<AgentDeviceReport> devices) {}
    /** 设备同步后返回的页面端口配置。 */
    public record AgentDeviceInventoryResponse(List<AgentDevicePortAssignment> devices) {}
    /** 一台设备应采用的 WDA 本地端口。 */
    public record AgentDevicePortAssignment(String deviceId, Integer wdaLocalPort) {}
    /** 管理端更新设备 WDA 端口。 */
    public record UpdateAgentDeviceWdaPortRequest(Integer wdaLocalPort) {}
    /** 管理端设备池视图。 */
    public record AgentDeviceView(String agentId, String deviceId, String deviceName, String model,
                                  String platform, String osVersion, boolean connected,
                                  String connectionType, String status, String wdaStatus,
                                  boolean wdaRunning, Integer wdaLocalPort,
                                  Integer observedWdaLocalPort, String wdaPortErrorCode,
                                  String lastErrorCode, Instant lastSeenAt) {}
    /** WDA 签名参数，私钥和证书内容不进入协议。 */
    public record WdaSigningConfig(String xcodeOrgId, String signingIdentity,
                                   String updatedByBundleId, Boolean allowDeviceRegistration) {}
    /** 管理端和 Agent 共同使用的 WDA 配置。 */
    public record AgentWdaConfigView(String agentId, WdaSigningConfig signingConfig,
                                     String launchMode, String wdaUrl, String appiumServerUrl,
                                     Integer baseWdaLocalPort, long configVersion,
                                     Instant updatedAt) {}
    /** 更新 WDA 配置请求。 */
    public record UpdateAgentWdaConfigRequest(WdaSigningConfig signingConfig, String launchMode,
                                              String wdaUrl, String appiumServerUrl,
                                              Integer baseWdaLocalPort) {}
    /** Agent 拉取的 Registry 有效配置。 */
    public record AgentRegistryConfigView(String agentId, int defaultPort, Integer portOverride,
                                          int effectivePort, String desiredState,
                                          long configVersion) {}
    /** 管理端 Registry 配置与脱敏运行状态。 */
    public record AgentRegistryView(String agentId, int defaultPort, Integer portOverride,
                                    int effectivePort, String desiredState, String observedState,
                                    Integer observedPort, int tunnelCount, String helperVersion,
                                    String lastErrorCode, long configVersion,
                                    Instant lastReportedAt) {}
    /** 更新 Registry 端口覆盖值。 */
    public record UpdateAgentRegistryRequest(Integer portOverride) {}
    /** Registry 固定生命周期动作。 */
    public record AgentRegistryActionRequest(String action) {}
    /** Agent 上报的 Registry 脱敏状态。 */
    public record AgentRegistryStatusRequest(String state, Integer effectivePort,
                                             Integer tunnelCount, String helperVersion,
                                             String errorCode) {}
}
