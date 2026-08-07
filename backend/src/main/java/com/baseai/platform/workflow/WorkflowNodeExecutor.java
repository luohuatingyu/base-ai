package com.baseai.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/** 定义 Base AI 原生叶子节点的统一执行契约。 */
public interface WorkflowNodeExecutor {
    /** 返回当前执行器负责的全部节点类型。 */
    Set<String> types();

    /** 使用已解析配置和只读上下文执行节点。 */
    Result execute(Request request);

    record Request(String runId, String nodeId, String type, ObjectNode config, ObjectNode context, Long workflowOwnerId) { }
    record Result(JsonNode output, String branch) {
        /** 创建不选择分支的普通节点结果。 */
        public static Result output(JsonNode output) { return new Result(output, null); }
        /** 创建带命名输出端口的分支节点结果。 */
        public static Result branch(JsonNode output, String branch) { return new Result(output, branch); }
    }
}
