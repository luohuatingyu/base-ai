package com.baseai.platform.logging;

import com.baseai.platform.domain.LoginLog;
import com.baseai.platform.domain.OperationLog;
import com.baseai.platform.repository.LoginLogRepository;
import com.baseai.platform.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class SystemAuditAsyncWriter {
    private static final Logger log = LoggerFactory.getLogger(SystemAuditAsyncWriter.class);
    private final LoginLogRepository loginLogRepository;
    private final OperationLogRepository operationLogRepository;

    public SystemAuditAsyncWriter(LoginLogRepository loginLogRepository, OperationLogRepository operationLogRepository) {
        this.loginLogRepository = loginLogRepository;
        this.operationLogRepository = operationLogRepository;
    }

    /** 异步保存登录审计，避免认证请求等待审计数据库。 */
    @Async("auditTaskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeLogin(String username, String ipAddress, String userAgent, boolean success, String message) {
        try {
            LoginLog audit = new LoginLog();
            audit.setUsername(username);
            audit.setIpAddress(ipAddress);
            audit.setUserAgent(userAgent);
            audit.setSuccess(success);
            audit.setMessage(message);
            audit.setLoginAt(Instant.now());
            loginLogRepository.save(audit);
        } catch (RuntimeException exception) {
            log.error("event=login_audit_persist_failed username={} success={}", username, success, exception);
        }
    }

    /** 异步保存操作审计，避免写请求等待审计数据库。 */
    @Async("auditTaskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeOperation(OperationLog audit) {
        try {
            operationLogRepository.save(audit);
        } catch (RuntimeException exception) {
            log.error("event=operation_audit_persist_failed method={} path={}", audit.getMethod(), audit.getPath(), exception);
        }
    }
}
