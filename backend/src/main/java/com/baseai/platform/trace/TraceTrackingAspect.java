package com.baseai.platform.trace;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.service.TaskTraceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Locale;

@Aspect
@Component
public class TraceTrackingAspect {
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private final TaskTraceService taskTraceService;
    private final TraceRuntimeRegistry runtimeRegistry;
    private final TraceRequestSnapshotSanitizer sanitizer;
    private final PlatformProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public TraceTrackingAspect(TaskTraceService taskTraceService, TraceRuntimeRegistry runtimeRegistry,
                             TraceRequestSnapshotSanitizer sanitizer, PlatformProperties properties) {
        this.taskTraceService = taskTraceService;
        this.runtimeRegistry = runtimeRegistry;
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    /** 默认跟踪未排除的公开控制器方法。 */
    @Around("@within(org.springframework.web.bind.annotation.RestController) && execution(public * *(..))")
    public Object trackController(ProceedingJoinPoint joinPoint) throws Throwable {
        return track(joinPoint, true);
    }

    /** 跟踪显式声明 TraceType 的非控制器服务或定时方法。 */
    @Around("@annotation(com.baseai.platform.trace.TraceType) && !@within(org.springframework.web.bind.annotation.RestController)")
    public Object trackAnnotatedService(ProceedingJoinPoint joinPoint) throws Throwable {
        return track(joinPoint, false);
    }

    /** 创建任务、绑定上下文并维护统一终态。 */
    private Object track(ProceedingJoinPoint joinPoint, boolean controllerInvocation) throws Throwable {
        if (TraceContextHolder.current().isPresent()) return joinPoint.proceed();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        TraceType metadata = AnnotatedElementUtils.findMergedAnnotation(method, TraceType.class);
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes == null ? null : attributes.getRequest();
        if (shouldIgnore(joinPoint, request, controllerInvocation)) return joinPoint.proceed();
        Long ownerId = resolveOwner(metadata, signature.getParameterNames(), joinPoint.getArgs());
        if (ownerId == null) return joinPoint.proceed();
        String taskType = metadata == null ? method.getDeclaringClass().getSimpleName() + "." + method.getName() : metadata.value();
        String triggerEntry = metadata == null ? "API" : metadata.triggerEntry();
        TraceSnapshot snapshot = request != null && (metadata == null || metadata.captureRequest())
            ? sanitizer.sanitize(request, signature.getParameterNames(), joinPoint.getArgs()) : new TraceSnapshot("{}", "{}");
        String traceId = taskTraceService.bindOrCreate(request == null ? null : request.getHeader(TRACE_ID_HEADER), ownerId,
            taskType, triggerEntry, request == null ? "INTERNAL" : request.getMethod(),
            request == null ? method.toGenericString() : request.getRequestURI(), snapshot);
        HttpServletResponse response = attributes == null ? null : attributes.getResponse();
        if (response != null) response.setHeader(TRACE_ID_HEADER, traceId);
        TraceRuntime runtime = runtimeRegistry.create(traceId);
        runtime.registerThread(Thread.currentThread());
        TraceContext context = new TraceContext(traceId, ownerId, taskType, triggerEntry, runtime.token(), runtime);
        try (TraceContextHolder.Scope ignored = TraceContextHolder.bind(context)) {
            Object result = joinPoint.proceed();
            context.checkpoint();
            taskTraceService.markSuccess(traceId);
            return result;
        } catch (TraceCancelledException exception) {
            taskTraceService.completeCancellation(traceId);
            throw exception;
        } catch (Throwable throwable) {
            if (runtime.token().isCancelled() || Thread.currentThread().isInterrupted()) {
                taskTraceService.completeCancellation(traceId);
                throw new TraceCancelledException(traceId);
            }
            taskTraceService.markFailed(traceId, throwable.getMessage());
            throw throwable;
        } finally {
            runtime.unregisterThread(Thread.currentThread());
            runtimeRegistry.remove(traceId);
        }
    }

    /** 判断注解、HTTP 方法和路径是否排除任务跟踪。 */
    private boolean shouldIgnore(ProceedingJoinPoint joinPoint, HttpServletRequest request, boolean controllerInvocation) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (AnnotatedElementUtils.hasAnnotation(method, TraceIgnored.class)
            || AnnotatedElementUtils.hasAnnotation(joinPoint.getTarget().getClass(), TraceIgnored.class)) return true;
        if (!controllerInvocation || request == null) return false;
        if (properties.getTraceTracking().getExcludedMethods().stream()
            .anyMatch(value -> value.equalsIgnoreCase(request.getMethod()))) return true;
        return properties.getTraceTracking().getExcludedPaths().stream()
            .anyMatch(pattern -> pathMatcher.match(pattern, request.getRequestURI()));
    }

    /** 从登录上下文或注解指定的方法参数解析任务所有者。 */
    private Long resolveOwner(TraceType metadata, String[] names, Object[] values) {
        AuthUser authUser = AuthContext.current();
        if (authUser != null) return authUser.id();
        if (metadata == null || metadata.ownerIdParameter().isBlank()) return null;
        for (int index = 0; names != null && index < names.length; index++) {
            if (names[index].toLowerCase(Locale.ROOT).equals(metadata.ownerIdParameter().toLowerCase(Locale.ROOT))
                && values[index] instanceof Number number) return number.longValue();
        }
        return null;
    }
}
