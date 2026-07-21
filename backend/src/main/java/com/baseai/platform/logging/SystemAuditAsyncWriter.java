package com.baseai.platform.logging;

import com.baseai.platform.domain.LoginLog;
import com.baseai.platform.domain.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class SystemAuditAsyncWriter {
    private static final Logger log = LoggerFactory.getLogger(SystemAuditAsyncWriter.class);
    private final JdbcTemplate jdbcTemplate;

    public SystemAuditAsyncWriter(@Qualifier("auditJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 异步保存登录审计，避免认证请求等待审计数据库。 */
    @Async("auditTaskExecutor")
    public void writeLogin(String username, String ipAddress, String userAgent, boolean success, String message) {
        try {
            jdbcTemplate.update("""
                INSERT INTO sys_login_log(username, ip_address, user_agent, success, message, login_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, username, ipAddress, userAgent, success, Timestamp.from(Instant.now()));
        } catch (RuntimeException exception) {
            log.error("event=login_audit_persist_failed username={} success={}", username, success, exception);
        }
    }

    /** 异步保存操作审计，避免写请求等待审计数据库。 */
    @Async("auditTaskExecutor")
    public void writeOperation(OperationLog audit) {
        try {
            jdbcTemplate.update("""
                INSERT INTO sys_operation_log(user_id, username, method, path, controller, action, request_data,
                    ip_address, duration_ms, success, error_message, operated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, audit.getUserId(), audit.getUsername(), audit.getMethod(), audit.getPath(), audit.getController(),
                audit.getAction(), audit.getRequestData(), audit.getIpAddress(), audit.getDurationMs(), audit.getSuccess(),
                audit.getErrorMessage(), Timestamp.from(audit.getOperatedAt()));
        } catch (RuntimeException exception) {
            log.error("event=operation_audit_persist_failed method={} path={}", audit.getMethod(), audit.getPath(), exception);
        }
    }
}
