package com.baseai.platform.config;

import com.baseai.platform.security.AuthInterceptor;
import com.baseai.platform.web.TraceIdInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web MVC 拦截器、跨域和公开路径配置。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final TraceIdInterceptor traceIdInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, TraceIdInterceptor traceIdInterceptor) {
        this.authInterceptor = authInterceptor;
        this.traceIdInterceptor = traceIdInterceptor;
    }

    /** 先建立 traceId 上下文，再执行统一认证和权限校验。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(traceIdInterceptor).order(Ordered.HIGHEST_PRECEDENCE).addPathPatterns("/api/**");
        registry.addInterceptor(authInterceptor).order(Ordered.HIGHEST_PRECEDENCE + 1).addPathPatterns("/api/**")
            .excludePathPatterns("/api/auth/login", "/api/open/**", "/api/internal/**");
    }

    /** 允许本地开发跨域，生产由前端服务执行同源代理。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*").exposedHeaders("X-Request-Id", "X-Trace-Id").allowCredentials(false);
    }
}
