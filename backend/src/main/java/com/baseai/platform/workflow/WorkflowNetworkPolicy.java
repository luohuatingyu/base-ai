package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 在每次保存和连接前验证工作流连接器出站目标，阻止 SSRF 与内网横移。 */
@Component
public class WorkflowNetworkPolicy {
    private final WorkflowNetworkSecurityService configurationService;
    private final WorkflowConnectionTargetParser targetParser;
    private final ApiKeyCidrMatcher cidrMatcher;
    private final HostResolver hostResolver;

    /** 注入独立策略、目标解析和 CIDR 匹配组件。 */
    @Autowired
    public WorkflowNetworkPolicy(WorkflowNetworkSecurityService configurationService,
                                 WorkflowConnectionTargetParser targetParser, ApiKeyCidrMatcher cidrMatcher) {
        this.configurationService = configurationService;
        this.targetParser = targetParser;
        this.cidrMatcher = cidrMatcher;
        this.hostResolver = InetAddress::getAllByName;
    }

    /** 测试构造器允许模拟 DNS 在相邻解析之间切换地址。 */
    WorkflowNetworkPolicy(WorkflowNetworkSecurityService configurationService,
                          WorkflowConnectionTargetParser targetParser, ApiKeyCidrMatcher cidrMatcher,
                          HostResolver hostResolver) {
        this.configurationService = configurationService;
        this.targetParser = targetParser;
        this.cidrMatcher = cidrMatcher;
        this.hostResolver = hostResolver;
    }

    /** 校验连接配置中的每个 Host；私有、回环等地址还必须命中 CIDR 白名单。 */
    public void validate(String type, JsonNode config) {
        WorkflowNetworkSecurityService.ConfigurationView policy = configurationService.current();
        for (WorkflowConnectionTargetParser.Target target : targetParser.parse(type, config)) {
            resolveVerifiedHost(target.host(), policy);
        }
    }

    /** 为实际建连返回经过当前 Host 和 CIDR 策略校验的全部地址。 */
    public InetAddress[] resolveVerifiedHost(String rawHost) throws UnknownHostException {
        String host = rawHost == null ? "" : rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.isBlank()) throw new UnknownHostException("empty workflow host");
        return resolveVerifiedHost(host, configurationService.current());
    }

    /** 对单个 Host 执行规则和 DNS 全地址校验并返回防御性副本。 */
    private InetAddress[] resolveVerifiedHost(String host,
                                              WorkflowNetworkSecurityService.ConfigurationView policy) {
        if (policy.hostRules().stream().noneMatch(rule -> matches(rule, host))) {
            throw BusinessException.forbidden("workflow.networkHostForbidden");
        }
        try {
            Set<String> cidrs = Set.copyOf(policy.allowedCidrs());
            InetAddress[] addresses = hostResolver.resolve(host);
            if (addresses == null || addresses.length == 0) throw new UnknownHostException(host);
            for (InetAddress address : addresses) {
                if (restricted(address) && (cidrs.isEmpty() || !cidrMatcher.matches(address.getHostAddress(), cidrs))) {
                    throw BusinessException.forbidden("workflow.networkAddressForbidden");
                }
            }
            return addresses.clone();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.connectionTargetInvalid"); }
    }

    /** 匹配精确、DNS 边界和显式高风险 ANY 规则。 */
    private boolean matches(ApiTriggerSecurityConfigurationService.HostRule rule, String host) {
        String value = rule.value() == null ? "" : rule.value().toLowerCase(Locale.ROOT);
        return switch (ApiTriggerSecurityConfigurationService.HostMatchType.valueOf(rule.type())) {
            case EXACT -> host.equals(value);
            case PREFIX -> host.equals(value) || host.startsWith(value + ".");
            case SUFFIX -> host.equals(value) || host.endsWith("." + value);
            case CONTAINS -> host.contains(value);
            case ANY -> true;
        };
    }

    /** 识别必须额外获得 CIDR 授权的非公网地址。 */
    static boolean restricted(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        boolean sharedIpv4 = bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        boolean currentNetworkIpv4 = bytes.length == 4 && (bytes[0] & 0xff) == 0;
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalIpv6 || sharedIpv4 || currentNetworkIpv4;
    }

    /** 提供可替换的域名解析边界，生产实现使用 JVM 的进程级固定缓存。 */
    @FunctionalInterface
    interface HostResolver {
        /** 返回 Host 的全部地址。 */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
