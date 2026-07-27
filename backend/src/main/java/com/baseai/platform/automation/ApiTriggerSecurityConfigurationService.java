package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ApiTriggerSecurityConfigurationService {
    public static final String ALLOWED_HOSTS_KEY = "api.trigger.allowed-hosts";
    public static final String HOST_RULES_KEY = "api.trigger.host-rules";
    public static final String ALLOW_LOOPBACK_KEY = "api.trigger.allow-loopback";
    public static final String ALLOW_PRIVATE_NETWORK_KEY = "api.trigger.allow-private-network";
    static final boolean DEFAULT_ALLOW_LOOPBACK = true;
    static final boolean DEFAULT_ALLOW_PRIVATE_NETWORK = false;

    private static final String GROUP_CODE = "api-trigger";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private final SystemSettingRepository settingRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String cachePrefix;

    public ApiTriggerSecurityConfigurationService(SystemSettingRepository settingRepository,
                                                  StringRedisTemplate redisTemplate,
                                                  PlatformProperties properties,
                                                  ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cachePrefix = properties.getPlatform().getCode() + ":api-trigger-security:";
    }

    /** 判断系统参数键是否由接口触发安全配置页面专用管理。 */
    public static boolean isReservedKey(String key) {
        return ALLOWED_HOSTS_KEY.equals(key) || HOST_RULES_KEY.equals(key) || ALLOW_LOOPBACK_KEY.equals(key)
            || ALLOW_PRIVATE_NETWORK_KEY.equals(key);
    }

    /** 读取当前生效的接口触发安全配置，未保存时仅隐式允许回环地址。 */
    public ConfigurationView current() {
        List<HostRule> hostRules = readValue(HOST_RULES_KEY)
            .map(this::parseHostRules)
            .orElseGet(() -> readValue(ALLOWED_HOSTS_KEY).map(this::parseLegacyHostRules).orElse(List.of()));
        boolean allowLoopback = readValue(ALLOW_LOOPBACK_KEY)
            .map(Boolean::parseBoolean)
            .orElse(DEFAULT_ALLOW_LOOPBACK);
        boolean allowPrivateNetwork = readValue(ALLOW_PRIVATE_NETWORK_KEY)
            .map(Boolean::parseBoolean)
            .orElse(DEFAULT_ALLOW_PRIVATE_NETWORK);
        return new ConfigurationView(hostRules, allowLoopback, allowPrivateNetwork);
    }

    /** 校验并保存运行时安全配置，清除共享缓存后供后续请求立即读取。 */
    @Transactional
    public ConfigurationView update(UpdateCommand command) {
        if (command == null || command.allowLoopback() == null || command.allowPrivateNetwork() == null) {
            throw new BusinessException("apiTrigger.networkPolicyRequired");
        }
        List<HostRule> hostRules = normalizeHostRules(command.hostRules());
        saveValue(HOST_RULES_KEY, "接口触发 Host 匹配规则", serializeHostRules(hostRules));
        saveValue(ALLOW_LOOPBACK_KEY, "接口触发是否允许回环地址", command.allowLoopback().toString());
        saveValue(ALLOW_PRIVATE_NETWORK_KEY, "接口触发是否允许私有网络", command.allowPrivateNetwork().toString());
        evictCache();
        return new ConfigurationView(hostRules, command.allowLoopback(), command.allowPrivateNetwork());
    }

    /** 从 Redis 和系统参数表读取固定配置值，缓存空字符串以保留“拒绝全部”的显式配置。 */
    private Optional<String> readValue(String key) {
        String cacheKey = cachePrefix + key;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return Optional.of(cached);
        return settingRepository.findByConfigKey(key).filter(item -> Boolean.TRUE.equals(item.getEnabled())).map(item -> {
            String value = item.getConfigValue() == null ? "" : item.getConfigValue();
            redisTemplate.opsForValue().set(cacheKey, value, CACHE_TTL);
            return value;
        });
    }

    /** 创建或更新专用系统参数，避免安全配置暴露为启动环境变量。 */
    private void saveValue(String key, String name, String value) {
        SystemSetting setting = settingRepository.findByConfigKey(key).orElseGet(SystemSetting::new);
        setting.setGroupCode(GROUP_CODE);
        setting.setConfigKey(key);
        setting.setName(name);
        setting.setConfigValue(value);
        setting.setSensitive(false);
        setting.setEnabled(true);
        settingRepository.save(setting);
    }

    /** 删除三个运行时缓存键，确保单实例和多实例部署均在下一次读取时获取新值。 */
    private void evictCache() {
        redisTemplate.delete(List.of(cachePrefix + HOST_RULES_KEY, cachePrefix + ALLOWED_HOSTS_KEY,
            cachePrefix + ALLOW_LOOPBACK_KEY, cachePrefix + ALLOW_PRIVATE_NETWORK_KEY));
    }

    /** 解析新版 JSON Host 规则，格式异常时拒绝使用不确定配置。 */
    private List<HostRule> parseHostRules(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return normalizeHostRules(objectMapper.readValue(value, new TypeReference<List<HostRule>>() {}));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("apiTrigger.hostRulesInvalid");
        }
    }

    /** 将旧逗号规则转换为精确、后缀和任意 Host 三类结构化规则。 */
    private List<HostRule> parseLegacyHostRules(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<HostRule> rules = new ArrayList<>();
        for (String item : value.split(",")) {
            String normalized = normalizeValue(item);
            if (normalized.isBlank()) continue;
            if ("*".equals(normalized)) rules.add(new HostRule(HostMatchType.ANY.name(), null));
            else if (normalized.startsWith("*.")) rules.add(new HostRule(HostMatchType.SUFFIX.name(), normalized.substring(2)));
            else rules.add(new HostRule(HostMatchType.EXACT.name(), normalized));
        }
        return normalizeHostRules(rules);
    }

    /** 序列化已规范化规则，供系统参数表持久化。 */
    private String serializeHostRules(List<HostRule> rules) {
        try {
            return objectMapper.writeValueAsString(rules);
        } catch (Exception exception) {
            throw new BusinessException("apiTrigger.hostRulesSaveFailed");
        }
    }

    /** 规范化结构化规则并按类型和值去重。 */
    private List<HostRule> normalizeHostRules(List<HostRule> rules) {
        if (rules == null || rules.isEmpty()) return List.of();
        LinkedHashSet<HostRule> normalized = new LinkedHashSet<>();
        for (HostRule rule : rules) {
            if (rule == null || rule.type() == null || rule.type().isBlank()) continue;
            HostMatchType type;
            try {
                type = HostMatchType.valueOf(rule.type().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("apiTrigger.hostMatchTypeUnsupported", rule.type());
            }
            if (type == HostMatchType.ANY) {
                normalized.add(new HostRule(type.name(), null));
                continue;
            }
            if (rule.value() == null || rule.value().isBlank()) throw new BusinessException("apiTrigger.hostValueRequired");
            String value = normalizeValue(rule.value());
            if (type == HostMatchType.EXACT) {
                if (!isValidExactHost(value)) throw new BusinessException("apiTrigger.hostRuleInvalid", rule.value());
                if (isBuiltInLoopback(value)) continue;
            } else if ((type == HostMatchType.PREFIX || type == HostMatchType.SUFFIX) && !isValidDnsName(value)) {
                throw new BusinessException("apiTrigger.hostRuleInvalid", rule.value());
            } else if (type == HostMatchType.CONTAINS && !isValidDnsPattern(value)) {
                throw new BusinessException("apiTrigger.hostRuleInvalid", rule.value());
            }
            normalized.add(new HostRule(type.name(), value));
        }
        return List.copyOf(normalized);
    }

    /** 剔除无需保存和展示的三个内置回环 Host。 */
    private boolean isBuiltInLoopback(String value) {
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
    }

    /** 统一大小写并移除 IPv6 URL 中可选的方括号。 */
    private String normalizeValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /** 精确规则支持 DNS 名称、IPv4 和 IPv6 字面值。 */
    private boolean isValidExactHost(String value) {
        if (value.contains(":")) return isValidIpv6(value);
        if (value.chars().allMatch(character -> Character.isDigit(character) || character == '.')) return isValidIpv4(value);
        return isValidDnsName(value);
    }

    /** 前缀、后缀和包含规则仅允许 DNS Host 可出现的字符。 */
    private boolean isValidDnsPattern(String value) {
        return !value.isBlank() && value.length() <= 253 && !value.startsWith(".") && !value.endsWith(".")
            && value.chars().anyMatch(character -> character >= 'a' && character <= 'z' || Character.isDigit(character))
            && value.chars().allMatch(character -> character >= 'a' && character <= 'z'
                || Character.isDigit(character) || character == '-' || character == '.');
    }

    /** 校验单标签主机名和多标签域名，兼容容器服务名等内部 DNS 名称。 */
    private boolean isValidDnsName(String value) {
        if (value.isBlank() || value.length() > 253 || value.startsWith(".") || value.endsWith(".")) return false;
        for (String label : value.split("\\.")) {
            if (!DNS_LABEL.matcher(label).matches()) return false;
        }
        return true;
    }

    /** 校验 IPv4 字面值的四段数值范围。 */
    private boolean isValidIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (part.isBlank() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                    || Integer.parseInt(part) > 255) return false;
            }
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** 校验包含冒号的 IPv6 字面值，不对普通域名执行 DNS 查询。 */
    private boolean isValidIpv6(String value) {
        if (!value.contains(":")) return false;
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (Exception exception) {
            return false;
        }
    }

    public enum HostMatchType { EXACT, PREFIX, SUFFIX, CONTAINS, ANY }
    public record HostRule(String type, String value) {}
    public record UpdateCommand(List<HostRule> hostRules, Boolean allowLoopback, Boolean allowPrivateNetwork) {}
    public record ConfigurationView(List<HostRule> hostRules, boolean allowLoopback, boolean allowPrivateNetwork) {}
}
