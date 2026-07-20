package com.baseai.platform.job;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.service.TaskJobService;
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
public class JobTrackingAspect {
    public static final String JOB_ID_HEADER = "X-Job-Id";
    private final TaskJobService taskJobService;
    private final JobRuntimeRegistry runtimeRegistry;
    private final JobRequestSnapshotSanitizer sanitizer;
    private final PlatformProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JobTrackingAspect(TaskJobService taskJobService, JobRuntimeRegistry runtimeRegistry,
                             JobRequestSnapshotSanitizer sanitizer, PlatformProperties properties) {
        this.taskJobService = taskJobService;
        this.runtimeRegistry = runtimeRegistry;
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    /** 默认跟踪未排除的公开控制器方法。 */
    @Around("@within(org.springframework.web.bind.annotation.RestController) && execution(public * *(..))")
    public Object trackController(ProceedingJoinPoint joinPoint) throws Throwable {
        return track(joinPoint, true);
    }

    /** 跟踪显式声明 JobType 的非控制器服务或定时方法。 */
    @Around("@annotation(com.baseai.platform.job.JobType) && !@within(org.springframework.web.bind.annotation.RestController)")
    public Object trackAnnotatedService(ProceedingJoinPoint joinPoint) throws Throwable {
        return track(joinPoint, false);
    }

    /** 创建任务、绑定上下文并维护统一终态。 */
    private Object track(ProceedingJoinPoint joinPoint, boolean controllerInvocation) throws Throwable {
        if (JobContextHolder.current().isPresent()) return joinPoint.proceed();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        JobType metadata = AnnotatedElementUtils.findMergedAnnotation(method, JobType.class);
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes == null ? null : attributes.getRequest();
        if (shouldIgnore(joinPoint, request, controllerInvocation)) return joinPoint.proceed();
        Long ownerId = resolveOwner(metadata, signature.getParameterNames(), joinPoint.getArgs());
        if (ownerId == null) return joinPoint.proceed();
        String taskType = metadata == null ? method.getDeclaringClass().getSimpleName() + "." + method.getName() : metadata.value();
        String triggerEntry = metadata == null ? "API" : metadata.triggerEntry();
        JobSnapshot snapshot = request != null && (metadata == null || metadata.captureRequest())
            ? sanitizer.sanitize(request, signature.getParameterNames(), joinPoint.getArgs()) : new JobSnapshot("{}", "{}");
        String jobId = taskJobService.bindOrCreate(request == null ? null : request.getHeader(JOB_ID_HEADER), ownerId,
            taskType, triggerEntry, request == null ? "INTERNAL" : request.getMethod(),
            request == null ? method.toGenericString() : request.getRequestURI(), snapshot);
        HttpServletResponse response = attributes == null ? null : attributes.getResponse();
        if (response != null) response.setHeader(JOB_ID_HEADER, jobId);
        JobRuntime runtime = runtimeRegistry.create(jobId);
        runtime.registerThread(Thread.currentThread());
        JobContext context = new JobContext(jobId, ownerId, taskType, triggerEntry, runtime.token(), runtime);
        try (JobContextHolder.Scope ignored = JobContextHolder.bind(context)) {
            Object result = joinPoint.proceed();
            context.checkpoint();
            taskJobService.markSuccess(jobId);
            return result;
        } catch (JobCancelledException exception) {
            taskJobService.completeCancellation(jobId);
            throw exception;
        } catch (Throwable throwable) {
            taskJobService.markFailed(jobId, throwable.getMessage());
            throw throwable;
        } finally {
            runtime.unregisterThread(Thread.currentThread());
            runtimeRegistry.remove(jobId);
        }
    }

    /** 判断注解、HTTP 方法和路径是否排除任务跟踪。 */
    private boolean shouldIgnore(ProceedingJoinPoint joinPoint, HttpServletRequest request, boolean controllerInvocation) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (AnnotatedElementUtils.hasAnnotation(method, JobIgnored.class)
            || AnnotatedElementUtils.hasAnnotation(joinPoint.getTarget().getClass(), JobIgnored.class)) return true;
        if (!controllerInvocation || request == null) return false;
        if (properties.getJobTracking().getExcludedMethods().stream()
            .anyMatch(value -> value.equalsIgnoreCase(request.getMethod()))) return true;
        return properties.getJobTracking().getExcludedPaths().stream()
            .anyMatch(pattern -> pathMatcher.match(pattern, request.getRequestURI()));
    }

    /** 从登录上下文或注解指定的方法参数解析任务所有者。 */
    private Long resolveOwner(JobType metadata, String[] names, Object[] values) {
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
