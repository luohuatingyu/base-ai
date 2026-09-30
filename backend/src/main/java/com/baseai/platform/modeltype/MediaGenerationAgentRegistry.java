package com.baseai.platform.modeltype;

import org.springframework.stereotype.Component;

import java.util.List;

/** 按供应商和模型画像选择媒体 Agent 策略，保证业务服务不再写供应商分支。 */
@Component
public final class MediaGenerationAgentRegistry {
    private final List<MediaGenerationAgentStrategy> strategies;

    /** 注册内置 Ark、OpenAI、Gemini、MiniMax、Vidu 和通用策略。 */
    public MediaGenerationAgentRegistry() {
        this(new LlmModelTypeRegistry(), new MediaGenerationProtocolRegistry());
    }

    /** 支持测试注入自定义策略和历史协议注册表。 */
    public MediaGenerationAgentRegistry(LlmModelTypeRegistry modelTypeRegistry,
                                        MediaGenerationProtocolRegistry protocolRegistry) {
        this(List.of(new ArkMediaGenerationAgentStrategy(), new OpenAiMediaGenerationAgentStrategy(),
            new GeminiMediaGenerationAgentStrategy(), new MiniMaxMediaGenerationAgentStrategy(),
            new ViduMediaGenerationAgentStrategy(), new GenericMediaGenerationAgentStrategy(modelTypeRegistry, protocolRegistry)));
    }

    /** 支持测试注入有序策略列表，最后一个策略应提供兜底行为。 */
    public MediaGenerationAgentRegistry(List<MediaGenerationAgentStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    /** 选择最具体的媒体 Agent 策略。 */
    public MediaGenerationAgentStrategy resolve(String providerCode, String model, String profile) {
        return strategies.stream().filter(strategy -> strategy.supports(providerCode, model, profile)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No media generation strategy"));
    }

    /** 按供应商显式绑定的策略解析媒体 Agent；历史空值才回退到名称推断。 */
    public MediaGenerationAgentStrategy resolve(String strategyCode, String providerCode, String model, String profile) {
        if (strategyCode == null || strategyCode.isBlank()) return resolve(providerCode, model, profile);
        String normalized = normalizeStrategyCode(strategyCode);
        return strategies.stream().filter(strategy -> normalizeStrategyCode(strategy.id()).equals(normalized)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown media generation strategy: " + strategyCode));
    }

    /** 返回已注册的策略编码，供供应商管理页面选择。 */
    public List<String> strategyCodes() { return strategies.stream().map(MediaGenerationAgentStrategy::id).toList(); }

    /** 判断模型画像是否属于供应商绑定策略；通用策略允许自定义画像。 */
    public boolean supportsProfile(String strategyCode, String providerCode, String model, String profile) {
        if (profile == null || profile.isBlank()) return true;
        MediaGenerationAgentStrategy strategy = resolve(strategyCode, providerCode, model, profile);
        if ("GENERIC".equalsIgnoreCase(strategy.id())) return true;
        return strategy.supports("", "", profile);
    }

    /** 返回策略编码，供模型目录和任务诊断使用。 */
    public String protocol(String providerCode, String model, String profile) {
        return resolve(providerCode, model, profile).id();
    }

    /** 返回策略能力目录。 */
    public java.util.Map<String, Object> capabilities(String providerCode, String model, String profile, String modelType) {
        return resolve(providerCode, model, profile).capabilities(modelType, model, profile);
    }

    /** 返回供应商显式绑定策略的能力目录。 */
    public java.util.Map<String, Object> capabilities(String strategyCode, String providerCode, String model,
                                                      String profile, String modelType) {
        return resolve(strategyCode, providerCode, model, profile).capabilities(modelType, model, profile);
    }

    /** 返回供应商显式绑定策略的稳定编码。 */
    public String protocol(String strategyCode, String providerCode, String model, String profile) {
        return resolve(strategyCode, providerCode, model, profile).id();
    }

    /** 判断显式生成画像是否属于内置策略，供模型保存校验使用。 */
    public static boolean knownProfile(String profile, String modelType) {
        String value = profile == null ? "" : profile.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (ArkMediaProtocol.supports(value)) return true;
        if (value.contains("openai") || value.contains("gpt-image") || value.contains("dall-e") || value.contains("sora")) return true;
        if (value.contains("gemini") || value.contains("google") || value.contains("veo") || value.contains("imagen")) return true;
        if (value.contains("minimax") || value.contains("hailuo") || value.contains("video-01") || value.contains("image-01")) return true;
        if (value.contains("vidu")) return true;
        return false;
    }

    /** 规范化供应商策略编码，避免大小写差异造成绑定失效。 */
    private static String normalizeStrategyCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
