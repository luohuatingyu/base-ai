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
                throw new BusinessException("接口地址必须是完整 HTTP/HTTPS URL");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            ApiTriggerSecurityConfigurationService.ConfigurationView configuration = configurationService.current();
            if (configuration.allowedHosts().stream().noneMatch(pattern -> matches(pattern, host))) {
                throw BusinessException.forbidden("目标域名不在接口触发 Host 白名单");
            }
            if (!configuration.allowPrivateNetwork()) {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (isPrivateAddress(address)) {
                        throw BusinessException.forbidden("禁止访问本机或私有网络地址");
                    }
                }
            }
            return uri;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("接口地址解析失败");
        }
    }

    /** 支持星号、精确域名及 *.example.com 通配形式，并兼容 IPv6 方括号。 */
    private boolean matches(String pattern, String host) {
        String normalized = String.valueOf(pattern).trim().toLowerCase(Locale.ROOT);
        String normalizedHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return "*".equals(normalized) || (normalized.startsWith("*.")
            ? normalizedHost.endsWith(normalized.substring(1)) && normalizedHost.length() > normalized.length() - 1
            : normalized.equals(normalizedHost));
    }

    /** 识别本机、链路本地、私有网段、IPv6 ULA 和组播地址。 */
    private boolean isPrivateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalIpv6;
    }
}
