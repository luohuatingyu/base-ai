package com.baseai.platform.service;

import com.baseai.platform.domain.ApiKeyCredential;
import com.baseai.platform.domain.ApiKeyRateLimitType;
import com.baseai.platform.repository.ApiKeyCredentialRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class ApiKeyRateLimitDataMigration implements ApplicationRunner {
    private final ApiKeyCredentialRepository repository;

    public ApiKeyRateLimitDataMigration(ApiKeyCredentialRepository repository) {
        this.repository = repository;
    }

    /** 启动时将历史每分钟限流配置迁移为新的周期配置。 */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ApiKeyCredential> credentials = repository.findAll().stream()
            .filter(ApiKeyCredential::hasLegacyRateLimitConfiguration)
            .toList();
        credentials.forEach(credential -> {
            credential.setRateLimitType(ApiKeyRateLimitType.MINUTE);
            credential.setRateLimitCount(credential.getRateLimitPerMinute());
        });
        if (!credentials.isEmpty()) repository.saveAll(credentials);
    }
}
