package com.baseai.platform.trace;

import com.baseai.platform.automation.ApiTriggerController;
import com.baseai.platform.automation.ApiTriggerSecurityConfigurationController;
import com.baseai.platform.automation.ApiTriggerTrackedExecutionService;
import com.baseai.platform.controller.AiChatController;
import com.baseai.platform.controller.MailManagementController;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.security.AuthUser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** AI 对话按页面与 API Key 认证区分入口，其他固定入口不受影响。 */
    @Test
    void aiChatTriggerEntryUsesAuthenticationType() {
        TraceType chat = Arrays.stream(AiChatController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(TraceType.class))
            .filter(annotation -> annotation != null && "AI_CHAT".equals(annotation.value()))
            .findFirst().orElseThrow();
        AuthUser webUser = new AuthUser(1L, "web", Set.of("USER"), Set.of(),
            AuthenticationType.TOKEN, null, null);
        AuthUser apiUser = new AuthUser(1L, "api", Set.of("USER"), Set.of(),
            AuthenticationType.API_KEY, 7L, "integration");

        assertTrue(chat.authenticationTriggerEntry());
        assertEquals("WEB_UI", TraceTrackingAspect.resolveTriggerEntry(chat, webUser));
        assertEquals("API_KEY", TraceTrackingAspect.resolveTriggerEntry(chat, apiUser));

        TraceType fixed = Arrays.stream(ApiTriggerController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(TraceType.class))
            .filter(annotation -> annotation != null && "API_TRIGGER_EXECUTE".equals(annotation.value()))
            .findFirst().orElseThrow();
        assertEquals("MANUAL", TraceTrackingAspect.resolveTriggerEntry(fixed, apiUser));
    }
}
