package com.baseai.platform.trace;

import com.baseai.platform.config.PlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

/** 统一判断 HTTP 接口是否应忽略链路追踪。 */
@Component
public class TraceTrackingPolicy {
    private final PlatformProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public TraceTrackingPolicy(PlatformProperties properties) {
        this.properties = properties;
    }

    /** 根据请求配置和控制器注解判断接口是否忽略。 */
    public boolean isIgnored(HttpServletRequest request, Object handler) {
        if (isConfiguredIgnored(request)) return true;
        if (!(handler instanceof HandlerMethod handlerMethod)) return false;
        return isAnnotatedIgnored(handlerMethod.getMethod(), handlerMethod.getBeanType());
    }

    /** 为任务切面复用相同的配置和注解忽略规则。 */
    public boolean isIgnored(HttpServletRequest request, Method method, Class<?> targetType, boolean controllerInvocation) {
        if (isAnnotatedIgnored(method, targetType)) return true;
        return controllerInvocation && request != null && isConfiguredIgnored(request);
    }

    /** 判断 HTTP 方法或路径是否命中外部忽略配置。 */
    private boolean isConfiguredIgnored(HttpServletRequest request) {
        if (properties.getTraceTracking().getExcludedMethods().stream()
            .anyMatch(value -> value.equalsIgnoreCase(request.getMethod()))) return true;
        return properties.getTraceTracking().getExcludedPaths().stream()
            .anyMatch(pattern -> pathMatcher.match(pattern, request.getRequestURI()));
    }

    /** 判断方法或控制器类型是否声明忽略追踪。 */
    private boolean isAnnotatedIgnored(Method method, Class<?> targetType) {
        return AnnotatedElementUtils.hasAnnotation(method, TraceIgnored.class)
            || AnnotatedElementUtils.hasAnnotation(targetType, TraceIgnored.class);
    }
}
