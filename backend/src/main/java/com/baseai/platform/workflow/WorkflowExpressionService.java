package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析受限变量引用和结构化条件，不执行任意脚本。 */
@Service
public class WorkflowExpressionService {
    private static final Pattern EXACT = Pattern.compile("^\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}$");
    private static final Pattern EMBEDDED = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private final ObjectMapper objectMapper;

    /** 注入 JSON 工厂。 */
    public WorkflowExpressionService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    /** 递归解析对象、数组和字符串中的变量引用。 */
    public JsonNode resolve(JsonNode value, ObjectNode context) {
        if (value == null || value.isNull()) return objectMapper.nullNode();
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            value.fields().forEachRemaining(entry -> result.set(entry.getKey(), resolve(entry.getValue(), context)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(resolve(item, context)));
            return result;
        }
        if (!value.isTextual()) return value.deepCopy();
        String raw = value.asText();
        Matcher exact = EXACT.matcher(raw);
        if (exact.matches()) return lookup(context, exact.group(1)).deepCopy();
        Matcher embedded = EMBEDDED.matcher(raw);
        StringBuffer output = new StringBuffer();
        while (embedded.find()) embedded.appendReplacement(output, Matcher.quoteReplacement(stringValue(lookup(context, embedded.group(1)))));
        embedded.appendTail(output);
        return TextNode.valueOf(output.toString());
    }

    /** 计算条件节点或循环节点的结构化比较表达式。 */
    public boolean condition(JsonNode condition, ObjectNode context) {
        if (condition == null || !condition.isObject()) throw new BusinessException("workflow.conditionInvalid");
        JsonNode left = resolve(condition.path("left"), context);
        JsonNode right = resolve(condition.path("right"), context);
        String operator = condition.path("operator").asText("EQ").toUpperCase(java.util.Locale.ROOT);
        return switch (operator) {
            case "EQ" -> left.equals(right) || stringValue(left).equals(stringValue(right));
            case "NE" -> !(left.equals(right) || stringValue(left).equals(stringValue(right)));
            case "GT" -> decimal(left).compareTo(decimal(right)) > 0;
            case "GTE" -> decimal(left).compareTo(decimal(right)) >= 0;
            case "LT" -> decimal(left).compareTo(decimal(right)) < 0;
            case "LTE" -> decimal(left).compareTo(decimal(right)) <= 0;
            case "CONTAINS" -> left.isArray()
                ? java.util.stream.StreamSupport.stream(left.spliterator(), false).anyMatch(right::equals)
                : stringValue(left).contains(stringValue(right));
            case "EXISTS" -> !left.isMissingNode() && !left.isNull() && !stringValue(left).isBlank();
            case "EMPTY" -> left.isNull() || left.isMissingNode() || stringValue(left).isBlank() || left.isContainerNode() && left.isEmpty();
            default -> throw new BusinessException("workflow.conditionInvalid");
        };
    }

    /** 按点路径读取输入、节点输出和循环上下文。 */
    public JsonNode lookup(JsonNode context, String path) {
        JsonNode current = context;
        for (String part : path.split("\\.")) {
            if (current == null) return objectMapper.missingNode();
            if (current.isArray() && part.matches("\\d+")) current = current.path(Integer.parseInt(part));
            else current = current.path(part);
        }
        return current == null ? objectMapper.missingNode() : current;
    }

    /** 将任意 JSON 值转换为模板替换文本。 */
    private String stringValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        return value.isValueNode() ? value.asText() : value.toString();
    }

    /** 数值比较只接受可解析十进制，避免字符串字典序误判。 */
    private BigDecimal decimal(JsonNode value) {
        try { return value.isNumber() ? value.decimalValue() : new BigDecimal(stringValue(value)); }
        catch (NumberFormatException exception) { throw new BusinessException("workflow.conditionInvalid"); }
    }
}
