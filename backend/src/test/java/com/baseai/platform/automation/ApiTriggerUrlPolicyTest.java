package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiTriggerUrlPolicyTest {

    /** 验证白名单内的回环地址仍会被私网策略拒绝。 */
    @Test
    void rejectsLoopbackTargets() {
        PlatformProperties properties = new PlatformProperties();
        properties.getApiTrigger().setAllowedHosts(List.of("127.0.0.1"));
        ApiTriggerUrlPolicy policy = new ApiTriggerUrlPolicy(properties);
        assertThatThrownBy(() -> policy.validate("http://127.0.0.1/internal"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("私有网络");
    }

    /** 验证不在域名白名单中的地址被拒绝。 */
    @Test
    void rejectsHostsOutsideAllowlist() {
        PlatformProperties properties = new PlatformProperties();
        properties.getApiTrigger().setAllowedHosts(List.of("api.example.com"));
        ApiTriggerUrlPolicy policy = new ApiTriggerUrlPolicy(properties);
        assertThatThrownBy(() -> policy.validate("https://untrusted.example.net/test"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("白名单");
    }
}
