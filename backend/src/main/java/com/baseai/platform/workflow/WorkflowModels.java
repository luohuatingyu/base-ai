package com.baseai.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 工作流 HTTP、持久化与执行层共享的数据模型。 */
public final class WorkflowModels {
    /** 工具类不允许实例化。 */
    private WorkflowModels() {}

    public record NodeTemplateCommand(String code, String name, String nodeType, String description,
                                      JsonNode config, Boolean enabled, String source, String functionalCategory) {}
    public record NodeTemplateView(Long id, String code, String name, String nodeType, String description,
                                   JsonNode config, boolean systemTemplate, String source, String functionalCategory,
                                   boolean enabled, boolean importedTemplate, String externalKey, String externalVersion,
                                   String externalPublisher, String externalFingerprint, LocalDateTime importedAt,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record MarketplaceActionView(String externalId, String name, String description, boolean compatible,
                                        String incompatibilityReason, String targetNodeType,
                                        String functionalCategory, String compatibilityLevel, boolean imported) {}
    public record MarketplaceNodeView(String externalId, String name, String description, String version,
                                      String publisher, String marketplaceCategory, boolean compatible,
                                      String incompatibilityReason, String targetNodeType, String functionalCategory,
                                      String compatibilityLevel, List<MarketplaceActionView> actions,
                                      String probeStatus, boolean imported) {}
    public record MarketplacePage(String source, List<MarketplaceNodeView> items, int page, int pageSize, long total,
                                  boolean probePending) {}
    public record MarketplaceImportCommand(List<String> externalIds, Boolean replaceExisting) {}
    public record MarketplaceImportItem(String externalId, String status, Long templateId) {}
    public record MarketplaceImportResult(String source, List<MarketplaceImportItem> items) {}
    public record PluginComponentOption(Long id, String source, String packageKey, String packageVersion,
                                        String externalKey, String name, String componentType,
                                        JsonNode parameterSchema, JsonNode credentialSchema) {}
    public record PluginExternalService(String name, String domain) {}
    public record PluginAdmissionView(Long pluginId, String source, String packageKey, String packageVersion,
                                      String publisher, String licenseName, String licenseUrl,
                                      List<PluginExternalService> externalServices, boolean noExternalService,
                                      List<String> dataTypes, String dataNotes, String admissionStatus,
                                      String reviewNote, Long reviewedBy, LocalDateTime reviewedAt,
                                      boolean pluginEnabled) {}
    public record PluginAdmissionCommand(String licenseName, String licenseUrl,
                                         List<PluginExternalService> externalServices, Boolean noExternalService,
                                         List<String> dataTypes, String dataNotes) {}
    public record PluginAdmissionReviewCommand(Boolean approved, String reviewNote) {}
    public record PluginOAuthAuthorizeCommand(String redirectUri) {}
    public record PluginOAuthAuthorization(String authorizationUrl, String state, LocalDateTime expiresAt) {}
    public record PluginOAuthCallbackCommand(String state, String code) {}
    public record PluginOAuthCallbackResult(Long connectionId, boolean connected) {}
    public record MarketplaceTemplateDraft(String source, String externalKey, String externalVersion,
                                           String externalPublisher, String externalFingerprint, String code,
                                           String name, String description, String nodeType,
                                           String functionalCategory, JsonNode config) {}
    public record MarketplaceTemplatePersistence(Long templateId, String status) {}
    public record WorkflowCommand(String code, String name, String description, JsonNode graph,
                                  JsonNode inputSchema, Long revision) {}
    public record WorkflowView(Long id, String code, String name, String description, String status,
                               Long currentVersionId, Integer currentVersion, Long publishedVersionId,
                               Integer publishedVersion, long revision, boolean enabled, Long ownerUserId,
                               JsonNode graph, JsonNode inputSchema, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record VersionView(Long id, int versionNumber, JsonNode graph, JsonNode inputSchema,
                              LocalDateTime createdAt) {}
    public record RunCommand(Map<String, Object> inputs) {}
    public record RunAccepted(String runId, String status) {}
    public record NodeRunView(Long id, String nodeId, String nodeName, String nodeType, int sequenceNo,
                              String iterationPath, String status, JsonNode input, JsonNode output,
                              String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt) {}
    public record RunView(String id, Long workflowId, String workflowCode, int versionNumber,
                          String parentRunId, String traceId, String triggerType, String status,
                          JsonNode input, JsonNode output, String errorMessage, Long ownerUserId,
                          Long apiKeyId, boolean cancelRequested, LocalDateTime startedAt, LocalDateTime finishedAt,
                          LocalDateTime createdAt, List<NodeRunView> nodes) {}
    public record StoredVersion(Long id, Long workflowId, String workflowCode, int versionNumber,
                                JsonNode graph, JsonNode inputSchema, JsonNode templateSnapshots, Long workflowOwnerId) {}
    public record ConnectionCommand(String code, String name, String connectionType, JsonNode config, Boolean enabled) {}
    public record ConnectionView(Long id, String code, String name, String connectionType, JsonNode config,
                                 boolean enabled, Long ownerUserId, String vectorStatus, String vectorEngine,
                                 String vectorVersion, LocalDateTime vectorCheckedAt, String vectorError,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record TriggerDefinition(Long workflowId, String workflowCode, Long versionId, Long ownerUserId,
                                    String nodeId, String nodeType, JsonNode config) {}
}
