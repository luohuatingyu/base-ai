package com.baseai.platform.modeltype;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 模型类型能力注册表。
 *
 * <p>路由、聊天、向量和媒体服务只依赖这里的能力查询，不再复制类型编码判断。
 * 未显式注册的历史字典类型保留聊天兼容处理器，避免升级后已有配置突然失效。</p>
 */
@Component
public final class LlmModelTypeRegistry {
    private final Map<String, LlmModelTypeHandler> handlers;
    private final LlmModelTypeHandler legacyChatHandler = new BasicLlmModelTypeHandler(
        "legacy_chat", Set.of(LlmOperation.CHAT, LlmOperation.HEALTH_CHECK), "chat", null);

    /** 创建包含平台内置类型的注册表。 */
    public LlmModelTypeRegistry() { this(List.of(
        new BasicLlmModelTypeHandler("text_model", Set.of(LlmOperation.CHAT, LlmOperation.HEALTH_CHECK), "chat", null),
        new BasicLlmModelTypeHandler("vision_model", Set.of(LlmOperation.CHAT, LlmOperation.HEALTH_CHECK), "chat", null),
        new BasicLlmModelTypeHandler("embedding_model", Set.of(LlmOperation.EMBEDDING, LlmOperation.HEALTH_CHECK), "embedding", null),
        new BasicLlmModelTypeHandler("image_model", Set.of(LlmOperation.IMAGE_GENERATION), null, "/images/generations"),
        new BasicLlmModelTypeHandler("video_model", Set.of(LlmOperation.VIDEO_GENERATION), null, "/videos/generations")
    )); }

    /** 使用额外处理器创建注册表，便于插件类型和单元测试注入。 */
    public LlmModelTypeRegistry(Collection<? extends LlmModelTypeHandler> handlers) {
        Map<String, LlmModelTypeHandler> values = new LinkedHashMap<>();
        if (handlers != null) for (LlmModelTypeHandler handler : handlers) {
            if (handler == null || normalize(handler.type()).isBlank()) continue;
            values.put(normalize(handler.type()), handler);
        }
        this.handlers = Map.copyOf(values);
    }

    /** 按类型编码解析处理器；未注册类型走历史聊天兼容策略。 */
    public LlmModelTypeHandler resolve(String type) {
        return handlers.getOrDefault(normalize(type), legacyChatHandler);
    }

    /** 返回模型类型是否支持指定业务操作。 */
    public boolean supports(String type, LlmOperation operation) {
        return operation != null && resolve(type).supports(operation);
    }

    /** 返回一组能力声明是否至少支持指定业务操作。 */
    public boolean supportsAny(Collection<String> types, LlmOperation operation) {
        return normalize(types).stream().anyMatch(type -> supports(type, operation));
    }

    /** 返回一组能力声明是否包含生成协议。 */
    public boolean containsGeneration(Collection<String> types) {
        return normalize(types).stream().anyMatch(type -> supports(type, LlmOperation.IMAGE_GENERATION)
            || supports(type, LlmOperation.VIDEO_GENERATION));
    }

    /** 返回可执行聊天协议的类型，保持原有顺序和去重规则。 */
    public List<String> chatTypes(Collection<String> types) {
        return normalize(types).stream().filter(type -> supports(type, LlmOperation.CHAT)).toList();
    }

    /** 返回类型对应的健康检查协议；纯向量模型走 embeddings，其余聊天兼容类型走 chat。 */
    public String healthProtocol(Collection<String> types) {
        List<String> normalized = normalize(types);
        if (normalized.size() == 1 && supports(normalized.get(0), LlmOperation.EMBEDDING)) return "embedding";
        if (supportsAny(normalized, LlmOperation.CHAT)) return "chat";
        return null;
    }

    /** 返回图片或视频生成类型对应的供应商路径。 */
    public String generationPath(String type) { return resolve(type).generationPath(); }

    /** 返回规范化、去重后的模型类型列表。 */
    public List<String> normalize(Collection<String> types) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (types != null) for (String value : types) {
            if (value == null) continue;
            for (String item : value.split(",")) {
                String normalized = normalize(item);
                if (!normalized.isBlank()) result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    /** 规范化一个模型类型编码。 */
    public String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
