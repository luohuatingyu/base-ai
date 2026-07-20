package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

@Component
public class ApiTriggerUrlPolicy {
    private final PlatformProperties.ApiTrigger properties;

    public ApiTriggerUrlPolicy(PlatformProperties properties) { this.properties = properties.getApiTrigger(); }

    /** 校验协议、域名白名单和目标网络地址，阻止 SSRF。 */
    public URI validate(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new BusinessException("接口地址必须是完整 HTTP/HTTPS URL");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (properties.getAllowedHosts().stream().noneMatch(pattern -> matches(pattern, host))) {
                throw BusinessException.forbidden("目标域名不在 API_TRIGGER_ALLOWED_HOSTS 白名单");
            }
            if (!properties.isAllowPrivateNetwork()) {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
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

    /** 支持精确域名及 *.example.com 通配形式。 */
    private boolean matches(String pattern, String host) {
        String normalized = String.valueOf(pattern).trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("*.") ? host.endsWith(normalized.substring(1)) && host.length() > normalized.length() - 1
            : normalized.equals(host);
    }
}
