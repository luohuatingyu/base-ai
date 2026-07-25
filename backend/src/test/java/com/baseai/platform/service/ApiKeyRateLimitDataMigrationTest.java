package com.baseai.platform.service;

import com.baseai.platform.domain.ApiKeyCredential;
import com.baseai.platform.domain.ApiKeyRateLimitType;
import com.baseai.platform.repository.ApiKeyCredentialRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyRateLimitDataMigrationTest {
    /** 历史每分钟配置应迁移为新的分钟周期和原调用次数。 */
    @Test
    void runMigratesLegacyMinuteConfiguration() {
        ApiKeyCredentialRepository repository = mock(ApiKeyCredentialRepository.class);
        ApiKeyCredential credential = new ApiKeyCredential();
        credential.setRateLimitPerMinute(120);
        when(repository.findAll()).thenReturn(List.of(credential));

        new ApiKeyRateLimitDataMigration(repository).run(null);

        assertEquals(ApiKeyRateLimitType.MINUTE, credential.getRateLimitType());
        assertEquals(120, credential.getRateLimitCount());
        verify(repository).saveAll(List.of(credential));
    }
}
