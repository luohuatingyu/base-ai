package com.baseai.platform.modeltype;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 注册图片和视频生成协议，避免生成服务直接耦合供应商字段。 */
@Component
public final class MediaGenerationProtocolRegistry {
    private final Map<String, MediaGenerationProtocolAdapter> adapters;

    /** 注册平台内置的图片和视频 JSON 适配器。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MediaGenerationProtocolRegistry() {
        this(List.of(new ImageGenerationProtocolAdapter(), new VideoGenerationProtocolAdapter()));
    }

    /** 支持测试注入自定义协议适配器。 */
    public MediaGenerationProtocolRegistry(List<MediaGenerationProtocolAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(MediaGenerationProtocolAdapter::modelType, Function.identity()));
    }

    /** 按模型类型构造生成请求；未知类型返回空结果，由服务统一拒绝。 */
    public Map<String, Object> request(String modelType, String model, String prompt, String generationMode,
                                       List<Map<String, Object>> inputs, Map<String, Object> options) {
        var adapter = adapters.get(modelType);
        return adapter == null ? Map.of() : adapter.request(model, prompt, generationMode, inputs, options);
    }
}
