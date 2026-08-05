package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class ClientIpResolver {
    private static final int MAX_FORWARDED_LENGTH = 1024;
    private static final int MAX_PROXY_HOPS = 20;
    private final ApiKeyCidrMatcher cidrMatcher;
    private final Set<String> trustedProxyCidrs;

    public ClientIpResolver(ApiKeyCidrMatcher cidrMatcher, PlatformProperties properties) {
        this.cidrMatcher = cidrMatcher;
        this.trustedProxyCidrs = properties.getProxy().getTrustedCidrs().stream()
            .map(cidrMatcher::normalize).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 仅在直接来源可信时解析代理链，防止客户端伪造来源地址。 */
    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalizeAddress(request.getRemoteAddr());
        if (trustedProxyCidrs.isEmpty() || !cidrMatcher.matches(remoteAddress, trustedProxyCidrs)) return remoteAddress;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || forwarded.length() > MAX_FORWARDED_LENGTH) return remoteAddress;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > MAX_PROXY_HOPS) return remoteAddress;
        String current = remoteAddress;
        try {
            for (int index = hops.length - 1; index >= 0 && cidrMatcher.matches(current, trustedProxyCidrs); index--) {
                current = normalizeAddress(hops[index]);
            }
            return current;
        } catch (BusinessException exception) {
            return remoteAddress;
        }
    }

    /** 使用现有 CIDR 解析器规范化 IP 字面量，不触发 DNS 查询。 */
    private String normalizeAddress(String value) {
        return cidrMatcher.normalize(value == null ? "" : value.trim());
    }
}
