package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 执行不依赖外部连接的数据、模板、文档和结构化分支节点。 */
@Component
public class WorkflowDataNodeExecutor implements WorkflowNodeExecutor {
    private static final Set<String> TYPES = Set.of(
        "SWITCH", "MERGE", "SET_VARIABLE", "TEMPLATE", "JSON_PARSE", "JSON_VALIDATE", "TRANSFORM",
        "FILTER", "SORT", "AGGREGATE", "CSV", "STRUCTURED_OUTPUT", "DOCUMENT_EXTRACTOR"
    );
    private final ObjectMapper objectMapper;
    private final WorkflowExpressionService expressions;

    /** 注入 JSON 与受限表达式服务。 */
    public WorkflowDataNodeExecutor(ObjectMapper objectMapper, WorkflowExpressionService expressions) {
        this.objectMapper = objectMapper;
        this.expressions = expressions;
    }

    /** 返回数据执行器支持的节点集合。 */
    @Override
    public Set<String> types() { return TYPES; }

    /** 按节点类型执行确定性数据处理。 */
    @Override
    public Result execute(Request request) {
        return switch (request.type()) {
            case "SWITCH" -> executeSwitch(request.config(), request.context());
            case "MERGE" -> Result.output(executeMerge(request.config(), request.context()));
            case "SET_VARIABLE", "TRANSFORM" -> Result.output(resolveOutput(request.config(), request.context()));
            case "TEMPLATE" -> Result.output(objectMapper.createObjectNode().put("text",
                expressions.resolve(request.config().path("template"), request.context()).asText("")));
            case "JSON_PARSE" -> Result.output(parseJson(expressions.resolve(request.config().path("value"), request.context())));
            case "JSON_VALIDATE" -> Result.output(validateNode(request.config(), request.context()));
            case "FILTER" -> Result.output(filter(request.config(), request.context()));
            case "SORT" -> Result.output(sort(request.config(), request.context()));
            case "AGGREGATE" -> Result.output(aggregate(request.config(), request.context()));
            case "CSV" -> Result.output(csv(request.config(), request.context()));
            case "STRUCTURED_OUTPUT" -> Result.output(structured(request.config(), request.context()));
            case "DOCUMENT_EXTRACTOR" -> Result.output(document(request.config(), request.context()));
            default -> throw new BusinessException("workflow.nodeTypeInvalid");
        };
    }

    /** 按顺序匹配多路条件并返回命名端口。 */
    private Result executeSwitch(ObjectNode config, ObjectNode context) {
        int index = 0;
        for (JsonNode item : config.path("cases")) {
            if (expressions.condition(item.path("condition"), context)) {
                String branch = item.path("branch").asText("case-" + index);
                return Result.branch(objectMapper.createObjectNode().put("matched", true).put("branch", branch), branch);
            }
            index++;
        }
        String fallback = config.path("defaultBranch").asText("default");
        return Result.branch(objectMapper.createObjectNode().put("matched", false).put("branch", fallback), fallback);
    }

    /** 将多个已解析值按数组或对象模式合并。 */
    private JsonNode executeMerge(ObjectNode config, ObjectNode context) {
        JsonNode values = expressions.resolve(config.path("values"), context);
        String mode = config.path("mode").asText("ARRAY").toUpperCase(Locale.ROOT);
        if ("OBJECT".equals(mode)) {
            ObjectNode output = objectMapper.createObjectNode();
            if (values.isArray()) values.forEach(value -> {
                if (!value.isObject()) throw new BusinessException("workflow.dataInputInvalid");
                output.setAll((ObjectNode) value);
            });
            else if (values.isObject()) output.setAll((ObjectNode) values);
            else throw new BusinessException("workflow.dataInputInvalid");
            return output;
        }
        return values.isArray() ? values : objectMapper.createArrayNode().add(values);
    }

    /** 解析节点显式 output/value 配置并保留 JSON 类型。 */
    private JsonNode resolveOutput(ObjectNode config, ObjectNode context) {
        JsonNode value = config.has("output") ? config.path("output") : config.path("value");
        return expressions.resolve(value, context);
    }

