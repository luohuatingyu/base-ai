package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** Spring 必须选择注入三个生产依赖的构造器创建网络策略。 */
    @Test
    void springSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WorkflowNetworkSecurityService.class, () -> configurationService);
            context.registerBean(WorkflowConnectionTargetParser.class, () -> parser);
            context.registerBean(ApiKeyCidrMatcher.class, ApiKeyCidrMatcher::new);
            context.register(WorkflowNetworkPolicy.class);
            context.refresh();

            assertNotNull(context.getBean(WorkflowNetworkPolicy.class));
        }
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
        for (String type : List.of("QDRANT", "MILVUS", "ELASTICSEARCH")) {
            assertThrows(BusinessException.class, () -> parser.parse(type,
                new ObjectMapper().readTree("{\"url\":\"file:///etc/passwd\"}")), type);
            assertDoesNotThrow(() -> parser.parse(type,
                new ObjectMapper().readTree("{\"url\":\"https://vectors.example.com\"}")), type);
        }
    }

    /** 实际建连解析若切换为私网地址，必须再次执行 CIDR 策略并拒绝。 */
    @Test
    void rejectsAddressThatRebindsBeforeConnection() throws Exception {
        var host = new ApiTriggerSecurityConfigurationService.HostRule("EXACT", "switch.example.com");
        when(configurationService.current()).thenReturn(
            new WorkflowNetworkSecurityService.ConfigurationView(List.of(host), List.of(), true));
        AtomicInteger resolutions = new AtomicInteger();
        WorkflowNetworkPolicy rebindingPolicy = new WorkflowNetworkPolicy(configurationService, parser,
            new ApiKeyCidrMatcher(), ignored -> resolutions.getAndIncrement() == 0
                ? new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})}
                : new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})});

        assertDoesNotThrow(() -> rebindingPolicy.resolveVerifiedHost("switch.example.com"));
        assertThrows(BusinessException.class, () -> rebindingPolicy.resolveVerifiedHost("switch.example.com"));
    }
}
