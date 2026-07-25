package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyCidrMatcherTest {
    private final ApiKeyCidrMatcher matcher = new ApiKeyCidrMatcher();

    /** 空白名单允许任意来源，精确 IP 和 CIDR 仅允许匹配地址。 */
    @Test
    void matchesEmptyExactAndCidrRules() {
        assertTrue(matcher.matches("203.0.113.10", Set.of()));
        assertTrue(matcher.matches("10.1.2.3", Set.of("10.1.2.3")));
        assertTrue(matcher.matches("10.1.2.255", Set.of("10.1.2.0/24")));
        assertFalse(matcher.matches("10.1.3.1", Set.of("10.1.2.0/24")));
        assertTrue(matcher.matches("2001:db8::10", Set.of("2001:db8::/64")));
    }

    /** 规范化应压缩 IPv6 并保留有效 CIDR 前缀。 */
    @Test
    void normalizeCanonicalizesIpRules() {
        assertEquals("10.0.0.1", matcher.normalize(" 10.0.0.1 "));
        assertEquals("10.0.0.0/24", matcher.normalize("10.0.0.0/24"));
        assertEquals("2001:db8:0:0:0:0:0:0/64", matcher.normalize("2001:db8::/64"));
    }

    /** 主机名、非法地址和越界前缀必须拒绝，避免 DNS 查询和规则绕过。 */
    @Test
    void normalizeRejectsInvalidRules() {
        for (String value : new String[]{"example.com", "999.1.1.1", "10.0.0.0/33", "2001:db8::/129", "10.0.0.1/a"}) {
            assertThrows(BusinessException.class, () -> matcher.normalize(value));
        }
    }
}
