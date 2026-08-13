package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowInputSchemaValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 嵌套必填字段、范围和额外字段限制都必须在入队前得到校验。 */
    @Test
    void validatesNestedRestrictedSchema() throws Exception {
        var schema = objectMapper.readTree("""
            {"type":"object","required":["order"],"additionalProperties":false,"properties":{"order":{"type":"object","required":["items"],"properties":{"items":{"type":"array","minItems":1,"items":{"type":"object","required":["quantity"],"properties":{"quantity":{"type":"integer","minimum":1,"maximum":10}}}}}}}}
            """);
        assertDoesNotThrow(() -> WorkflowInputSchemaValidator.validate(schema,
            objectMapper.readTree("{\"order\":{\"items\":[{\"quantity\":2}]}}")));
        BusinessException exception = assertThrows(BusinessException.class, () -> WorkflowInputSchemaValidator.validate(schema,
            objectMapper.readTree("{\"order\":{\"items\":[{\"quantity\":0}]}}")));
        assertEquals("workflow.inputTypeInvalid", exception.getMessageKey());
    }

    /** 外部引用不得被解析，防止 Schema 驱动的远程访问或循环消耗。 */
    @Test
    void rejectsReferenceSchema() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class, () -> WorkflowInputSchemaValidator.validate(
            objectMapper.readTree("{\"$ref\":\"https://attacker.invalid/schema\"}"), objectMapper.createObjectNode()));
        assertEquals("workflow.inputSchemaInvalid", exception.getMessageKey());
    }
}
