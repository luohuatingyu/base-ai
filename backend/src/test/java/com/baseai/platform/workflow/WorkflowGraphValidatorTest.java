package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowGraphValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowGraphValidator validator = new WorkflowGraphValidator(objectMapper, 100);

    /** 最小开始到结束的有向无环图必须通过发布校验。 */
    @Test
    void acceptsMinimalWorkflow() throws Exception {
        assertDoesNotThrow(() -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"end","type":"END"}],
             "edges":[{"id":"edge-1","source":"start","target":"end"}]}
            """)));
    }

    /** 主图可以使用一个原生触发节点替代手动开始节点。 */
    @Test
    void acceptsSingleTriggerAsWorkflowEntry() throws Exception {
        assertDoesNotThrow(() -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"trigger","type":"WEBHOOK_TRIGGER"},{"id":"end","type":"END"}],
             "edges":[{"id":"edge-1","source":"trigger","target":"end"}]}
            """)));
    }

    /** 手动开始节点和触发节点不能同时存在，避免一次定义拥有多个入口。 */
    @Test
    void rejectsMultipleWorkflowEntries() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"trigger","type":"SCHEDULE_TRIGGER"},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"end"},{"id":"b","source":"trigger","target":"end"}]}
            """)));
    }

    /** 普通主图中的循环边必须被拒绝，防止绕过受控循环节点。 */
    @Test
    void rejectsUncontrolledCycle() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"llm","type":"LLM"},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"llm"},{"id":"b","source":"llm","target":"start"},
                      {"id":"c","source":"llm","target":"end"}]}
            """)));
    }

    /** 工作流必须有唯一开始节点和至少一个结束节点。 */
    @Test
    void requiresStartAndEndNodes() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"only","type":"LLM"}],"edges":[]}
            """)));
    }

    /** 所有可达路径必须以 END 结束，不能在普通节点静默成功。 */
    @Test
    void rejectsTerminalNodeThatIsNotEnd() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"llm","type":"LLM"},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"llm"}]}
            """)));
    }

    /** END 之后不得继续连接下游副作用节点。 */
    @Test
    void rejectsEndNodeWithOutgoingEdge() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"end","type":"END"},{"id":"llm","type":"LLM"}],
             "edges":[{"id":"a","source":"start","target":"end"},{"id":"b","source":"end","target":"llm"}]}
            """)));
    }

    /** Vue Flow 的 data.config 子画布也必须递归校验，不能绕过普通循环检测。 */
    @Test
    void rejectsCycleInsideVueFlowNodeConfig() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[
                {"id":"start","type":"START"},
                {"id":"iteration","type":"ITERATION","data":{"config":{"bodyGraph":{
                    "nodes":[{"id":"inner-start","type":"START"},{"id":"inner-end","type":"END"}],
                    "edges":[{"id":"a","source":"inner-start","target":"inner-end"},
                             {"id":"b","source":"inner-end","target":"inner-start"}]
                }}}},
                {"id":"end","type":"END"}
             ],"edges":[{"id":"outer-a","source":"start","target":"iteration"},
                         {"id":"outer-b","source":"iteration","target":"end"}]}
            """)));
    }

    /** 嵌套控制节点深度必须受平台上限约束。 */
    @Test
    void rejectsNestedGraphsBeyondDepthLimit() throws Exception {
        WorkflowGraphValidator shallow = new WorkflowGraphValidator(objectMapper, 100, 1);
        assertThrows(BusinessException.class, () -> shallow.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"loop","type":"LOOP","config":{"bodyGraph":{
                "nodes":[{"id":"inner-start","type":"START"},{"id":"inner-loop","type":"LOOP","config":{"bodyGraph":{
                    "nodes":[{"id":"deep-start","type":"START"},{"id":"deep-end","type":"END"}],
                    "edges":[{"id":"deep-edge","source":"deep-start","target":"deep-end"}]
                }}},{"id":"inner-end","type":"END"}],
                "edges":[{"id":"inner-a","source":"inner-start","target":"inner-loop"},{"id":"inner-b","source":"inner-loop","target":"inner-end"}]
            }}},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"loop"},{"id":"b","source":"loop","target":"end"}]}
            """)));
    }

    /** 持久等待只能出现在主图，嵌套执行不能保存不完整的子图检查点。 */
    @Test
    void rejectsWaitInsideNestedGraph() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"loop","type":"LOOP","config":{"bodyGraph":{
                "nodes":[{"id":"inner-start","type":"START"},{"id":"wait","type":"WAIT"},{"id":"inner-end","type":"END"}],
                "edges":[{"id":"inner-a","source":"inner-start","target":"wait"},{"id":"inner-b","source":"wait","target":"inner-end"}]
            }}},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"loop"},{"id":"b","source":"loop","target":"end"}]}
            """)));
    }

    /** 条件节点必须声明且只声明 true/false 两条出口，不能由空 handle 意外执行所有下游。 */
    @Test
    void rejectsConditionWithoutExplicitBranches() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"condition","type":"CONDITION"},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"condition"},{"id":"b","source":"condition","target":"end"}]}
            """)));
    }

    /** 节点上限必须累计主图和全部子图，不能由每层分别计数绕过。 */
    @Test
    void rejectsNestedGraphsWhenCumulativeNodeLimitIsExceeded() throws Exception {
        WorkflowGraphValidator limited = new WorkflowGraphValidator(objectMapper, 4, 5);
        assertThrows(BusinessException.class, () -> limited.validate(objectMapper.readTree("""
            {"nodes":[{"id":"start","type":"START"},{"id":"loop","type":"LOOP","config":{"bodyGraph":{
                "nodes":[{"id":"inner-start","type":"START"},{"id":"inner-end","type":"END"}],
                "edges":[{"id":"inner-edge","source":"inner-start","target":"inner-end"}]
            }}},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"loop"},{"id":"b","source":"loop","target":"end"}]}
            """)));
    }
}
