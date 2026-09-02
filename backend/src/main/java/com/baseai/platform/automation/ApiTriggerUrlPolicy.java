package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

@Component
public class ApiTriggerUrlPolicy {
    private final ApiTriggerSecurityConfigurationService configurationService;

    public ApiTriggerUrlPolicy(ApiTriggerSecurityConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /** 校验协议、域名白名单和目标网络地址，阻止 SSRF。 */
    public URI validate(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new BusinessException("apiTrigger.urlAbsoluteRequired");
            }
            if (uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() == 0) {
                throw new BusinessException("apiTrigger.urlUnsafeComponent");
            }
            resolveVerifiedHost(uri.getHost());
            return uri;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("apiTrigger.urlParseFailed");
        }
    }

    /**
     * 解析并返回通过策略校验的地址，供实际 HTTP 连接器直接使用。
     *
     * <p>连接器不得再次委托系统默认 DNS 解析，否则首次校验与实际连接之间仍可能被 DNS 重绑定绕过。</p>
     */
    public InetAddress[] resolveVerifiedHost(String value) {
        try {
            String host = normalizeHost(value);
            ApiTriggerSecurityConfigurationService.ConfigurationView configuration = configurationService.current();
            boolean literalLoopback = isLiteralLoopbackHost(host);
            if (literalLoopback && !configuration.allowLoopback()) {
                throw BusinessException.forbidden("apiTrigger.loopbackForbidden");
            }
            if (!literalLoopback && configuration.hostRules().stream().noneMatch(rule -> matches(rule, host))) {
                throw BusinessException.forbidden("apiTrigger.hostForbidden");
            }
            InetAddress[] addresses = InetAddress.getAllByName(stripIpv6Brackets(host));
            if (!configuration.allowLoopback() || !configuration.allowPrivateNetwork()) {
                for (InetAddress address : addresses) {
                    if (address.isLoopbackAddress() && !configuration.allowLoopback()) {
                        throw BusinessException.forbidden("apiTrigger.loopbackForbidden");
                    }
                    if (isNonLoopbackPrivateAddress(address) && !configuration.allowPrivateNetwork()) {
                        throw BusinessException.forbidden("apiTrigger.privateNetworkForbidden");
                    }
                }
            }
            return addresses;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("apiTrigger.urlParseFailed");
        }
    }

    /** 按精确、域名边界前后缀、普通包含和任意 Host 五种类型匹配。 */
    private boolean matches(ApiTriggerSecurityConfigurationService.HostRule rule, String host) {
        String normalizedHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        String value = rule.value() == null ? "" : rule.value().toLowerCase(Locale.ROOT);
        return switch (ApiTriggerSecurityConfigurationService.HostMatchType.valueOf(rule.type())) {
            case EXACT -> normalizedHost.equals(value);
            case PREFIX -> normalizedHost.equals(value) || normalizedHost.startsWith(value + ".");
            case SUFFIX -> normalizedHost.equals(value) || normalizedHost.endsWith("." + value);
            case CONTAINS -> normalizedHost.contains(value);
            case ANY -> true;
        };
    }

    /** 统一 Host 的大小写和 IPv6 方括号表示。 */
    private String normalizeHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (host.isBlank()) throw new BusinessException("apiTrigger.urlAbsoluteRequired");
        return host;
    }

    /** Java DNS API 接受不带方括号的 IPv6 文本地址。 */
    private String stripIpv6Brackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /** 识别无需配置额外 Host 白名单的三个字面回环地址。 */
    private boolean isLiteralLoopbackHost(String host) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    /** 识别回环之外的本机、链路本地、私有网段、IPv6 ULA 和组播地址。 */
    private boolean isNonLoopbackPrivateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        boolean sharedIpv4 = bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        boolean currentNetworkIpv4 = bytes.length == 4 && (bytes[0] & 0xff) == 0;
        return address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalIpv6
            || sharedIpv4 || currentNetworkIpv4;
    }
}
