package com.baseai.platform.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

@Component
public class JobRequestSnapshotSanitizer {
    private static final Set<String> SENSITIVE = Set.of("password", "secret", "token", "authorization", "cookie", "api-key", "apikey");
    private final ObjectMapper objectMapper;

    public JobRequestSnapshotSanitizer(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    /** 序列化方法参数和请求头，并递归屏蔽敏感字段。 */
    public JobSnapshot sanitize(HttpServletRequest request, String[] names, Object[] values) {
        ObjectNode params = objectMapper.createObjectNode();
        for (int index = 0; index < values.length; index++) {
            String name = names != null && index < names.length ? names[index] : "arg" + index;
            if (isInfrastructure(values[index])) continue;
            params.set(name, sanitizeNode(name, objectMapper.valueToTree(values[index])));
        }
        ObjectNode headers = objectMapper.createObjectNode();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, isSensitive(name) ? "***" : limit(request.getHeader(name)));
        }
        return new JobSnapshot(write(params), write(headers));
    }

    /** 递归处理对象和数组中的敏感字段。 */
    private JsonNode sanitizeNode(String fieldName, JsonNode node) {
        if (isSensitive(fieldName)) return objectMapper.getNodeFactory().textNode("***");
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), sanitizeNode(entry.getKey(), entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(sanitizeNode(fieldName, item)));
            return result;
        }
        if (node.isTextual()) return objectMapper.getNodeFactory().textNode(limit(node.asText()));
        return node;
    }

    /** 排除 Servlet、响应和文件等基础设施对象。 */
    private boolean isInfrastructure(Object value) {
        return value instanceof jakarta.servlet.ServletRequest || value instanceof jakarta.servlet.ServletResponse
            || value instanceof org.springframework.web.multipart.MultipartFile;
    }

    private boolean isSensitive(String name) {
        String normalized = String.valueOf(name).toLowerCase(Locale.ROOT);
        return SENSITIVE.stream().anyMatch(normalized::contains);
    }
    private String limit(String value) { return value == null ? "" : value.substring(0, Math.min(2000, value.length())); }
    private String write(JsonNode value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { return "{}"; }
    }
}
