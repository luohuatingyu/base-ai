package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/** 解析人工选择的邮件路由并委托 Python Worker 发送测试邮件。 */
@Service
public class MailDeliveryService {
    private final MailManagementService managementService;
    private final MailDeliveryClient deliveryClient;
    private final MessageSource messageSource;
    private final PlatformProperties properties;

    /** 注入邮件路由管理、Worker 客户端、国际化资源和系统语言配置。 */
    public MailDeliveryService(MailManagementService managementService, MailDeliveryClient deliveryClient,
                               MessageSource messageSource, PlatformProperties properties) {
        this.managementService = managementService;
        this.deliveryClient = deliveryClient;
        this.messageSource = messageSource;
        this.properties = properties;
    }

    /** 使用当前界面语言向人工选中的精确路由发送固定测试邮件。 */
    public DeliveryResult sendTest(Long routeId, Locale locale) {
        MailManagementService.ResolvedRoute route = managementService.resolveRoute(routeId);
        Locale requestedLocale = locale == null ? systemLocale() : locale;
        Map<String, Object> workerResult = deliveryClient.send(route,
            messageSource.getMessage("mail.test.subject", null, requestedLocale),
            messageSource.getMessage("mail.test.body", null, requestedLocale));
        if (!Boolean.TRUE.equals(workerResult.get("sent"))) throw new BusinessException(502, "mail.sendFailed");
        return new DeliveryResult(route.businessCode(), true);
    }

    /** 将已通过启动配置校验的系统语言标签转换为 Locale。 */
    private Locale systemLocale() {
        return Locale.forLanguageTag(properties.getI18n().getDefaultLocale());
    }

    public record DeliveryResult(String routeCode, boolean sent) { }
}
