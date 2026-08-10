package com.baseai.platform.workflow;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 统一维护原生节点执行协议与已验证模型类型的兼容关系。 */
public final class WorkflowModelCompatibility {
    private static final List<String> CHAT_TYPES = List.of("text_model", "vision_model");
    private static final List<Policy> POLICIES = List.of(
        new Policy("LLM", "CHAT", "text_model", CHAT_TYPES),
        new Policy("AGENT", "CHAT", "text_model", CHAT_TYPES),
        new Policy("RAG", "CHAT", "text_model", CHAT_TYPES),
        new Policy("QUESTION_CLASSIFIER", "CHAT", "text_model", CHAT_TYPES),
        new Policy("PARAMETER_EXTRACTOR", "CHAT", "text_model", CHAT_TYPES),
        new Policy("EMBEDDING", "EMBEDDINGS", "embedding_model", List.of("embedding_model"))
    );
    private static final Map<String, Policy> BY_NODE = POLICIES.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(Policy::nodeType, item -> item));

    /** 工具类不允许实例化。 */
    private WorkflowModelCompatibility() { }

    /** 返回全部模型节点兼容策略，顺序同时用于文档展示。 */
    public static List<Policy> policies() { return POLICIES; }

    /** 按节点类型查找兼容策略，普通节点返回空。 */
    public static Optional<Policy> policy(String nodeType) { return Optional.ofNullable(BY_NODE.get(normalize(nodeType))); }

    /** 返回节点已验证的模型类型；未知节点没有模型候选。 */
    public static List<String> allowedModelTypes(String nodeType) {
        return policy(nodeType).map(Policy::allowedModelTypes).orElse(List.of());
    }

    /** 判断节点是否明确支持给定模型类型，未知扩展类型默认拒绝。 */
    public static boolean supports(String nodeType, String modelType) {
        String normalized = modelType == null ? "" : modelType.trim().toLowerCase(Locale.ROOT);
        return allowedModelTypes(nodeType).contains(normalized);
    }

    /** 使用稳定英文大写节点编码查找策略。 */
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    /** 描述一个原生节点的执行协议、推荐类型和允许类型。 */
    public record Policy(String nodeType, String protocol, String recommendedModelType, List<String> allowedModelTypes) {
        /** 复制允许类型，避免接口调用方修改全局兼容目录。 */
        public Policy { allowedModelTypes = List.copyOf(allowedModelTypes); }
    }
}
