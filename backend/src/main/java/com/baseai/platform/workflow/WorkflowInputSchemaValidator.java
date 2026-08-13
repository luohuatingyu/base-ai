package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

/** 校验工作流开放输入使用的受限 JSON Schema 子集，不解析远程或递归引用。 */
final class WorkflowInputSchemaValidator {
    private static final int MAX_DEPTH = 20;

    /** 工具类不允许实例化。 */
    private WorkflowInputSchemaValidator() { }

    /** 校验输入值符合定义中的类型、必填项、长度、范围、枚举和对象结构限制。 */
    static void validate(JsonNode schema, JsonNode input) {
        if (schema == null || !schema.isObject() || schema.has("$ref")) {
            throw new BusinessException("workflow.inputSchemaInvalid");
        }
        validateValue(schema, input, "inputs", 0);
    }

    /** 递归校验一个 JSON 值，深度固定受限以避免恶意 Schema 消耗执行资源。 */
    private static void validateValue(JsonNode schema, JsonNode value, String path, int depth) {
        if (depth > MAX_DEPTH || schema.has("$ref")) throw new BusinessException("workflow.inputSchemaInvalid");
        String type = schema.path("type").asText("");
        if (!matches(type, value)) throw new BusinessException("workflow.inputTypeInvalid", path, type);
        if (schema.has("enum") && schema.path("enum").isArray()
            && schema.path("enum").valueStream().noneMatch(value::equals)) {
            throw new BusinessException("workflow.inputTypeInvalid", path, "enum");
        }
        if (value.isTextual()) validateText(schema, value.asText(), path);
        if (value.isNumber()) validateNumber(schema, value, path);
        if (value.isArray()) validateArray(schema, value, path, depth);
        if (value.isObject()) validateObject(schema, value, path, depth);
    }

    /** 校验基本 JSON 类型；未指定 type 的 Schema 仅应用其显式约束。 */
    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "", "any" -> true;
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> throw new BusinessException("workflow.inputSchemaInvalid");
        };
    }

    /** 校验字符串长度边界。 */
    private static void validateText(JsonNode schema, String value, String path) {
        if (schema.has("minLength") && value.length() < positiveInteger(schema, "minLength")) {
            throw new BusinessException("workflow.inputTypeInvalid", path, "minLength");
        }
        if (schema.has("maxLength") && value.length() > positiveInteger(schema, "maxLength")) {
            throw new BusinessException("workflow.inputTypeInvalid", path, "maxLength");
        }
    }

    /** 校验数值上下限。 */
    private static void validateNumber(JsonNode schema, JsonNode value, String path) {
        if (schema.has("minimum") && (!schema.path("minimum").isNumber()
            || value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0)) {
            throw new BusinessException("workflow.inputTypeInvalid", path, "minimum");
        }
        if (schema.has("maximum") && (!schema.path("maximum").isNumber()
            || value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0)) {
            throw new BusinessException("workflow.inputTypeInvalid", path, "maximum");
        }
    }

    /** 校验数组大小和全部元素。 */
    private static void validateArray(JsonNode schema, JsonNode value, String path, int depth) {
        if (schema.has("minItems") && value.size() < positiveInteger(schema, "minItems")) {
            throw new BusinessException("workflow.inputTypeInvalid", path, "minItems");
        }
        if (schema.has("maxItems") && value.size() > positiveInteger(schema, "maxItems")) {
            throw new BusinessException("workflow.inputTypeInvalid", path, "maxItems");
        }
        if (!schema.has("items")) return;
        if (!schema.path("items").isObject()) throw new BusinessException("workflow.inputSchemaInvalid");
        for (int index = 0; index < value.size(); index++) {
            validateValue(schema.path("items"), value.get(index), path + "[" + index + "]", depth + 1);
        }
    }

    /** 校验对象必填字段、属性和值域外字段。 */
    private static void validateObject(JsonNode schema, JsonNode value, String path, int depth) {
        JsonNode properties = schema.path("properties");
        if (!properties.isMissingNode() && !properties.isObject()) throw new BusinessException("workflow.inputSchemaInvalid");
        for (JsonNode required : schema.path("required")) {
            if (!required.isTextual()) throw new BusinessException("workflow.inputSchemaInvalid");
            if (!value.has(required.asText()) || value.path(required.asText()).isNull()) {
                throw new BusinessException("workflow.inputRequired", path + "." + required.asText());
            }
        }
        value.fields().forEachRemaining(entry -> {
            JsonNode property = properties.path(entry.getKey());
            if (property.isMissingNode()) {
                if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()) {
                    throw new BusinessException("workflow.inputTypeInvalid", path + "." + entry.getKey(), "additionalProperties");
                }
            } else validateValue(property, entry.getValue(), path + "." + entry.getKey(), depth + 1);
        });
    }

    /** 读取非负整型约束；负数和浮点数均视为非法 Schema。 */
    private static int positiveInteger(JsonNode schema, String field) {
        JsonNode value = schema.path(field);
        if (!value.isIntegralNumber() || value.asInt() < 0) throw new BusinessException("workflow.inputSchemaInvalid");
        return value.asInt();
    }
}
