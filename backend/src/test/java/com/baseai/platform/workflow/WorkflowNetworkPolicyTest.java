package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowNetworkPolicyTest {
    private WorkflowNetworkSecurityService configurationService;
    private WorkflowConnectionTargetParser parser;
    private WorkflowNetworkPolicy policy;

    /** 创建可控安全配置和真实目标解析器。 */
    @BeforeEach
    void setUp() {
        configurationService = mock(WorkflowNetworkSecurityService.class);
        parser = new WorkflowConnectionTargetParser();
        policy = new WorkflowNetworkPolicy(configurationService, parser, new ApiKeyCidrMatcher());
    }

    /** 未明确加入 Host 白名单的公网连接也必须默认拒绝。 */
    @Test
    void deniesTargetsWithoutHostRule() throws Exception {
        when(configurationService.current()).thenReturn(new WorkflowNetworkSecurityService.ConfigurationView(List.of(), List.of(), true));
        assertThrows(BusinessException.class, () -> policy.validate("MYSQL", new ObjectMapper().readTree(
            "{\"url\":\"jdbc:mysql://93.184.216.34/orders\"}")));
    }

    /** 精确 Host 允许公网目标，但私网目标还必须命中 CIDR。 */
    @Test
    void privateTargetsRequireBothHostAndCidr() throws Exception {
        var host = new ApiTriggerSecurityConfigurationService.HostRule("EXACT", "10.0.0.8");
        when(configurationService.current()).thenReturn(
            new WorkflowNetworkSecurityService.ConfigurationView(List.of(host), List.of(), true),
            new WorkflowNetworkSecurityService.ConfigurationView(List.of(host), List.of("10.0.0.8"), true));
        var config = new ObjectMapper().readTree("{\"uri\":\"redis://10.0.0.8:6379\"}");
        assertThrows(BusinessException.class, () -> policy.validate("REDIS", config));
        assertDoesNotThrow(() -> policy.validate("REDIS", config));
    }

    /** 连接类型必须使用各自允许的协议，不能伪装任意 URI。 */
    @Test
    void parserRejectsUnexpectedSchemes() throws Exception {
        assertThrows(BusinessException.class, () -> parser.parse("MYSQL",
            new ObjectMapper().readTree("{\"url\":\"jdbc:postgresql://db/orders\"}")));
        assertThrows(BusinessException.class, () -> parser.parse("RABBITMQ",
            new ObjectMapper().readTree("{\"uri\":\"http://broker\"}")));
    }
}
