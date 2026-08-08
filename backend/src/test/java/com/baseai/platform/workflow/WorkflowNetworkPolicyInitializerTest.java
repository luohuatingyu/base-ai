package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowNetworkPolicyInitializerTest {
    /** 首次升级只把已有目标导入为精确 Host，并为私有解析地址生成精确规则。 */
    @Test
    void importsExistingTargetsWithoutAnyHostRule() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-network-import;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_connection(id BIGINT PRIMARY KEY,connection_type VARCHAR(24),config_encrypted CLOB,voided BOOLEAN)
            """);
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        ConfigCryptoService crypto = new ConfigCryptoService(properties);
        jdbcTemplate.update("INSERT INTO workflow_connection VALUES (1,'REDIS',?,false)",
            crypto.encrypt("{\"uri\":\"redis://127.0.0.1:6379\"}"));
        WorkflowNetworkSecurityService security = mock(WorkflowNetworkSecurityService.class);
        when(security.current()).thenReturn(new WorkflowNetworkSecurityService.ConfigurationView(List.of(), List.of(), false));
        WorkflowNetworkPolicyInitializer initializer = new WorkflowNetworkPolicyInitializer(jdbcTemplate, new ObjectMapper(),
            crypto, new WorkflowConnectionTargetParser(), security, new ApiKeyCidrMatcher());

        initializer.run(new DefaultApplicationArguments(new String[0]));

        @SuppressWarnings("unchecked") ArgumentCaptor<List<ApiTriggerSecurityConfigurationService.HostRule>> hosts = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> cidrs = ArgumentCaptor.forClass(List.class);
        verify(security).initializeImported(hosts.capture(), cidrs.capture());
        assertTrue(hosts.getValue().stream().anyMatch(rule -> "EXACT".equals(rule.type()) && "127.0.0.1".equals(rule.value())));
        assertTrue(hosts.getValue().stream().noneMatch(rule -> "ANY".equals(rule.type())));
        assertTrue(cidrs.getValue().contains("127.0.0.1"));
    }
}
