package com.baseai.platform.service;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import com.baseai.platform.security.AuthContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

/**
 * 系统配置服务
 *
 * <p>提供系统参数配置和数据字典管理功能，包括：
 * <ul>
 *   <li>系统参数的增删改查，支持敏感值加密存储</li>
 *   <li>系统参数的Redis缓存管理，提升读取性能</li>
 *   <li>数据字典类型和字典数据的完整管理</li>
 * </ul>
 *
 * <p>敏感参数会通过 {@link ConfigCryptoService} 进行加密存储和解密读取，
 * 查询时敏感值会被屏蔽显示为 "******"。
 *
 * @author BaseAI Platform
 * @since 1.0
 */
@Service
public class SystemConfigurationService {
    /** 系统参数数据仓库 */
    private final SystemSettingRepository settingRepository;
    /** 字典类型数据仓库 */
    private final DictionaryTypeRepository typeRepository;
    /** 字典数据仓库 */
    private final DictionaryDataRepository dataRepository;
    /** 配置加密服务，用于敏感参数的加密解密 */
    private final ConfigCryptoService cryptoService;
    /** Redis模板，用于参数缓存 */
    private final StringRedisTemplate redisTemplate;
    /** Redis缓存键前缀，格式为 "平台代码:setting:" */
    private final String cachePrefix;
    /** 配置运行时缓存同步服务 */
    private final SystemSettingCacheService cacheService;
    /** 配置缓存同步 Outbox 服务 */
    private final SystemSettingSyncOutboxService outboxService;

    /**
     * 构造函数，初始化系统配置服务
     *
     * @param settingRepository 系统参数数据仓库
     * @param typeRepository 字典类型数据仓库
     * @param dataRepository 字典数据仓库
     * @param cryptoService 配置加密服务
     * @param redisTemplate Redis操作模板
     * @param properties 平台配置属性，用于获取缓存键前缀
     * @param cacheService 配置运行时缓存同步服务
     * @param outboxService 配置缓存同步 Outbox 服务
     */
    public SystemConfigurationService(SystemSettingRepository settingRepository, DictionaryTypeRepository typeRepository,
                                      DictionaryDataRepository dataRepository, ConfigCryptoService cryptoService,
                                      StringRedisTemplate redisTemplate, PlatformProperties properties,
                                      SystemSettingCacheService cacheService, SystemSettingSyncOutboxService outboxService) {
        this.settingRepository = settingRepository;
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
        this.cryptoService = cryptoService;
        this.redisTemplate = redisTemplate;
        this.cacheService = cacheService;
        this.outboxService = outboxService;
        // 构建缓存键前缀，例如："baseai:setting:"
        this.cachePrefix = properties.getPlatform().getCode() + ":setting:";
    }

    /**
     * 查询全部系统参数并屏蔽敏感值
     *
     * <p>返回所有系统参数列表，按分组代码和参数键排序。
     * 敏感参数的值会被屏蔽显示为 "******"，不会暴露实际值。
     *
     * @return 系统参数视图列表，敏感值已屏蔽
     */
    public List<SettingView> settings() {
        return sortedSettings().stream().map(this::toView).toList();
    }