    /** 将文本 JSON 解析为结构化值，结构化输入直接复制。 */
    private JsonNode parseJson(JsonNode value) {
        if (!value.isTextual()) return value.deepCopy();
        try { return objectMapper.readTree(value.asText()); }
        catch (Exception exception) { throw new BusinessException("workflow.jsonParseInvalid"); }
    }

    /** 校验解析后的值并返回明确校验结果。 */
    private JsonNode validateNode(ObjectNode config, ObjectNode context) {
        JsonNode value = expressions.resolve(config.path("value"), context);
        List<String> errors = new ArrayList<>();
        validateSchema(config.path("schema"), value, "$", errors);
        if (!errors.isEmpty() && config.path("failOnError").asBoolean(true)) {
            throw new BusinessException("workflow.jsonSchemaInvalid", String.join("; ", errors));
        }
        ObjectNode output = objectMapper.createObjectNode().put("valid", errors.isEmpty());
        output.set("value", value);
        output.set("errors", objectMapper.valueToTree(errors));
        return output;
    }

    /** 递归校验常用 JSON Schema 类型、必填字段、数组元素和长度边界。 */
    private void validateSchema(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!schema.isObject()) { errors.add(path + " schema invalid"); return; }
        String type = schema.path("type").asText("");
        boolean typeValid = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber(); case "boolean" -> value.isBoolean();
            case "null" -> value.isNull(); case "" -> true; default -> false;
        };
        if (!typeValid) { errors.add(path + " expected " + type); return; }
        if (value.isObject()) {
            schema.path("required").forEach(required -> {
                String name = required.asText();
                if (!value.has(name) || value.path(name).isNull()) errors.add(path + "." + name + " required");
            });
            schema.path("properties").fields().forEachRemaining(entry -> {
                if (value.has(entry.getKey())) validateSchema(entry.getValue(), value.path(entry.getKey()), path + "." + entry.getKey(), errors);
            });
        }
        if (value.isArray() && schema.path("items").isObject()) {
            for (int index = 0; index < value.size(); index++) validateSchema(schema.path("items"), value.get(index), path + "[" + index + "]", errors);
        }
        if (value.isTextual() && schema.has("maxLength") && value.asText().length() > schema.path("maxLength").asInt()) {
            errors.add(path + " too long");
        }
    }

    /** 使用 item 上下文过滤数组。 */
    private JsonNode filter(ObjectNode config, ObjectNode context) {
        JsonNode collection = expressions.resolve(config.path("collection"), context);
        if (!collection.isArray()) throw new BusinessException("workflow.dataInputInvalid");
        ArrayNode output = objectMapper.createArrayNode();
        for (int index = 0; index < collection.size(); index++) {
            ObjectNode itemContext = context.deepCopy();
            itemContext.set("item", collection.get(index));
            itemContext.put("itemIndex", index);
            if (expressions.condition(config.path("condition"), itemContext)) output.add(collection.get(index));
        }
        return output;
    }

    /** 按受限点路径对数组执行稳定排序。 */
    private JsonNode sort(ObjectNode config, ObjectNode context) {
        JsonNode collection = expressions.resolve(config.path("collection"), context);
        if (!collection.isArray()) throw new BusinessException("workflow.dataInputInvalid");
        String path = config.path("path").asText("");
        boolean descending = "DESC".equalsIgnoreCase(config.path("direction").asText("ASC"));
        List<JsonNode> values = new ArrayList<>(); collection.forEach(value -> values.add(value.deepCopy()));
        Comparator<JsonNode> comparator = Comparator.comparing(value -> comparable(path.isBlank() ? value : expressions.lookup(value, path)),
            Comparator.nullsFirst(Comparator.naturalOrder()));
        if (descending) comparator = comparator.reversed();
        values.sort(comparator);
        return objectMapper.valueToTree(values);
    }

    /** 将 JSON 标量转换为稳定可比较值。 */
    private String comparable(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isNumber()) return String.format(Locale.ROOT, "%040.10f", value.asDouble());
        return value.asText(value.toString());
    }

    /** 对数组执行计数、求和、平均、最小或最大聚合。 */
    private JsonNode aggregate(ObjectNode config, ObjectNode context) {
        JsonNode collection = expressions.resolve(config.path("collection"), context);
        if (!collection.isArray()) throw new BusinessException("workflow.dataInputInvalid");
        String operation = config.path("operation").asText("COUNT").toUpperCase(Locale.ROOT);
        String path = config.path("path").asText("");
        if ("COUNT".equals(operation)) return objectMapper.createObjectNode().put("value", collection.size());
        List<BigDecimal> numbers = new ArrayList<>();
        collection.forEach(item -> {
            JsonNode value = path.isBlank() ? item : expressions.lookup(item, path);
            try { numbers.add(value.decimalValue()); }
            catch (Exception exception) { throw new BusinessException("workflow.dataInputInvalid"); }
        });
        if (numbers.isEmpty()) throw new BusinessException("workflow.dataInputInvalid");
        BigDecimal value = switch (operation) {
            case "SUM" -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "AVG" -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(numbers.size()), java.math.MathContext.DECIMAL128);
            case "MIN" -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
            case "MAX" -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
            default -> throw new BusinessException("workflow.dataOperationInvalid");
        };
        return objectMapper.createObjectNode().put("value", value);
    }

    /** 在对象数组和带表头 CSV 文本之间转换。 */
    private JsonNode csv(ObjectNode config, ObjectNode context) {
        String operation = config.path("operation").asText("PARSE").toUpperCase(Locale.ROOT);
        JsonNode value = expressions.resolve(config.path("value"), context);
        try {
            if ("PARSE".equals(operation)) {
                CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
                try (CSVParser parser = format.parse(new StringReader(value.asText("")))) {
                    ArrayNode rows = objectMapper.createArrayNode();
                    parser.forEach(record -> rows.add(objectMapper.valueToTree(record.toMap())));
                    return rows;
                }
            }
            if (!value.isArray()) throw new BusinessException("workflow.dataInputInvalid");
            LinkedHashMap<String, Boolean> headers = new LinkedHashMap<>();
            value.forEach(row -> row.fieldNames().forEachRemaining(name -> headers.put(name, true)));
            StringWriter writer = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(headers.keySet().toArray(String[]::new)).get())) {
                for (JsonNode row : value) {
                    List<String> record = headers.keySet().stream().map(name -> row.path(name).asText("")).toList();
                    printer.printRecord(record);
                }
            }
            return objectMapper.createObjectNode().put("text", writer.toString());
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.csvInvalid"); }
    }

    /** 解析并校验结构化输出。 */
    private JsonNode structured(ObjectNode config, ObjectNode context) {
        JsonNode value = parseJson(expressions.resolve(config.path("value"), context));
        ObjectNode validation = objectMapper.createObjectNode();
        validation.set("value", value); validation.set("schema", config.path("schema")); validation.put("failOnError", true);
        return validateNode(validation, objectMapper.createObjectNode());
    }

    /** 从 Base64 或文本内容中提取文档正文和元数据。 */
    private JsonNode document(ObjectNode config, ObjectNode context) {
        JsonNode resolved = expressions.resolve(config, context);
        byte[] bytes;
        try {
            bytes = resolved.hasNonNull("base64") ? Base64.getDecoder().decode(resolved.path("base64").asText())
                : resolved.path("content").asText("").getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) throw new BusinessException("workflow.documentEmpty");
            Metadata metadata = new Metadata();
            if (resolved.hasNonNull("fileName")) metadata.set("resourceName", resolved.path("fileName").asText());
            BodyContentHandler handler = new BodyContentHandler(resolved.path("maxCharacters").asInt(1_000_000));
            new AutoDetectParser().parse(new ByteArrayInputStream(bytes), handler, metadata, new ParseContext());
            ObjectNode output = objectMapper.createObjectNode().put("text", handler.toString());
            ObjectNode values = output.putObject("metadata");
            for (String name : metadata.names()) values.put(name, metadata.get(name));
            return output;
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.documentInvalid"); }
    }
}
