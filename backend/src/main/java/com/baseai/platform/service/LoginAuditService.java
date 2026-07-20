package com.baseai.platform.service;

import com.baseai.platform.domain.LoginLog;
import com.baseai.platform.repository.LoginLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class LoginAuditService {
    private final LoginLogRepository repository;

    public LoginAuditService(LoginLogRepository repository) { this.repository = repository; }

    /** 使用独立事务保存登录日志，避免认证失败回滚审计记录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String username, AuthService.LoginMetadata metadata, boolean success, String message) {
        LoginLog log = new LoginLog();
        log.setUsername(username);
        log.setIpAddress(metadata.ipAddress());
        log.setUserAgent(metadata.userAgent());
        log.setSuccess(success);
        log.setMessage(message);
        log.setLoginAt(Instant.now());
        repository.save(log);
    }
}
