package com.baseai.platform.config;

import com.baseai.platform.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web MVC 拦截器、跨域和公开路径配置。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) { this.authInterceptor = authInterceptor; }

    /** 注册统一认证拦截器并排除公开及内部认证接口。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**")
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