    /** 按参数键模糊检索并分页查询系统参数，页码从一开始且每页最多一百条。 */
    public SettingPage settingsPage(int page, int size, String configKey) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        String normalizedKey = configKey == null ? "" : configKey.trim().toLowerCase(Locale.ROOT);
        List<SystemSetting> settings = sortedSettings().stream()
            .filter(item -> normalizedKey.isEmpty()
                || item.getConfigKey().toLowerCase(Locale.ROOT).contains(normalizedKey))
            .toList();
        long requestedFromIndex = (long) (normalizedPage - 1) * normalizedSize;
        int fromIndex = (int) Math.min(requestedFromIndex, settings.size());
        int toIndex = Math.min(fromIndex + normalizedSize, settings.size());
        return new SettingPage(settings.subList(fromIndex, toIndex).stream().map(this::toView).toList(),
            normalizedPage, normalizedSize, settings.size());
    }

    /** 查询并整理可展示的系统参数，统一全量读取和分页查询的排序规则。 */
    private List<SystemSetting> sortedSettings() {
        return settingRepository.findAll().stream()
            .filter(item -> !ApiTriggerSecurityConfigurationService.isReservedKey(item.getConfigKey()))
            .sorted(Comparator.comparing(SystemSetting::getGroupCode)
                .thenComparing(SystemSetting::getSortOrder, Comparator.nullsFirst(Integer::compareTo))
                .thenComparing(SystemSetting::getConfigKey))
            .toList();
    }

    /**
     * 创建系统参数并刷新缓存
     *
     * <p>创建新的系统参数，参数键必须唯一。
     * 如果参数标记为敏感，配置值会被加密存储。
     *
     * @param command 系统参数创建命令对象
     * @return 创建成功的系统参数视图
     * @throws BusinessException 如果参数键已存在或必填字段为空
     */
    @Transactional
    public SettingView createSetting(SettingCommand command) {
        rejectReservedKey(command.configKey());
        // 检查参数键是否已存在
        if (settingRepository.findByConfigKey(require(command.configKey(), "setting.keyRequired")).isPresent()) throw new BusinessException("setting.keyExists");
        return saveSetting(new SystemSetting(), command);
    }

    /**
     * 更新系统参数，敏感值留空时保留原值
     *
     * <p>更新已有的系统参数。对于敏感参数，如果更新时配置值为空，
     * 则保留原有的加密值，避免因误操作清空敏感配置。
     *
     * @param id 系统参数ID
     * @param command 系统参数更新命令对象
     * @return 更新后的系统参数视图
     * @throws BusinessException 如果参数不存在或必填字段为空
     */
    @Transactional
    public SettingView updateSetting(Long id, SettingCommand command) {
        SystemSetting setting = settingRepository.findById(id).orElseThrow(() -> BusinessException.notFound("setting.notFound"));
        rejectReservedKey(setting.getConfigKey());
        rejectReservedKey(command.configKey());
        rejectSystemManagedMetadata(setting, command);
        return saveSetting(setting, command);
    }

    /**
     * 删除系统参数及其缓存
     *
     * <p>从数据库中删除指定的系统参数，同时清除Redis缓存。
     *
     * @param id 系统参数ID
     * @throws BusinessException 如果参数不存在
     */
    @Transactional
    public void deleteSetting(Long id) {
        SystemSetting setting = settingRepository.findById(id).orElseThrow(() -> BusinessException.notFound("setting.notFound"));
        rejectReservedKey(setting.getConfigKey());
        if (Boolean.TRUE.equals(setting.getSystemManaged())) throw BusinessException.forbidden("setting.systemManaged");
        String configKey = setting.getConfigKey();
        SystemSettingCacheService.CacheSnapshot previousCache = cacheService.snapshot(configKey);
        SystemSettingSyncOutbox event = outboxService.enqueue(configKey, "DELETE");
        try {
            cacheService.delete(configKey);
            settingRepository.delete(setting);
            markOutboxProcessedAfterCommit(event);
        } catch (RuntimeException exception) {
            restoreCache(configKey, previousCache, exception);
            throw new BusinessException("setting.syncFailed");
        }
    }

    /**
     * 读取启用的参数值，优先使用Redis缓存
     *
     * <p>读取系统参数的实际值（敏感参数会被解密）。
     * 采用缓存优先策略：
     * <ol>
     *   <li>首先从Redis缓存中读取</li>
     *   <li>缓存未命中时从数据库读取</li>
     *   <li>只返回启用状态的参数</li>
     *   <li>敏感参数会自动解密</li>
     *   <li>读取后写入缓存，过期时间10分钟</li>
     * </ol>
     *
     * @param key 参数键
     * @return 参数值的Optional包装，如果参数不存在或未启用则返回空Optional
     */
    public Optional<String> value(String key) {
        String cacheKey = cachePrefix + key;
        // 优先从缓存读取
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return Optional.of(cached);

        // 缓存未命中，从数据库读取
        return settingRepository.findByConfigKey(key).filter(item -> Boolean.TRUE.equals(item.getEnabled())).map(item -> {
            // 敏感参数需要解密
            String value = Boolean.TRUE.equals(item.getSensitive()) ? cryptoService.decrypt(item.getConfigValue()) : item.getConfigValue();
            // 写入缓存，过期时间10分钟
            redisTemplate.opsForValue().set(cacheKey, value == null ? "" : value, Duration.ofMinutes(10));
            return value;
        });
    }

    /**
     * 查询所有字典类型
     *
     * <p>返回所有字典类型，按编码排序。
     *
     * @return 字典类型列表
     */
    public List<DictionaryType> dictionaryTypes() { return typeRepository.findAll().stream().sorted(Comparator.comparing(DictionaryType::getCode)).toList(); }

    /**
     * 创建字典类型
     *
     * <p>创建新的字典类型，字典编码必须唯一。
     *
     * @param command 字典类型创建命令对象
     * @return 创建成功的字典类型
     * @throws BusinessException 如果字典编码已存在或必填字段为空
     */
    @Transactional
    public DictionaryType createDictionaryType(DictionaryTypeCommand command) {
        // 检查字典编码是否已存在
        if (typeRepository.findByCode(require(command.code(), "dictionary.codeRequired")).isPresent()) throw new BusinessException("dictionary.codeExists");
        return saveDictionaryType(new DictionaryType(), command);
    }

    /**
     * 更新字典类型
     *
     * @param id 字典类型ID
     * @param command 字典类型更新命令对象
     * @return 更新后的字典类型
     * @throws BusinessException 如果字典类型不存在或必填字段为空
     */
    @Transactional
    public DictionaryType updateDictionaryType(Long id, DictionaryTypeCommand command) {
        return saveDictionaryType(typeRepository.findById(id).orElseThrow(() -> BusinessException.notFound("dictionary.typeNotFound")), command);
    }

    /**
     * 删除没有字典数据的类型
     *
     * <p>只能删除没有关联字典数据的类型，防止产生孤立数据。
     *
     * @param id 字典类型ID
     * @throws BusinessException 如果字典类型不存在或还有关联的字典数据
     */
    @Transactional
    public void deleteDictionaryType(Long id) {
        DictionaryType type = typeRepository.findById(id).orElseThrow(() -> BusinessException.notFound("dictionary.typeNotFound"));
        // 检查是否还有关联的字典数据
        if (!dataRepository.findByTypeCodeOrderBySortOrderAscIdAsc(type.getCode()).isEmpty()) throw new BusinessException("dictionary.typeInUse");
        typeRepository.delete(type);
    }

    /**
     * 查询指定类型的字典数据
     *
     * <p>返回指定字典类型下的所有字典数据，按排序号和ID升序排列。
     *
     * @param typeCode 字典类型编码
     * @return 字典数据列表
     */
    public List<DictionaryData> dictionaryData(String typeCode) { return dataRepository.findByTypeCodeOrderBySortOrderAscIdAsc(typeCode); }

    /**
     * 创建字典数据
     *
     * @param command 字典数据创建命令对象
     * @return 创建成功的字典数据
     * @throws BusinessException 如果字典类型不存在或必填字段为空
     */
    @Transactional
    public DictionaryData createDictionaryData(DictionaryDataCommand command) { return saveDictionaryData(new DictionaryData(), command); }

    /**
     * 更新字典数据
     *
     * @param id 字典数据ID
     * @param command 字典数据更新命令对象
     * @return 更新后的字典数据
     * @throws BusinessException 如果字典数据不存在、字典类型不存在或必填字段为空
     */
    @Transactional
    public DictionaryData updateDictionaryData(Long id, DictionaryDataCommand command) {
        return saveDictionaryData(dataRepository.findById(id).orElseThrow(() -> BusinessException.notFound("dictionary.dataNotFound")), command);
    }

    /**
     * 删除字典数据
     *
     * @param id 字典数据ID
     */
    public void deleteDictionaryData(Long id) { dataRepository.deleteById(id); }

    /**
     * 保存系统参数（内部方法）
     *
     * <p>处理系统参数的创建和更新逻辑：
     * <ul>
     *   <li>设置基本属性（分组代码、参数键、名称）</li>
     *   <li>处理敏感参数的加密存储</li>
     *   <li>敏感参数更新时，如果值为空且是更新操作，保留原加密值</li>
     *   <li>保存后清除Redis缓存，确保下次读取最新值</li>
     * </ul>
     *
     * @param setting 要保存的系统参数实体（新建或已有）
     * @param command 参数命令对象
     * @return 保存后的系统参数视图
     */
    private SettingView saveSetting(SystemSetting setting, SettingCommand command) {
        String previousConfigKey = setting.getConfigKey();
        String configKey = require(command.configKey(), "setting.keyRequired");
        String previousKey = previousConfigKey == null ? configKey : previousConfigKey;
        SystemSettingCacheService.CacheSnapshot previousCache = cacheService.snapshot(previousKey);
        SystemSettingCacheService.CacheSnapshot replacementCache = previousKey.equals(configKey)
            ? previousCache : cacheService.snapshot(configKey);
        setting.setGroupCode(require(command.groupCode(), "setting.groupRequired"));
        setting.setConfigKey(configKey);
        setting.setName(require(command.name(), "setting.nameRequired"));
        boolean sensitive = Boolean.TRUE.equals(command.sensitive());
        // 判断是否需要更新配置值：非敏感参数总是更新；敏感参数在值非空或新建时才更新
        if (!sensitive || !blank(command.configValue()) || setting.getId() == null) {
            // 敏感参数需要加密存储
            setting.setConfigValue(sensitive ? cryptoService.encrypt(command.configValue()) : command.configValue());
        }
        setting.setSensitive(sensitive);
        // 默认启用
        setting.setEnabled(command.enabled() == null || command.enabled());
        if (setting.getSortOrder() == null) setting.setSortOrder(0);
        if (setting.getSystemManaged() == null) setting.setSystemManaged(false);
        SystemSetting saved = settingRepository.save(setting);
        SystemSettingSyncOutbox replacementEvent = outboxService.enqueue(saved.getConfigKey(), "UPSERT");
        SystemSettingSyncOutbox removalEvent = previousKey.equals(saved.getConfigKey())
            ? null : outboxService.enqueue(previousKey, "DELETE");
        try {
            cacheService.apply(saved);
            if (removalEvent != null) cacheService.delete(previousKey);
            markOutboxProcessedAfterCommit(replacementEvent);
            markOutboxProcessedAfterCommit(removalEvent);
            return toView(saved);
        } catch (RuntimeException exception) {
            restoreCache(saved.getConfigKey(), replacementCache, exception);
            if (removalEvent != null) restoreCache(previousKey, previousCache, exception);
            throw new BusinessException("setting.syncFailed");
        }
    }

    /** 恢复缓存旧值并将同步失败转换为统一业务异常。 */
    private void restoreCache(String configKey, SystemSettingCacheService.CacheSnapshot snapshot, RuntimeException cause) {
        try {
            cacheService.restore(configKey, snapshot);
        } catch (RuntimeException restoreException) {
            cause.addSuppressed(restoreException);
        }
    }

    /** 提交事务后标记 Outbox，进程在提交窗口中退出时保留任务供定时对账。 */
    private void markOutboxProcessedAfterCommit(SystemSettingSyncOutbox event) {
        if (event == null || event.getId() == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            outboxService.markProcessed(event.getId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { outboxService.markProcessed(event.getId()); }
        });
    }

    /**
     * 保存字典类型（内部方法）
     *
     * @param type 要保存的字典类型实体（新建或已有）
     * @param command 字典类型命令对象
     * @return 保存后的字典类型
     */
    private DictionaryType saveDictionaryType(DictionaryType type, DictionaryTypeCommand command) {
        type.setCode(require(command.code(), "dictionary.codeRequired"));
        type.setName(require(command.name(), "dictionary.nameRequired"));
        type.setDescription(trim(command.description()));
        // 默认启用
        type.setEnabled(command.enabled() == null || command.enabled());
        return typeRepository.save(type);
    }

    /**
     * 保存字典数据（内部方法）
     *
     * @param data 要保存的字典数据实体（新建或已有）
     * @param command 字典数据命令对象
     * @return 保存后的字典数据
     */
    private DictionaryData saveDictionaryData(DictionaryData data, DictionaryDataCommand command) {
        String typeCode = require(command.typeCode(), "dictionary.typeRequired");
        // 验证字典类型是否存在
        if (typeRepository.findByCode(typeCode).isEmpty()) throw BusinessException.notFound("dictionary.typeNotFound");
        data.setTypeCode(typeCode);
        data.setLabel(require(command.label(), "dictionary.labelRequired"));
        data.setDictValue(require(command.dictValue(), "dictionary.valueRequired"));
        // 排序号默认为0
        data.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        // 默认启用
        data.setEnabled(command.enabled() == null || command.enabled());
        return dataRepository.save(data);
    }

    /**
     * 将系统参数实体转换为视图对象（内部方法）
     *
     * <p>敏感参数仅对系统管理员展示真实值，其他用户显示为 "******"。
     *
     * @param item 系统参数实体
     * @return 系统参数视图对象
     */
    private SettingView toView(SystemSetting item) {
        String configValue = Boolean.TRUE.equals(item.getSensitive())
            ? (isAdmin() ? cryptoService.decrypt(item.getConfigValue()) : "******")
            : item.getConfigValue();
        return new SettingView(item.getId(), item.getGroupCode(), item.getConfigKey(), item.getName(), configValue,
            item.getSensitive(), item.getEnabled(), item.getSortOrder(), item.getSystemManaged(), item.getUpdatedAt());
    }

    /** 判断当前登录用户是否为系统管理员。 */
    private boolean isAdmin() {
        return AuthContext.current() != null && AuthContext.current().roles().contains("ADMIN");
    }

    /**
     * 判断字符串是否为空或空白（内部方法）
     *
     * @param value 待检查的字符串
     * @return 如果为null或只包含空白字符返回true，否则返回false
     */
    private boolean blank(String value) { return value == null || value.isBlank(); }

    /**
     * 去除字符串首尾空白（内部方法）
     *
     * @param value 待处理的字符串
     * @return 如果为空或空白返回null，否则返回去除首尾空白后的字符串
     */
    private String trim(String value) { return blank(value) ? null : value.trim(); }

    /**
     * 必填字段验证（内部方法）
     *
     * <p>验证字符串不为空，如果为空则抛出业务异常。
     *
     * @param value 待验证的字符串
     * @param messageKey 验证失败时的消息资源键
     * @return 去除首尾空白后的字符串
     * @throws BusinessException 如果字符串为空或空白
     */
    private String require(String value, String messageKey) {
        if (blank(value)) throw new BusinessException(messageKey);
        return value.trim();
    }

    /** 阻止通用系统参数入口修改接口触发专用安全配置。 */
    private void rejectReservedKey(String key) {
        if (ApiTriggerSecurityConfigurationService.isReservedKey(key == null ? null : key.trim())) {
            throw BusinessException.forbidden("apiTrigger.securityDedicatedPageOnly");
        }
    }

    /** 系统托管参数只允许修改值和启用状态，防止初始化身份被篡改。 */
    private void rejectSystemManagedMetadata(SystemSetting setting, SettingCommand command) {
        if (!Boolean.TRUE.equals(setting.getSystemManaged())) return;
        if (!Objects.equals(setting.getGroupCode(), command.groupCode())
            || !Objects.equals(setting.getConfigKey(), command.configKey())
            || !Objects.equals(setting.getName(), command.name())
            || !Objects.equals(setting.getSensitive(), command.sensitive())
            || !Objects.equals(setting.getEnabled(), command.enabled())) {
            throw BusinessException.forbidden("setting.systemManaged");
        }
    }

    /**
     * 系统参数命令对象
     *
     * @param groupCode 参数分组代码
     * @param configKey 参数键（唯一标识）
     * @param name 参数名称
     * @param configValue 参数值（敏感参数会被加密存储）
     * @param sensitive 是否为敏感参数
     * @param enabled 是否启用
     */
    public record SettingCommand(String groupCode, String configKey, String name, String configValue, Boolean sensitive, Boolean enabled) {}

    /**
     * 系统参数视图对象
     *
     * @param id 参数ID
     * @param groupCode 参数分组代码
     * @param configKey 参数键
     * @param name 参数名称
     * @param configValue 参数值（敏感参数显示为"******"）
     * @param sensitive 是否为敏感参数
     * @param enabled 是否启用
     * @param updatedAt 更新时间
     */
    public record SettingView(Long id, String groupCode, String configKey, String name, String configValue,
                              Boolean sensitive, Boolean enabled, Integer sortOrder, Boolean systemManaged,
                              java.time.Instant updatedAt) {}

    /** 系统参数分页响应，items 为当前页数据，total 为过滤后的总数。 */
    public record SettingPage(List<SettingView> items, int page, int size, long total) {}

    /**
     * 字典类型命令对象
     *
     * @param code 字典类型编码（唯一标识）
     * @param name 字典类型名称
     * @param description 字典类型描述
     * @param enabled 是否启用
     */
    public record DictionaryTypeCommand(String code, String name, String description, Boolean enabled) {}

    /**
     * 字典数据命令对象
     *
     * @param typeCode 字典类型编码
     * @param label 字典标签（显示文本）
     * @param dictValue 字典值（实际值）
     * @param sortOrder 排序号
     * @param enabled 是否启用
     */
    public record DictionaryDataCommand(String typeCode, String label, String dictValue, Integer sortOrder, Boolean enabled) {}
}
