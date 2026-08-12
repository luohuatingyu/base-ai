package com.baseai.platform.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Locale;

/**
 * 国际化配置
 * 支持中文和英文两种语言
 */
@Configuration
public class I18nConfig {
    private static final List<Locale> SUPPORTED_LOCALES = List.of(Locale.SIMPLIFIED_CHINESE, Locale.US);
    private final PlatformProperties properties;

    /** 注入后端默认语言配置。 */
    public I18nConfig(PlatformProperties properties) { this.properties = properties; }

    /**
     * 配置语言解析器
     * 从请求头 Accept-Language 中获取语言设置
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new ParameterLocaleResolver();
        resolver.setDefaultLocale(resolveDefaultLocale(properties.getI18n().getDefaultLocale()));
        resolver.setSupportedLocales(SUPPORTED_LOCALES);
        return resolver;
    }

    /** 严格解析配置值，避免不支持的语言被静默接受。 */
    private Locale resolveDefaultLocale(String value) {
        return SUPPORTED_LOCALES.stream()
            .filter(locale -> locale.toLanguageTag().equalsIgnoreCase(value == null ? "" : value.trim()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "app.i18n.default-locale must be one of: en-US, zh-CN"));
    }

    /**
     * 配置消息源
     * 从 messages_*.properties 文件中加载消息
     */
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setCacheSeconds(3600); // 缓存1小时
        return messageSource;
    }

    /** 支持单次请求 lang 参数，并在无效时回退标准 Accept-Language 解析。 */
    private static final class ParameterLocaleResolver extends AcceptHeaderLocaleResolver {
        /** lang 参数优先于请求头，只接受平台明确支持的语言。 */
        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            String value = request.getParameter("lang");
            if (value != null) {
                Locale requested = SUPPORTED_LOCALES.stream()
                    .filter(locale -> locale.toLanguageTag().equalsIgnoreCase(value.trim()))
                    .findFirst().orElse(null);
                if (requested != null) return requested;
            }
            return super.resolveLocale(request);
        }
    }
}
