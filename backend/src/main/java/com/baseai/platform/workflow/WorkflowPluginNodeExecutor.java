package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 执行固定版本市场插件组件，并在发送凭据前重新确认数据库身份与 Schema。 */
@Component
public class WorkflowPluginNodeExecutor implements WorkflowNodeExecutor {
    private static final Set<String> TYPES = Set.of("PLUGIN_ACTION", "PLUGIN_TRIGGER", "PLUGIN_MODEL",
        "PLUGIN_DATASOURCE", "PLUGIN_AGENT_STRATEGY", "PLUGIN_EXTENSION");
    private final ObjectMapper objectMapper;
    private final WorkflowExpressionService expressions;
    private final WorkflowPluginRegistryService registry;
    private final WorkflowPluginWorkerClient workers;
    private final WorkflowConnectionService connections;

    /** 注入表达式、插件注册表、Worker 客户端和加密连接服务。 */
    public WorkflowPluginNodeExecutor(ObjectMapper objectMapper, WorkflowExpressionService expressions,
                                      WorkflowPluginRegistryService registry, WorkflowPluginWorkerClient workers,
                                      WorkflowConnectionService connections) {
        this.objectMapper = objectMapper;
        this.expressions = expressions;
        this.registry = registry;
        this.workers = workers;
        this.connections = connections;
    }

    /** 返回全部通用插件节点类型。 */
    @Override
    public Set<String> types() { return TYPES; }

    /** 解析参数、校验固定身份和凭据归属后调用隔离 Worker。 */
    @Override
    public Result execute(Request request) {
        ObjectNode config = WorkflowNodeConfigDefaults.withDefaults(objectMapper, request.type(), request.config());
        JsonNode resolved = expressions.resolve(config, request.context());
        WorkflowNodeConfigValidator.validateResolved(request.type(), resolved);
        Long componentId = resolved.path("pluginComponentId").asLong();
        WorkflowPluginRegistryService.RuntimeComponent component = registry.requireRuntimeComponent(componentId);
        requireIdentity(resolved, component);
        JsonNode parameters = resolved.path("parameters");
        validateParameters(component.schema(), parameters);
        JsonNode credentials = credentials(resolved, component, request.workflowOwnerId());
        if ("PLUGIN_TRIGGER".equals(request.type())) {
            JsonNode triggerInput = request.context().path("input");
            return Result.output(triggerInput.isMissingNode() ? objectMapper.createObjectNode() : triggerInput.deepCopy());
        }
        ObjectNode context = objectMapper.createObjectNode().put("runId", request.runId())
            .put("nodeId", request.nodeId()).put("workflowOwnerId", request.workflowOwnerId());
        JsonNode output = workers.invoke(component.source(), component.packageFingerprint(), component.externalKey(),
            "invoke", parameters, credentials, request.context().path("input"), context);
        return Result.output(output);
    }

    /** 确认模板快照没有伪造包摘要、组件名或组件类型。 */
    private void requireIdentity(JsonNode config, WorkflowPluginRegistryService.RuntimeComponent component) {
        if (!component.pluginEnabled() || !component.packageFingerprint().equalsIgnoreCase(config.path("packageFingerprint").asText())
            || !component.externalKey().equals(config.path("componentExternalId").asText())
            || !nodeType(component.componentType()).equals(config.path("componentType").asText().isBlank()
                ? nodeType(component.componentType()) : nodeType(config.path("componentType").asText()))) {
            throw new BusinessException("workflow.pluginComponentChanged");
        }
    }

    /** 读取属于工作流所有者且绑定同一组件的加密插件连接。 */
    private JsonNode credentials(JsonNode config, WorkflowPluginRegistryService.RuntimeComponent component,
                                 Long workflowOwnerId) {
        if (!component.credentialSchema().isArray() || component.credentialSchema().isEmpty()) {
            return objectMapper.createObjectNode();
        }
        if (!config.path("connectionId").canConvertToLong() || config.path("connectionId").asLong() <= 0) {
            throw new BusinessException("workflow.nodeConfigRequired", "connectionId");
        }
        WorkflowConnectionService.StoredConnection connection = connections.requireOwnedAndEnabled(
            config.path("connectionId").asLong(), workflowOwnerId, Set.of("PLUGIN"));
        if (connection.config().path("pluginComponentId").asLong() != component.id()) {
            throw new BusinessException("workflow.connectionForbidden");
        }
        JsonNode values = connection.config().path("credentials");
        if (!values.isObject()) throw new BusinessException("workflow.connectionConfigInvalid");
        validateParameters(component.credentialSchema(), values);
        return values;
    }

    /** 按市场 Schema 验证必填字段与基础 JSON 类型。 */
    private void validateParameters(JsonNode schema, JsonNode values) {
        if (!values.isObject()) throw new BusinessException("workflow.pluginParametersInvalid");
        if (schema == null || !schema.isArray()) return;
        for (JsonNode field : schema) {
            String name = field.path("name").asText("");
            if (name.isBlank()) continue;
            JsonNode value = values.path(name);
            if (field.path("required").asBoolean(false) && (value.isMissingNode() || value.isNull()
                || value.isTextual() && value.asText().isBlank())) {
                throw new BusinessException("workflow.nodeConfigRequired", name);
            }
            if (!value.isMissingNode() && !value.isNull() && !matches(field.path("type").asText("string"), value)) {
                throw new BusinessException("workflow.pluginParametersInvalid");
            }
        }
    }

    /** 判断市场基础类型是否匹配 JSON 值。 */
    private boolean matches(String rawType, JsonNode value) {
        String type = rawType == null ? "" : rawType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "number", "integer" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array", "multi-options" -> value.isArray();
            case "object", "collection", "fixedcollection" -> value.isObject();
            default -> value.isValueNode() || value.isContainerNode();
        };
    }

    /** 把组件类型映射为不可伪造的节点类型。 */
    private String nodeType(String value) {
        String type = value == null ? "" : value.toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (type) {
            case "TRIGGER", "PLUGIN_TRIGGER" -> "PLUGIN_TRIGGER";
            case "MODEL", "PLUGIN_MODEL" -> "PLUGIN_MODEL";
            case "DATASOURCE", "PLUGIN_DATASOURCE" -> "PLUGIN_DATASOURCE";
            case "AGENT_STRATEGY", "PLUGIN_AGENT_STRATEGY" -> "PLUGIN_AGENT_STRATEGY";
            case "EXTENSION", "PLUGIN_EXTENSION" -> "PLUGIN_EXTENSION";
            default -> "PLUGIN_ACTION";
        };
    }
}
