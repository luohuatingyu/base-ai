package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
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
    public static final String ALLOW_LOOPBACK_KEY = "api.trigger.allow-loopback";
    public static final String ALLOW_PRIVATE_NETWORK_KEY = "api.trigger.allow-private-network";
    static final boolean DEFAULT_ALLOW_LOOPBACK = true;
    static final boolean DEFAULT_ALLOW_PRIVATE_NETWORK = false;

    private static final String GROUP_CODE = "api-trigger";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private final SystemSettingRepository settingRepository;
    private final StringRedisTemplate redisTemplate;
    private final String cachePrefix;

    public ApiTriggerSecurityConfigurationService(SystemSettingRepository settingRepository,
                                                  StringRedisTemplate redisTemplate,
                                                  PlatformProperties properties) {
        this.settingRepository = settingRepository;
        this.redisTemplate = redisTemplate;
        this.cachePrefix = properties.getPlatform().getCode() + ":api-trigger-security:";
    }

    /** 判断系统参数键是否由接口触发安全配置页面专用管理。 */
    public static boolean isReservedKey(String key) {
        return ALLOWED_HOSTS_KEY.equals(key) || ALLOW_LOOPBACK_KEY.equals(key) || ALLOW_PRIVATE_NETWORK_KEY.equals(key);
    }

    /** 读取当前生效的接口触发安全配置，未保存时仅隐式允许回环地址。 */
    public ConfigurationView current() {
        List<String> allowedHosts = readValue(ALLOWED_HOSTS_KEY)
            .map(this::parseAllowedHosts)
            .orElse(List.of());
        boolean allowLoopback = readValue(ALLOW_LOOPBACK_KEY)
            .map(Boolean::parseBoolean)
            .orElse(DEFAULT_ALLOW_LOOPBACK);
        boolean allowPrivateNetwork = readValue(ALLOW_PRIVATE_NETWORK_KEY)
            .map(Boolean::parseBoolean)
            .orElse(DEFAULT_ALLOW_PRIVATE_NETWORK);
        return new ConfigurationView(allowedHosts, allowLoopback, allowPrivateNetwork);
    }

    /** 校验并保存运行时安全配置，清除共享缓存后供后续请求立即读取。 */
    @Transactional
    public ConfigurationView update(UpdateCommand command) {
        if (command == null || command.allowLoopback() == null || command.allowPrivateNetwork() == null) {
            throw new BusinessException("请选择是否允许访问回环地址和私有网络");
        }
        List<String> allowedHosts = normalizeAllowedHosts(command.allowedHosts());
        saveValue(ALLOWED_HOSTS_KEY, "接口触发允许访问的 Host", String.join(",", allowedHosts));
        saveValue(ALLOW_LOOPBACK_KEY, "接口触发是否允许回环地址", command.allowLoopback().toString());
        saveValue(ALLOW_PRIVATE_NETWORK_KEY, "接口触发是否允许私有网络", command.allowPrivateNetwork().toString());
        evictCache();
        return new ConfigurationView(allowedHosts, command.allowLoopback(), command.allowPrivateNetwork());
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
        redisTemplate.delete(List.of(cachePrefix + ALLOWED_HOSTS_KEY, cachePrefix + ALLOW_LOOPBACK_KEY,
            cachePrefix + ALLOW_PRIVATE_NETWORK_KEY));
    }

    /** 将存储值解析为去重后的 Host 规则列表。 */
    private List<String> parseAllowedHosts(String value) {
        if (value == null || value.isBlank()) return List.of();
        return normalizeAllowedHosts(List.of(value.split(",")));
    }

    /** 规范化 Host 规则并拒绝非法通配、域名或 IP 地址。 */
    private List<String> normalizeAllowedHosts(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String pattern = normalizePattern(value);
            if (!isValidPattern(pattern)) throw new BusinessException("Host 规则格式错误：" + value);
            if (isBuiltInLoopback(pattern)) continue;
            normalized.add(pattern);
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    /** 剔除无需保存和展示的三个内置回环 Host。 */
    private boolean isBuiltInLoopback(String value) {
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
    }

    /** 统一大小写并移除 IPv6 URL 中可选的方括号。 */
    private String normalizePattern(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /** 支持星号、子域通配、DNS 名称、IPv4 和 IPv6 字面值。 */
    private boolean isValidPattern(String value) {
        if ("*".equals(value)) return true;
        if (value.startsWith("*.")) return isValidDnsName(value.substring(2));
        if (value.contains(":")) return isValidIpv6(value);
        if (value.chars().allMatch(character -> Character.isDigit(character) || character == '.')) return isValidIpv4(value);
        return isValidDnsName(value);
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

    public record UpdateCommand(List<String> allowedHosts, Boolean allowLoopback, Boolean allowPrivateNetwork) {}
    public record ConfigurationView(List<String> allowedHosts, boolean allowLoopback, boolean allowPrivateNetwork) {}
}
