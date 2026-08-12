package com.baseai.platform.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class I18nConfigTest {
    /** 未设置配置时应保持默认英文。 */
    @Test
    void defaultsToEnglish() {
        Locale locale = resolver(new PlatformProperties()).resolveLocale(new MockHttpServletRequest());

        assertEquals(Locale.US, locale);
    }

    /** 两种受支持配置都应解析为对应 Locale。 */
    @ParameterizedTest
    @ValueSource(strings = {"en-US", "zh-CN"})
    void acceptsSupportedDefaultLocales(String languageTag) {
        PlatformProperties properties = properties(languageTag);

        Locale locale = resolver(properties).resolveLocale(new MockHttpServletRequest());

        assertEquals(Locale.forLanguageTag(languageTag), locale);
    }

    /** app.i18n.default-locale 应能绑定到类型安全配置。 */
    @Test
    void bindsDefaultLocaleProperty() {
        PlatformProperties properties = new Binder(new MapConfigurationPropertySource(
            Map.of("app.i18n.default-locale", "zh-CN")))
            .bind("app", Bindable.of(PlatformProperties.class)).orElseThrow(IllegalStateException::new);

        assertEquals("zh-CN", properties.getI18n().getDefaultLocale());
    }

    /** 显式请求语言应优先于配置的默认语言。 */
    @Test
    void requestLanguageOverridesConfiguredDefault() {
        LocaleResolver resolver = resolver(properties("zh-CN"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US");

        assertEquals(Locale.US, resolver.resolveLocale(request));
    }

    /** lang 参数应覆盖请求头，供无需修改请求头的开放接口调用方使用。 */
    @Test
    void languageParameterOverridesRequestHeader() {
        LocaleResolver resolver = resolver(properties("en-US"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US");
        request.setParameter("lang", "zh-CN");

        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveLocale(request));
    }

    /** 非法 lang 参数不得触发服务端异常，应安全回退请求头语言。 */
    @Test
    void unsupportedLanguageParameterFallsBackToRequestHeader() {
        LocaleResolver resolver = resolver(properties("en-US"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "zh-CN");
        request.setParameter("lang", "fr-FR");

        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveLocale(request));
    }

    /** 空值和不支持语言必须阻止配置生效。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"fr-FR", "zh", "english"})
    void rejectsUnsupportedDefaultLocales(String languageTag) {
        PlatformProperties properties = properties(languageTag);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> resolver(properties));

        assertEquals("app.i18n.default-locale must be one of: en-US, zh-CN", exception.getMessage());
    }

    /** 使用指定默认语言创建平台配置。 */
    private static PlatformProperties properties(String languageTag) {
        PlatformProperties properties = new PlatformProperties();
        properties.getI18n().setDefaultLocale(languageTag);
        return properties;
    }

    /** 使用平台配置创建 LocaleResolver。 */
    private static LocaleResolver resolver(PlatformProperties properties) {
        return new I18nConfig(properties).localeResolver();
    }
}
