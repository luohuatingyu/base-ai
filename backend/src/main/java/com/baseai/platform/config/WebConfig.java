package com.baseai.platform.config;

import com.baseai.platform.security.AuthInterceptor;
import com.baseai.platform.web.HttpRequestTraceInterceptor;
import com.baseai.platform.web.TraceIdInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Web MVC 拦截器、跨域和公开路径配置。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final TraceIdInterceptor traceIdInterceptor;
    private final HttpRequestTraceInterceptor httpRequestTraceInterceptor;
    private final List<String> allowedOrigins;

    public WebConfig(AuthInterceptor authInterceptor, TraceIdInterceptor traceIdInterceptor,
                     HttpRequestTraceInterceptor httpRequestTraceInterceptor, PlatformProperties properties) {
        this.authInterceptor = authInterceptor;
        this.traceIdInterceptor = traceIdInterceptor;
        this.httpRequestTraceInterceptor = httpRequestTraceInterceptor;
        this.allowedOrigins = normalizeOrigins(properties.getCors().getAllowedOrigins());
    }

    /** 先建立 traceId 上下文，再执行统一认证和权限校验。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(traceIdInterceptor).order(Ordered.HIGHEST_PRECEDENCE).addPathPatterns("/api/**");
        registry.addInterceptor(authInterceptor).order(Ordered.HIGHEST_PRECEDENCE + 1).addPathPatterns("/api/**")
            .excludePathPatterns("/api/auth/login", "/api/open/**", "/api/internal/**", "/api/workflow-hooks/**");
        registry.addInterceptor(httpRequestTraceInterceptor).order(Ordered.HIGHEST_PRECEDENCE + 2).addPathPatterns("/api/**");
    }

    /** 仅为显式配置的精确来源启用跨域，空配置保持同源访问。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) return;
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*").exposedHeaders("X-Request-Id", "X-Trace-Id")
            .allowCredentials(false).maxAge(600);
    }

    /** 规范并校验 Origin，拒绝通配符、路径和非 HTTP 协议。 */
    static List<String> normalizeOrigins(List<String> values) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.isBlank()) continue;
            try {
                URI uri = URI.create(value.trim());
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getPort() > 65535
                    || value.contains("*")) {
                    throw new IllegalArgumentException("APP_CORS_ALLOWED_ORIGINS contains an invalid origin");
                }
                origins.add(new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null)
                    .toString());
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalArgumentException("APP_CORS_ALLOWED_ORIGINS contains an invalid origin", exception);
            }
        }
        return List.copyOf(origins);
    }
}
