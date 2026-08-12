package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 持久化可重新本地化的工作流业务错误，并兼容历史纯文本记录。 */
@Service
public class WorkflowErrorMessageService {
    private static final String PREFIX = "@i18n:";
    private static final int MAX_PERSISTED_LENGTH = 2000;
    private static final int MAX_ARGUMENT_LENGTH = 500;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    /** 注入 JSON 编解码器和统一消息源。 */
    public WorkflowErrorMessageService(ObjectMapper objectMapper, MessageSource messageSource) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    /** 将业务异常保存为消息键结构，未知异常仅保存安全通用错误。 */
    public String encode(Throwable exception) {
        if (exception instanceof BusinessException business) {
            return encode(business.getMessageKey(), business.getMessageArguments());
        }
        return encode("workflow.executionFailed");
    }

    /** 将消息键和受限参数编码为不超过数据库字段上限的结构化文本。 */
    public String encode(String messageKey, Object... arguments) {
        ObjectNode payload = objectMapper.createObjectNode().put("key", messageKey);
        ArrayNode values = payload.putArray("arguments");
        if (arguments != null) {
            for (Object argument : arguments) values.add(normalizeArgument(argument));
        }
        String encoded = PREFIX + payload;
        if (encoded.length() <= MAX_PERSISTED_LENGTH) return encoded;
        payload.putArray("arguments");
        return PREFIX + payload;
    }

    /** 按当前请求语言解析结构化错误，历史纯文本和损坏记录保持原样。 */
    public String localize(String persisted) {
        return localize(persisted, LocaleContextHolder.getLocale());
    }

    /** 按指定语言解析结构化错误，供异步任务追踪保存稳定可读文本。 */
    public String localize(String persisted, Locale locale) {
        if (persisted == null || persisted.isBlank() || !persisted.startsWith(PREFIX)) return persisted;
        try {
            JsonNode payload = objectMapper.readTree(persisted.substring(PREFIX.length()));
            String key = payload.path("key").asText("");
            if (key.isBlank()) return persisted;
            List<Object> arguments = new ArrayList<>();
            payload.path("arguments").forEach(value -> arguments.add(argumentValue(value)));
            return messageSource.getMessage(key, arguments.toArray(), locale);
        } catch (RuntimeException | java.io.IOException exception) {
            return messageSource.getMessage("workflow.executionFailed", null, locale);
        }
    }

    /** 将持久化参数限制为消息格式化所需的安全标量。 */
    private String normalizeArgument(Object value) {
        String normalized = value == null ? "" : String.valueOf(value);
        return normalized.length() <= MAX_ARGUMENT_LENGTH ? normalized : normalized.substring(0, MAX_ARGUMENT_LENGTH);
    }

    /** 将 JSON 标量恢复为消息格式化参数。 */
    private Object argumentValue(JsonNode value) {
        if (value.isNumber()) return value.numberValue();
        if (value.isBoolean()) return value.booleanValue();
        return value.asText("");
    }
}
