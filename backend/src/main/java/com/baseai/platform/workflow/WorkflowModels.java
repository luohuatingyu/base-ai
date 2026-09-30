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
                                   boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt, JsonNode localization) {}
    public record WorkflowCommand(String code, String name, String description, JsonNode graph,
                                  JsonNode inputSchema, Long revision) {}
    public record WorkflowView(Long id, String code, String name, String description, String status,
                               Long currentVersionId, Integer currentVersion, Long publishedVersionId,
                               Integer publishedVersion, long revision, boolean enabled, Long ownerUserId,
                               JsonNode graph, JsonNode inputSchema, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    /** 聊天 Agent 可选择的已发布工作流工具，不返回画布或敏感连接配置。 */
    public record ChatToolOption(String code, String name, String description, JsonNode inputSchema) {}
    public record VersionView(Long id, int versionNumber, JsonNode graph, JsonNode inputSchema,
                              LocalDateTime createdAt) {}
    public record RunCommand(Map<String, Object> inputs) {}
    public record RunAccepted(String runId, String status) {}
    public record NodeRunView(Long id, String nodeId, String nodeName, String nodeType, int sequenceNo,
                              String iterationPath, String status, JsonNode input, JsonNode output,
                              String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt,
                              String defaultNodeName, JsonNode localization) {}
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
                                 LocalDateTime lastTestAt, Boolean lastTestOk, Integer lastTestLatencyMs,
                                 JsonNode lastTestInfo, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record TriggerDefinition(Long workflowId, String workflowCode, Long versionId, Long ownerUserId,
                                    String nodeId, String nodeType, JsonNode config) {}
}
