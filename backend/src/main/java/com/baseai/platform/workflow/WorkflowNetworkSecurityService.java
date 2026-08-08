package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 管理与 HTTP 自动化隔离的工作流连接器 Host 和 CIDR 出站白名单。 */
@Service
public class WorkflowNetworkSecurityService {
    public static final String HOST_RULES_KEY = "workflow.network.host-rules";
    public static final String CIDR_RULES_KEY = "workflow.network.cidr-rules";
    public static final String INITIALIZED_KEY = "workflow.network.imported";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private final SystemSettingRepository settingRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApiKeyCidrMatcher cidrMatcher;
    private final String cachePrefix;

    /** 注入持久化、共享缓存、JSON 与 CIDR 规范化组件。 */
    public WorkflowNetworkSecurityService(SystemSettingRepository settingRepository, StringRedisTemplate redisTemplate,
                                          ObjectMapper objectMapper, ApiKeyCidrMatcher cidrMatcher,
                                          PlatformProperties properties) {
        this.settingRepository = settingRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cidrMatcher = cidrMatcher;
        this.cachePrefix = properties.getPlatform().getCode() + ":workflow-network-security:";
    }

    /** 判断系统参数是否由工作流网络策略专用接口维护。 */
    public static boolean isReservedKey(String key) {
        return HOST_RULES_KEY.equals(key) || CIDR_RULES_KEY.equals(key) || INITIALIZED_KEY.equals(key);
    }

    /** 读取当前生效的独立白名单；未初始化时保持默认拒绝。 */
    public ConfigurationView current() {
        return new ConfigurationView(readValue(HOST_RULES_KEY).map(this::parseHostRules).orElse(List.of()),
            readValue(CIDR_RULES_KEY).map(this::parseCidrs).orElse(List.of()),
            readValue(INITIALIZED_KEY).map(Boolean::parseBoolean).orElse(false));
    }

    /** 保存管理员明确配置的白名单并立即清理多实例共享缓存。 */
    @Transactional
    public ConfigurationView update(UpdateCommand command) {
        if (command == null) throw new BusinessException("workflow.networkPolicyRequired");
        List<ApiTriggerSecurityConfigurationService.HostRule> hosts = normalizeHostRules(command.hostRules());
        List<String> cidrs = normalizeCidrs(command.allowedCidrs());
        saveValue(HOST_RULES_KEY, "工作流连接器 Host 白名单", json(hosts));
        saveValue(CIDR_RULES_KEY, "工作流连接器 CIDR 白名单", json(cidrs));
        saveValue(INITIALIZED_KEY, "工作流连接器安全策略已初始化", "true");
        evictCache();
        return new ConfigurationView(hosts, cidrs, true);
    }

    /** 首次启动时幂等保存从既有连接精确提取的 Host 与地址。 */
    @Transactional
    public ConfigurationView initializeImported(List<ApiTriggerSecurityConfigurationService.HostRule> hosts,
                                                List<String> cidrs) {
        if (current().initialized()) return current();
        return update(new UpdateCommand(hosts, cidrs));
    }

    /** 从 Redis 优先读取配置，未命中时回源系统设置表。 */
    private Optional<String> readValue(String key) {
        String cached = redisTemplate.opsForValue().get(cachePrefix + key);
        if (cached != null) return Optional.of(cached);
        return settingRepository.findByConfigKey(key).filter(item -> Boolean.TRUE.equals(item.getEnabled())).map(item -> {
            String value = item.getConfigValue() == null ? "" : item.getConfigValue();
            redisTemplate.opsForValue().set(cachePrefix + key, value, CACHE_TTL);
            return value;
        });
    }

    /** 创建或更新受保护的系统设置记录。 */
    private void saveValue(String key, String name, String value) {
        SystemSetting setting = settingRepository.findByConfigKey(key).orElseGet(SystemSetting::new);
        setting.setGroupCode("workflow-network"); setting.setConfigKey(key); setting.setName(name);
        setting.setConfigValue(value); setting.setSensitive(false); setting.setEnabled(true); settingRepository.save(setting);
    }

    /** 规范 Host 规则，拒绝空值并去重。 */
    private List<ApiTriggerSecurityConfigurationService.HostRule> normalizeHostRules(
        List<ApiTriggerSecurityConfigurationService.HostRule> values) {
        LinkedHashSet<ApiTriggerSecurityConfigurationService.HostRule> result = new LinkedHashSet<>();
        if (values == null) return List.of();
        for (ApiTriggerSecurityConfigurationService.HostRule value : values) {
            if (value == null || value.type() == null) throw new BusinessException("workflow.networkHostRuleInvalid");
            ApiTriggerSecurityConfigurationService.HostMatchType type;
            try { type = ApiTriggerSecurityConfigurationService.HostMatchType.valueOf(value.type().trim().toUpperCase(Locale.ROOT)); }
            catch (Exception exception) { throw new BusinessException("workflow.networkHostRuleInvalid"); }
            String host = type == ApiTriggerSecurityConfigurationService.HostMatchType.ANY ? null
                : value.value() == null ? "" : value.value().trim().toLowerCase(Locale.ROOT);
            if (type != ApiTriggerSecurityConfigurationService.HostMatchType.ANY && !host.matches("[a-z0-9:._-]{1,253}")) {
                throw new BusinessException("workflow.networkHostRuleInvalid");
            }
            result.add(new ApiTriggerSecurityConfigurationService.HostRule(type.name(), host));
        }
        return List.copyOf(result);
    }

    /** 规范 CIDR 与精确 IP 白名单。 */
    private List<String> normalizeCidrs(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().filter(value -> value != null && !value.isBlank()).map(cidrMatcher::normalize).forEach(result::add);
        return List.copyOf(result);
    }

    /** 解析持久化 Host 规则。 */
    private List<ApiTriggerSecurityConfigurationService.HostRule> parseHostRules(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return normalizeHostRules(objectMapper.readValue(value, new TypeReference<>() {})); }
        catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.networkPolicyInvalid"); }
    }

    /** 解析持久化 CIDR 规则。 */
    private List<String> parseCidrs(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return normalizeCidrs(objectMapper.readValue(value, new TypeReference<>() {})); }
        catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.networkPolicyInvalid"); }
    }

    /** 清理三个策略缓存键。 */
    private void evictCache() {
        redisTemplate.delete(List.of(cachePrefix + HOST_RULES_KEY, cachePrefix + CIDR_RULES_KEY, cachePrefix + INITIALIZED_KEY));
    }

    /** 序列化受控配置。 */
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException("workflow.networkPolicyInvalid"); }
    }

    public record UpdateCommand(List<ApiTriggerSecurityConfigurationService.HostRule> hostRules, List<String> allowedCidrs) {}
    public record ConfigurationView(List<ApiTriggerSecurityConfigurationService.HostRule> hostRules,
                                    List<String> allowedCidrs, boolean initialized) {}
}
