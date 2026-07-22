package com.baseai.platform.logging;

import com.baseai.platform.domain.OperationLog;
import com.baseai.platform.trace.TraceRequestSnapshotSanitizer;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Set;

@Aspect
@Component
public class OperationAuditAspect {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final SystemAuditAsyncWriter writer;
    private final TraceRequestSnapshotSanitizer sanitizer;

    public OperationAuditAspect(SystemAuditAsyncWriter writer, TraceRequestSnapshotSanitizer sanitizer) {
        this.writer = writer;
        this.sanitizer = sanitizer;
    }

    /** 对登录以外的控制器写操作记录脱敏审计日志。 */
    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object audit(ProceedingJoinPoint point) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return point.proceed();
        HttpServletRequest request = attributes.getRequest();
        if (!MUTATING.contains(request.getMethod()) || request.getRequestURI().startsWith("/api/internal/") || request.getRequestURI().equals("/api/auth/login")) {
            return point.proceed();
        }
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            return point.proceed();
        } catch (Throwable throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            save(point, request, started, failure);
        }
    }

    /** 构造并保存单条操作审计记录。 */
    private void save(ProceedingJoinPoint point, HttpServletRequest request, long started, Throwable failure) {
        try {
            MethodSignature signature = (MethodSignature) point.getSignature();
            AuthUser user = AuthContext.current();
            OperationLog log = new OperationLog();
            if (user != null) { log.setUserId(user.id()); log.setUsername(user.username()); }
            log.setMethod(request.getMethod());
            log.setPath(request.getRequestURI());
            log.setController(signature.getDeclaringType().getSimpleName());
            log.setAction(signature.getName());
            log.setRequestData(sanitizer.sanitize(request, signature.getParameterNames(), point.getArgs()).paramsJson());
            log.setIpAddress(clientIp(request));
            log.setDurationMs((System.nanoTime() - started) / 1_000_000);
            log.setSuccess(failure == null);
            log.setErrorMessage(failure == null ? null : limit(failure.getMessage(), 1000));
            log.setOperatedAt(Instant.now());
            writer.writeOperation(log);
        } catch (Exception exception) {
            org.slf4j.LoggerFactory.getLogger(OperationAuditAspect.class)
                .warn("event=operation_audit_enqueue_failed method={} path={}", request.getMethod(), request.getRequestURI(), exception);
        }
    }

    private String clientIp(HttpServletRequest request) { String forwarded = request.getHeader("X-Forwarded-For"); return forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim(); }
    private String limit(String value, int length) { if (value == null) return null; return value.substring(0, Math.min(length, value.length())); }
}
