package com.baseai.platform.modeltype;

import java.util.Set;

/** 内置模型类型的不可变处理器实现；新增类型只需注册新的实例。 */
public record BasicLlmModelTypeHandler(String type, Set<LlmOperation> operations,
                                       String healthProtocol, String generationPath)
    implements LlmModelTypeHandler {
    /** 使用空集合和空协议创建不提供任何接入能力的类型处理器。 */
    public BasicLlmModelTypeHandler {
        operations = Set.copyOf(operations == null ? Set.of() : operations);
    }
}
