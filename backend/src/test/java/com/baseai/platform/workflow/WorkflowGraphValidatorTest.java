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
}
