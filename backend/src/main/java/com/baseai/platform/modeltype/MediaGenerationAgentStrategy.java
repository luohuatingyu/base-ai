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

    /** 返回异步任务查询路径；同步模型返回 null。 */
    String statusPath(String modelType, String model, String remoteId);

    /** 返回创建接口路径，用于从已配置的完整地址切换到状态地址。 */
    String operationPath(String modelType, String model);

    /** 把平台统一输入转换为供应商请求正文。 */
    Map<String, Object> request(String modelType, String model, String profile, String prompt,
                                List<Map<String, Object>> inputs, Map<String, Object> options);

    /** 返回供前端渲染的能力目录。 */
    Map<String, Object> capabilities(String modelType, String model, String profile);

    /** 返回该策略是否允许直接把远程素材地址交给供应商。 */
    default boolean supportsRemoteInputs() { return false; }
}
