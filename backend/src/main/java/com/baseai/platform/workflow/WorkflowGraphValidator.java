package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** 校验工作流画布结构，拒绝悬空边、不可达节点和未受控循环。 */
@Component
public class WorkflowGraphValidator {
    private final ObjectMapper objectMapper;
    private final int maxNodes;
    private final int maxDepth;
    private final int maxDefinitionBytes;

    /** 使用平台资源限制创建生产校验器。 */
    @Autowired
    public WorkflowGraphValidator(ObjectMapper objectMapper, PlatformProperties properties) {
        this(objectMapper, properties.getWorkflow().getMaxNodes(), properties.getWorkflow().getMaxRecursionDepth(),
            properties.getWorkflow().getMaxPayloadBytes());
    }

    /** 允许测试注入明确节点上限。 */
    WorkflowGraphValidator(ObjectMapper objectMapper, int maxNodes) {
        this(objectMapper, maxNodes, 5, 1024 * 1024);
    }

    /** 允许测试同时注入节点和嵌套深度上限。 */
    WorkflowGraphValidator(ObjectMapper objectMapper, int maxNodes, int maxDepth) {
        this(objectMapper, maxNodes, maxDepth, 1024 * 1024);
    }

    /** 允许测试同时注入节点、深度和定义字节上限。 */
    WorkflowGraphValidator(ObjectMapper objectMapper, int maxNodes, int maxDepth, int maxDefinitionBytes) {
        this.objectMapper = objectMapper;
        this.maxNodes = Math.max(2, maxNodes);
        this.maxDepth = Math.max(1, maxDepth);
        this.maxDefinitionBytes = Math.max(1024, maxDefinitionBytes);
    }

    /** 校验主图及迭代、循环节点携带的嵌套子图。 */
    public void validate(JsonNode graph) {
        validatePayload(graph);
        validateGraph(graph, true, 0, new AtomicInteger());
    }

