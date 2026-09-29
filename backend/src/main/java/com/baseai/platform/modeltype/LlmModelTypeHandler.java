package com.baseai.platform.modeltype;

import java.util.Set;

/** 描述一种模型类型支持的操作及其 Worker 健康检查协议。 */
public interface LlmModelTypeHandler {
    /** 返回字典中的规范类型编码。 */
    String type();

    /** 返回该类型可以执行的操作集合。 */
    Set<LlmOperation> operations();

    /** 返回健康检查所需的 Worker 协议名称。 */
    default String healthProtocol() { return null; }

    /** 返回媒体协议使用的供应商相对路径。 */
    default String generationPath() { return null; }

    /** 判断该类型是否支持给定操作。 */
    default boolean supports(LlmOperation operation) { return operations().contains(operation); }
}
