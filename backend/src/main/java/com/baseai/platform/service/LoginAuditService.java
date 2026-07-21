package com.baseai.platform.service;

import com.baseai.platform.logging.SystemAuditAsyncWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoginAuditService {
    private static final Logger log = LoggerFactory.getLogger(LoginAuditService.class);
    private final SystemAuditAsyncWriter writer;

    public LoginAuditService(SystemAuditAsyncWriter writer) { this.writer = writer; }

    /** 非阻塞投递登录日志，日志线程池满载时不影响认证结果。 */
    public void save(String username, AuthService.LoginMetadata metadata, boolean success, String message) {
        try {
            writer.writeLogin(username, metadata.ipAddress(), metadata.userAgent(), success, message);
        } catch (RuntimeException exception) {
            log.warn("event=login_audit_enqueue_failed username={} success={}", username, success, exception);
        }
    }
}
