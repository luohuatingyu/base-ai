package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowExpressionServiceTest {
    private ObjectMapper objectMapper;
    private WorkflowExpressionService service;
    private ObjectNode context;

    /** 准备输入、节点输出和循环变量上下文。 */
    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        service = new WorkflowExpressionService(objectMapper);
        context = (ObjectNode) objectMapper.readTree("""
            {"input":{"name":"Ada","score":9},"nodes":{"http":{"httpStatus":200}},"loop":{"index":2}}
            """);
    }

    /** 完整变量引用保留 JSON 类型，嵌入引用转换为文本。 */
    @Test
    void resolvesTypedAndEmbeddedVariables() throws Exception {
        assertEquals(9, service.resolve(objectMapper.readTree("\"{{input.score}}\""), context).asInt());
        assertEquals("Hello Ada #2", service.resolve(objectMapper.readTree("\"Hello {{input.name}} #{{loop.index}}\""), context).asText());
    }

    /** 结构化比较覆盖数值、相等、包含和存在分支。 */
    @ParameterizedTest
    @CsvSource({"GT,8,true", "LTE,9,true", "EQ,9,true", "NE,9,false"})
    void evaluatesStructuredConditions(String operator, int right, boolean expected) throws Exception {
        assertEquals(expected, service.condition(objectMapper.readTree("""
            {"left":"{{input.score}}","operator":"%s","right":%d}
            """.formatted(operator, right)), context));
    }

    /** 不支持的操作符不得退化为动态表达式执行。 */
    @Test
    void rejectsUnsupportedOperators() throws Exception {
        assertThrows(BusinessException.class, () -> service.condition(objectMapper.readTree("""
            {"left":"{{input.score}}","operator":"EVAL","right":"System.exit(0)"}
            """), context));
    }
}
