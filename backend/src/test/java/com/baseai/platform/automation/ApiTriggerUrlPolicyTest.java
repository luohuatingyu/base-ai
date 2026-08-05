package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiTriggerUrlPolicyTest {
    private final ApiTriggerSecurityConfigurationService configurationService = mock(ApiTriggerSecurityConfigurationService.class);
    private final ApiTriggerUrlPolicy policy = new ApiTriggerUrlPolicy(configurationService);

    /** 默认配置无需 Host 规则即可允许 localhost、IPv4 回环和 IPv6 回环地址。 */
    @Test
    void defaultConfigurationAllowsAllLoopbackAddresses() {
        configure(List.of(), true, false);

        assertDoesNotThrow(() -> policy.validate("http://localhost:8080/health"));
        assertDoesNotThrow(() -> policy.validate("http://127.0.0.1:8080/health"));
        assertDoesNotThrow(() -> policy.validate("http://[::1]:8080/health"));
    }

    /** 精确规则只允许完全相同的 Host。 */
    @Test
    void exactRuleMatchesOnlyEqualHost() {
        configure(List.of(rule("EXACT", "api.example.com")), true, true);

        assertDoesNotThrow(() -> policy.validate("https://api.example.com/path"));
        assertThrows(BusinessException.class, () -> policy.validate("https://child.api.example.com/path"));
    }

    /** 前缀规则按域名标签边界匹配，不得把 app 匹配到 application。 */
    @Test
    void prefixRuleUsesDnsBoundary() {
        configure(List.of(rule("PREFIX", "app")), true, true);

        assertDoesNotThrow(() -> policy.validate("https://app/path"));
        assertDoesNotThrow(() -> policy.validate("https://app.factory.ai/path"));
        assertThrows(BusinessException.class, () -> policy.validate("https://application.ai/path"));
    }

    /** 后缀规则按域名标签边界匹配 factory.ai 及其子域，不匹配 evilfactory.ai。 */
    @Test
    void suffixRuleUsesDnsBoundary() {
        configure(List.of(rule("SUFFIX", "factory.ai")), true, true);

        assertDoesNotThrow(() -> policy.validate("https://factory.ai/path"));
        assertDoesNotThrow(() -> policy.validate("https://app.factory.ai/path"));
        assertThrows(BusinessException.class, () -> policy.validate("https://evilfactory.ai/path"));
    }

    /** 包含规则使用普通字符串包含语义。 */
    @Test
    void containsRuleMatchesSubstring() {
        configure(List.of(rule("CONTAINS", "factory")), true, true);

        assertDoesNotThrow(() -> policy.validate("https://myfactoryservice.ai/path"));
        assertThrows(BusinessException.class, () -> policy.validate("https://example.ai/path"));
    }

    /** 任意 Host 规则仍必须遵循其他私网开关。 */
    @Test
    void anyRuleStillRespectsPrivateNetworkSwitch() {
        configure(List.of(rule("ANY", null)), true, false);

        for (String url : List.of("http://10.0.0.8/internal", "http://[fd00::1]/internal")) {
            assertThrows(BusinessException.class, () -> policy.validate(url));
        }
    }

    /** 关闭回环开关后，任意 Host 规则也不得绕过回环限制。 */
    @Test
    void loopbackSwitchBlocksLoopbackWithAnyRule() {
        configure(List.of(rule("ANY", null)), false, true);

        for (String url : List.of("http://localhost/internal", "http://127.0.0.1/internal", "http://[::1]/internal")) {
            assertThrows(BusinessException.class, () -> policy.validate(url));
        }
    }

    /** 每次校验都应读取当前规则，使页面保存结果无需重启即可生效。 */
    @Test
    void policyReadsConfigurationForEveryValidation() {
        when(configurationService.current())
            .thenReturn(view(List.of(rule("SUFFIX", "factory.ai")), true, true))
            .thenReturn(view(List.of(), true, true));

        assertDoesNotThrow(() -> policy.validate("https://app.factory.ai/health"));
        assertThrows(BusinessException.class, () -> policy.validate("https://app.factory.ai/health"));
    }

    /** 非 HTTP 协议和不完整 URL 即使配置任意 Host 也必须拒绝。 */
    @Test
    void anyRuleDoesNotBypassUrlSyntaxValidation() {
        configure(List.of(rule("ANY", null)), true, true);

        assertThrows(BusinessException.class, () -> policy.validate("file:///etc/passwd"));
        assertThrows(BusinessException.class, () -> policy.validate("localhost:8080/path"));
        assertThrows(BusinessException.class, () -> policy.validate("https://user:secret@example.com/path"));
        assertThrows(BusinessException.class, () -> policy.validate("https://example.com/path#fragment"));
        assertThrows(BusinessException.class, () -> policy.validate("https://example.com:0/path"));
    }

    /** 共享地址空间和非标准数字回环写法不得绕过私网限制。 */
    @Test
    void blocksAlternativePrivateAddressForms() {
        configure(List.of(rule("ANY", null)), false, false);

        assertThrows(BusinessException.class, () -> policy.validate("http://100.64.0.1/internal"));
        assertThrows(BusinessException.class, () -> policy.validate("http://2130706433/internal"));
    }

    /** 配置当前测试使用的规则和两个网络开关。 */
    private void configure(List<ApiTriggerSecurityConfigurationService.HostRule> rules,
                           boolean allowLoopback, boolean allowPrivateNetwork) {
        when(configurationService.current()).thenReturn(view(rules, allowLoopback, allowPrivateNetwork));
    }

    /** 创建不可变配置视图。 */
    private ApiTriggerSecurityConfigurationService.ConfigurationView view(
        List<ApiTriggerSecurityConfigurationService.HostRule> rules, boolean allowLoopback, boolean allowPrivateNetwork) {
        return new ApiTriggerSecurityConfigurationService.ConfigurationView(rules, allowLoopback, allowPrivateNetwork);
    }

    /** 创建测试 Host 规则。 */
    private ApiTriggerSecurityConfigurationService.HostRule rule(String type, String value) {
        return new ApiTriggerSecurityConfigurationService.HostRule(type, value);
    }
}
