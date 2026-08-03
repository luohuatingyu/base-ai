package com.baseai.platform.trace;

import com.baseai.platform.automation.ApiTriggerController;
import com.baseai.platform.automation.ApiTriggerSecurityConfigurationController;
import com.baseai.platform.automation.ApiTriggerTrackedExecutionService;
import com.baseai.platform.controller.AiChatController;
import com.baseai.platform.controller.MailManagementController;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TraceTypeCodeTest {
    /** 显式任务类型必须使用语言无关代码，不能持久化前端翻译键。 */
    @Test
    void annotatedTaskTypesUseStableCodes() {
        Set<String> codes = Arrays.stream(new Class<?>[]{
                AiChatController.class,
                MailManagementController.class,
                ApiTriggerController.class,
                ApiTriggerSecurityConfigurationController.class,
                ApiTriggerTrackedExecutionService.class
            })
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .map(method -> method.getAnnotation(TraceType.class))
            .filter(annotation -> annotation != null)
            .map(TraceType::value)
            .collect(Collectors.toSet());

        assertEquals(Set.of(
            "AI_CHAT",
            "API_TRIGGER_CREATE",
            "API_TRIGGER_UPDATE",
            "API_TRIGGER_DISABLE",
            "API_TRIGGER_VOID",
            "API_TRIGGER_EXECUTE",
            "API_TRIGGER_CRON",
            "API_TRIGGER_TEST",
            "API_TRIGGER_SECURITY_UPDATE",
            "MAIL_ACCOUNT_CREATE",
            "MAIL_ACCOUNT_UPDATE",
            "MAIL_ROUTE_CREATE",
            "MAIL_ROUTE_UPDATE",
            "MAIL_ROUTE_TEST"
        ), codes);
        assertFalse(codes.stream().anyMatch(code -> code.startsWith("tasks.")));
    }
}
