package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailDeliveryServiceTest {
    /** 中文和英文界面必须向所选路由发送语义一致的本地化测试邮件。 */
    @ParameterizedTest
    @CsvSource({
        "zh-CN,邮件路由测试,这是一封邮件路由测试邮件。",
        "en-US,Mail route test,This is a mail route test message."
    })
    void sendsLocalizedTestMessageToSelectedRoute(String languageTag, String expectedSubject,
                                                   String expectedBodyFragment) {
        MailManagementService management = mock(MailManagementService.class);
        MailDeliveryClient client = mock(MailDeliveryClient.class);
        MailManagementService.ResolvedRoute route = route();
        when(management.resolveRoute(7L)).thenReturn(route);
        when(client.send(org.mockito.ArgumentMatchers.eq(route), anyString(), anyString()))
            .thenReturn(Map.of("sent", true));
        MailDeliveryService service = service(management, client, "en-US");

        MailDeliveryService.DeliveryResult result = service.sendTest(7L, Locale.forLanguageTag(languageTag));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).send(org.mockito.ArgumentMatchers.eq(route), subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo(expectedSubject);
        assertThat(body.getValue()).contains(expectedBodyFragment);
        assertThat(result).isEqualTo(new MailDeliveryService.DeliveryResult("ORDER_FAILURE", true));
        verify(management, never()).resolve(anyString());
    }

    /** Worker 未确认发送成功时测试接口不得返回成功结果。 */
    @Test
    void rejectsUnconfirmedTestDelivery() {
        MailManagementService management = mock(MailManagementService.class);
        MailDeliveryClient client = mock(MailDeliveryClient.class);
        MailManagementService.ResolvedRoute route = route();
        when(management.resolveRoute(7L)).thenReturn(route);
        when(client.send(org.mockito.ArgumentMatchers.eq(route), anyString(), anyString()))
            .thenReturn(Map.of("sent", false));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service(management, client, "en-US").sendTest(7L, Locale.US));

        assertThat(exception.getStatus()).isEqualTo(502);
        assertThat(exception.getMessageKey()).isEqualTo("mail.sendFailed");
    }

    /** 缺少请求语言时必须使用系统默认语言。 */
    @Test
    void usesConfiguredLocaleWhenRequestLocaleIsMissing() {
        MailManagementService management = mock(MailManagementService.class);
        MailDeliveryClient client = mock(MailDeliveryClient.class);
        when(management.resolveRoute(7L)).thenReturn(route());
        when(client.send(org.mockito.ArgumentMatchers.any(), anyString(), anyString()))
            .thenReturn(Map.of("sent", true));

        service(management, client, "zh-CN").sendTest(7L, null);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(client).send(org.mockito.ArgumentMatchers.any(), subject.capture(), anyString());
        assertThat(subject.getValue()).isEqualTo("邮件路由测试");
    }

    /** 使用真实资源包创建待测发送服务。 */
    private MailDeliveryService service(MailManagementService management, MailDeliveryClient client,
                                        String defaultLocale) {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        PlatformProperties properties = new PlatformProperties();
        properties.getI18n().setDefaultLocale(defaultLocale);
        return new MailDeliveryService(management, client, messages, properties);
    }

    /** 创建包含主送和抄送人的精确路由。 */
    private MailManagementService.ResolvedRoute route() {
        return new MailManagementService.ResolvedRoute("ORDER_FAILURE", "smtp.example.com", 587,
            "sender@example.com", "sender@example.com", "STARTTLS", "secret",
            List.of("to@example.com"), List.of("cc@example.com"));
    }
}
