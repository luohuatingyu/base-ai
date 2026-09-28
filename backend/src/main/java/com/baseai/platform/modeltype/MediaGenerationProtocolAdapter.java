package com.baseai.platform.modeltype;

import java.util.List;
import java.util.Map;

/** 将平台统一媒体请求转换为供应商可扩展的媒体生成请求。 */
public interface MediaGenerationProtocolAdapter {
    /** 返回当前适配器负责的模型类型。 */
    String modelType();

    /** 构造首个生成请求的 JSON 正文。 */
    Map<String, Object> request(String model, String prompt, String generationMode,
                                List<Map<String, Object>> inputs, Map<String, Object> options);
}
