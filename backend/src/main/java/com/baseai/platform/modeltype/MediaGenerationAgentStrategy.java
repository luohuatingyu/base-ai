package com.baseai.platform.modeltype;

import java.util.List;
import java.util.Map;

/** 媒体供应商 Agent 策略，隔离认证后的请求、轮询路径和能力目录。 */
public interface MediaGenerationAgentStrategy {
    /** 返回策略稳定编码，用于前端展示和任务诊断。 */
    String id();

    /** 判断当前供应商、模型或管理员画像是否由策略负责。 */
    boolean supports(String providerCode, String model, String profile);

    /** 返回创建图片或视频任务的相对路径。 */
    String generationPath(String modelType, String model);

    /** 按输入素材选择供应商专用创建路径；默认复用模型类型路径。 */
    default String generationPath(String modelType, String model, String prompt,
                                  List<Map<String, Object>> inputs, Map<String, Object> options) {
        return generationPath(modelType, model);
    }

    /** 返回异步任务查询路径；同步模型返回 null。 */
    String statusPath(String modelType, String model, String remoteId);

    /** 返回创建接口路径，用于从已配置的完整地址切换到状态地址。 */
    String operationPath(String modelType, String model);

    /** 返回异步查询请求的供应商专用查询参数。 */
    default Map<String, String> statusQuery(String modelType, String model, String remoteId) { return Map.of(); }

    /** 把平台统一输入转换为供应商请求正文。 */
    Map<String, Object> request(String modelType, String model, String profile, String prompt,
                                List<Map<String, Object>> inputs, Map<String, Object> options);

    /** 返回供前端渲染的能力目录。 */
    Map<String, Object> capabilities(String modelType, String model, String profile);

    /** 规范化供应商专用选项；默认保留平台已校验的选项。 */
    default Map<String, Object> normalizeOptions(String modelType, String model, String profile, String mode,
                                                  String prompt, List<Map<String, Object>> inputs,
                                                  Map<String, Object> options) {
        return options == null ? Map.of() : options;
    }

    /** 判断模型画像是否声明了指定生成模式。 */
    default boolean supportsMode(String modelType, String model, String profile, String mode) {
        Object modes = capabilities(modelType, model, profile).get("modes");
        return modes instanceof List<?> values && values.contains(mode);
    }

    /** 判断策略是否允许使用 Ark 的特殊负数时长。 */
    default boolean allowsSpecialDuration() { return false; }

    /** 返回该策略是否允许直接把远程素材地址交给供应商。 */
    default boolean supportsRemoteInputs() { return false; }

    /** 返回单次请求允许的最大输入数；默认由平台统一边界控制。 */
    default int maxInputs(String modelType, String model, String profile) { return 4; }

    /** 返回供应商认证头的完整值；默认使用 Bearer 认证。 */
    default String authorizationHeader(String apiKey) { return "Bearer " + apiKey; }
}
