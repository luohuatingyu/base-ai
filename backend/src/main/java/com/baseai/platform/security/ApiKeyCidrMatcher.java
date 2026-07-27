package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Set;

@Component
public class ApiKeyCidrMatcher {
    /** 判断客户端 IP 是否匹配任一精确地址或 CIDR 规则。 */
    public boolean matches(String clientIp, Set<String> rules) {
        if (rules == null || rules.isEmpty()) return true;
        byte[] address = parseAddress(clientIp, "apiKey.clientIpInvalid");
        return rules.stream().anyMatch(rule -> matchesRule(address, rule));
    }

    /** 校验并规范化单条 IP 或 CIDR 规则。 */
    public String normalize(String rule) {
        if (rule == null || rule.isBlank()) throw new BusinessException("apiKey.cidrRequired");
        String normalized = rule.trim();
        String[] parts = normalized.split("/", -1);
        if (parts.length > 2) throw new BusinessException("apiKey.cidrFormatInvalid");
        byte[] address = parseAddress(parts[0], "apiKey.cidrAddressInvalid");
        int maxBits = address.length * 8;
        int prefix = parts.length == 1 ? maxBits : parsePrefix(parts[1], maxBits);
        try {
            return InetAddress.getByAddress(address).getHostAddress() + (prefix == maxBits ? "" : "/" + prefix);
        } catch (Exception exception) {
            throw new BusinessException("apiKey.cidrAddressInvalid");
        }
    }

    /** 判断地址字节是否落在指定网络前缀内。 */
    private boolean matchesRule(byte[] address, String rule) {
        String[] parts = rule.split("/", -1);
        byte[] network = parseAddress(parts[0], "apiKey.cidrAddressInvalid");
        if (network.length != address.length) return false;
        int prefix = parts.length == 1 ? network.length * 8 : parsePrefix(parts[1], network.length * 8);
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int index = 0; index < fullBytes; index++) if (address[index] != network[index]) return false;
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /** 解析仅包含 IP 字面量的地址，禁止触发 DNS 查询。 */
    private byte[] parseAddress(String value, String messageKey) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[0-9a-fA-F:.]+") || (!normalized.contains(".") && !normalized.contains(":"))) {
            throw new BusinessException(messageKey);
        }
        try {
            return InetAddress.getByName(normalized).getAddress();
        } catch (Exception exception) {
            throw new BusinessException(messageKey);
        }
    }

    /** 解析并校验 CIDR 前缀长度。 */
    private int parsePrefix(String value, int maxBits) {
        try {
            int prefix = Integer.parseInt(value);
            if (prefix < 0 || prefix > maxBits) throw new NumberFormatException();
            return prefix;
        } catch (NumberFormatException exception) {
            throw new BusinessException("apiKey.cidrPrefixInvalid");
        }
    }
}
