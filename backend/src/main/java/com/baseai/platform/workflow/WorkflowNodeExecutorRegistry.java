package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将节点类型映射到唯一的 Base AI 原生执行器。 */
@Component
public class WorkflowNodeExecutorRegistry {
    private final Map<String, WorkflowNodeExecutor> executors = new LinkedHashMap<>();

    /** 注册 Spring 容器中的节点执行器并拒绝重复类型。 */
    public WorkflowNodeExecutorRegistry(List<WorkflowNodeExecutor> candidates) {
        for (WorkflowNodeExecutor executor : candidates) {
            for (String type : executor.types()) {
                if (executors.putIfAbsent(type, executor) != null) {
                    throw new IllegalStateException("工作流节点执行器重复注册: " + type);
                }
            }
        }
    }

    /** 查找指定节点类型的执行器。 */
    public WorkflowNodeExecutor require(String type) {
        WorkflowNodeExecutor executor = executors.get(type);
        if (executor == null) throw new BusinessException("workflow.nodeTypeInvalid");
        return executor;
    }

    /** 判断类型是否由扩展执行器处理。 */
    public boolean supports(String type) { return executors.containsKey(type); }
}
