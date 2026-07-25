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

    /** 默认配置应允许 localhost、IPv4 回环和 IPv6 回环地址。 */
    @Test
    void defaultConfigurationAllowsAllLoopbackAddresses() {
        when(configurationService.current()).thenReturn(new ApiTriggerSecurityConfigurationService.ConfigurationView(
            List.of("localhost", "127.0.0.1", "::1"), true));

        assertDoesNotThrow(() -> policy.validate("http://localhost:8080/health"));
        assertDoesNotThrow(() -> policy.validate("http://127.0.0.1:8080/health"));
        assertDoesNotThrow(() -> policy.validate("http://[::1]:8080/health"));
    }

    /** 星号仅放开 Host，关闭私网开关时仍必须拒绝回环地址。 */
    @Test
    void wildcardStillRespectsPrivateNetworkSwitch() {
        when(configurationService.current()).thenReturn(new ApiTriggerSecurityConfigurationService.ConfigurationView(
            List.of("*"), false));

        for (String url : List.of("http://127.0.0.1/internal", "http://10.0.0.8/internal", "http://[fd00::1]/internal")) {
            assertThrows(BusinessException.class, () -> policy.validate(url));
        }
    }

    /** 星号配合开启私网时应允许任意私网 Host。 */
    @Test
    void wildcardAndPrivateNetworkAllowPrivateAddress() {
        when(configurationService.current()).thenReturn(new ApiTriggerSecurityConfigurationService.ConfigurationView(
            List.of("*"), true));

        assertDoesNotThrow(() -> policy.validate("http://10.0.0.8/internal"));
    }

    /** 精确 Host 与子域通配应通过，未授权 Host 应拒绝。 */
    @Test
    void exactAndSubdomainRulesRemainCompatible() {
        when(configurationService.current()).thenReturn(new ApiTriggerSecurityConfigurationService.ConfigurationView(
            List.of("api.example.com", "*.trusted.example.com"), true));

        assertDoesNotThrow(() -> policy.validate("https://api.example.com/path"));
        assertDoesNotThrow(() -> policy.validate("https://child.trusted.example.com/path"));
        assertThrows(BusinessException.class, () -> policy.validate("https://example.com/path"));
    }

    /** 每次校验都应读取当前配置，使页面保存结果无需重启即可生效。 */
    @Test
    void policyReadsConfigurationForEveryValidation() {
        when(configurationService.current())
            .thenReturn(new ApiTriggerSecurityConfigurationService.ConfigurationView(List.of("localhost"), true))
            .thenReturn(new ApiTriggerSecurityConfigurationService.ConfigurationView(List.of(), true));

        assertDoesNotThrow(() -> policy.validate("http://localhost/health"));
        assertThrows(BusinessException.class, () -> policy.validate("http://localhost/health"));
    }

    /** 非 HTTP 协议和不完整 URL 即使配置星号也必须拒绝。 */
    @Test
    void wildcardDoesNotBypassUrlSyntaxValidation() {
        when(configurationService.current()).thenReturn(new ApiTriggerSecurityConfigurationService.ConfigurationView(
            List.of("*"), true));

        assertThrows(BusinessException.class, () -> policy.validate("file:///etc/passwd"));
        assertThrows(BusinessException.class, () -> policy.validate("localhost:8080/path"));
    }
}