    /** 限制画布、输入 Schema 等持久化定义的序列化体积。 */
    public void validatePayload(JsonNode value) {
        try {
            if (objectMapper.writeValueAsBytes(value).length > maxDefinitionBytes) {
                throw new BusinessException("workflow.graphPayloadTooLarge", maxDefinitionBytes);
            }
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.graphInvalid"); }
    }

    /** 解析 JSON 文本后执行统一图校验。 */
    public JsonNode parseAndValidate(String graphJson) {
        try {
            JsonNode graph = objectMapper.readTree(graphJson == null ? "" : graphJson);
            validate(graph);
            return graph;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("workflow.graphInvalid");
        }
    }

    /** 校验单层有向无环图，并递归校验控制节点子画布。 */
    private void validateGraph(JsonNode graph, boolean requireBoundary, int depth, AtomicInteger totalNodes) {
        if (depth > maxDepth) throw new BusinessException("workflow.recursionLimit");
        if (graph == null || !graph.isObject() || !graph.path("nodes").isArray() || !graph.path("edges").isArray()) {
            throw new BusinessException("workflow.graphInvalid");
        }
        List<JsonNode> nodes = iterable(graph.path("nodes"));
        if (nodes.size() < 2 || totalNodes.addAndGet(nodes.size()) > maxNodes) {
            throw new BusinessException("workflow.graphNodeLimit", maxNodes);
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        int startCount = 0;
        int endCount = 0;
        for (JsonNode node : nodes) {
            String id = text(node, "id");
            String type = nodeType(node);
            if (!id.matches("[A-Za-z0-9_-]{1,100}") || !WorkflowNodeTypes.ALL.contains(type)
                || byId.putIfAbsent(id, node) != null) {
                throw new BusinessException("workflow.graphInvalid");
            }
            if ("START".equals(type) || WorkflowNodeTypes.TRIGGERS.contains(type)) startCount++;
            if (depth > 0 && WorkflowNodeTypes.TRIGGERS.contains(type)) throw new BusinessException("workflow.graphInvalid");
            if (depth > 0 && "WAIT".equals(type)) throw new BusinessException("workflow.waitNestedForbidden");
            if ("END".equals(type)) endCount++;
            JsonNode config = nodeConfig(node);
            if (WorkflowNodeTypes.NESTED_GRAPH.contains(type) && config.has("bodyGraph")) {
                validateGraph(config.path("bodyGraph"), true, depth + 1, totalNodes);
            }
        }
        if (requireBoundary && (startCount != 1 || endCount < 1)) {
            throw new BusinessException("workflow.graphBoundaryRequired");
        }

        Map<String, Set<String>> outgoing = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        byId.keySet().forEach(id -> { outgoing.put(id, new HashSet<>()); incoming.put(id, 0); });
        List<JsonNode> edges = iterable(graph.path("edges"));
        if (edges.size() > maxNodes * 4) throw new BusinessException("workflow.graphEdgeLimit", maxNodes * 4);
        Set<String> edgeIds = new HashSet<>();
        for (JsonNode edge : edges) {
            String edgeId = text(edge, "id");
            String source = text(edge, "source");
            String target = text(edge, "target");
            if (!edgeId.matches("[A-Za-z0-9_-]{1,100}") || !edgeIds.add(edgeId)
                || !byId.containsKey(source) || !byId.containsKey(target) || source.equals(target)) {
                throw new BusinessException("workflow.graphDanglingEdge");
            }
            if (outgoing.get(source).add(target)) incoming.put(target, incoming.get(target) + 1);
        }
        if (hasCycle(outgoing, incoming)) throw new BusinessException("workflow.graphCycle");
        ensureReachable(byId, outgoing);
        ensureConditionBranches(byId, edges);
        ensureTerminalEnds(byId, outgoing);
    }

    /** 使用 Kahn 算法检测普通图循环。 */
    private boolean hasCycle(Map<String, Set<String>> outgoing, Map<String, Integer> originalIncoming) {
        Map<String, Integer> incoming = new HashMap<>(originalIncoming);
        ArrayDeque<String> queue = new ArrayDeque<>();
        incoming.forEach((id, count) -> { if (count == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.remove();
            visited++;
            for (String target : outgoing.get(id)) {
                int remaining = incoming.compute(target, (ignored, count) -> count - 1);
                if (remaining == 0) queue.add(target);
            }
        }
        return visited != outgoing.size();
    }

    /** 保证每个节点都能从唯一开始节点到达。 */
    private void ensureReachable(Map<String, JsonNode> byId, Map<String, Set<String>> outgoing) {
        String start = byId.entrySet().stream().filter(entry -> {
                String type = nodeType(entry.getValue());
                return "START".equals(type) || WorkflowNodeTypes.TRIGGERS.contains(type);
            })
            .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new BusinessException("workflow.graphBoundaryRequired"));
        Set<String> reached = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (reached.add(current)) queue.addAll(outgoing.get(current));
        }
        if (reached.size() != byId.size()) throw new BusinessException("workflow.graphUnreachable");
    }

    /** 条件节点必须显式连接 true 和 false 两个分支，避免空 handle 放行全部下游。 */
    private void ensureConditionBranches(Map<String, JsonNode> byId, List<JsonNode> edges) {
        for (Map.Entry<String, JsonNode> entry : byId.entrySet()) {
            if (!"CONDITION".equals(nodeType(entry.getValue()))) continue;
            Set<String> handles = new HashSet<>();
            for (JsonNode edge : edges) {
                if (!entry.getKey().equals(text(edge, "source"))) continue;
                String handle = text(edge, "sourceHandle").toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("true", "false").contains(handle) || !handles.add(handle)) {
                    throw new BusinessException("workflow.graphInvalid");
                }
            }
            if (!handles.equals(Set.of("true", "false"))) throw new BusinessException("workflow.graphInvalid");
        }
    }

    /** 所有执行路径只能在 END 结束，且 END 不允许继续连接下游节点。 */
    private void ensureTerminalEnds(Map<String, JsonNode> byId, Map<String, Set<String>> outgoing) {
        for (Map.Entry<String, JsonNode> entry : byId.entrySet()) {
            boolean end = "END".equals(nodeType(entry.getValue()));
            boolean terminal = outgoing.get(entry.getKey()).isEmpty();
            if (end != terminal) throw new BusinessException("workflow.graphInvalid");
        }
    }

    /** 将数组节点复制为稳定列表，避免校验过程中依赖可变迭代器。 */
    private List<JsonNode> iterable(JsonNode array) {
        java.util.ArrayList<JsonNode> values = new java.util.ArrayList<>();
        Iterator<JsonNode> iterator = array.elements();
        iterator.forEachRemaining(values::add);
        return values;
    }

    /** 兼容节点 type 和 data.nodeType 两种画布序列化结构。 */
    public static String nodeType(JsonNode node) {
        String type = text(node, "type").toUpperCase(java.util.Locale.ROOT);
        if (WorkflowNodeTypes.ALL.contains(type)) return type;
        return text(node.path("data"), "nodeType").toUpperCase(java.util.Locale.ROOT);
    }

    /** 兼容后端直传和 Vue Flow 序列化的节点配置位置。 */
    private static JsonNode nodeConfig(JsonNode node) {
        return node.path("config").isObject() ? node.path("config") : node.path("data").path("config");
    }

    /** 安全读取 JSON 文本字段。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }
}
