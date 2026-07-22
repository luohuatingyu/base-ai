package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
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

    /**
     * 构造函数，初始化系统配置服务
     *
     * @param settingRepository 系统参数数据仓库
     * @param typeRepository 字典类型数据仓库
     * @param dataRepository 字典数据仓库
     * @param cryptoService 配置加密服务
     * @param redisTemplate Redis操作模板
     * @param properties 平台配置属性，用于获取缓存键前缀
     */
    public SystemConfigurationService(SystemSettingRepository settingRepository, DictionaryTypeRepository typeRepository,
                                      DictionaryDataRepository dataRepository, ConfigCryptoService cryptoService,
                                      StringRedisTemplate redisTemplate, PlatformProperties properties) {
        this.settingRepository = settingRepository;
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
        this.cryptoService = cryptoService;
        this.redisTemplate = redisTemplate;
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
        // 查询所有参数，先按分组代码排序，再按参数键排序
        return settingRepository.findAll().stream().sorted(Comparator.comparing(SystemSetting::getGroupCode).thenComparing(SystemSetting::getConfigKey))
            .map(this::toView).toList();
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
        // 检查参数键是否已存在
        if (settingRepository.findByConfigKey(require(command.configKey(), "请输入参数键")).isPresent()) throw new BusinessException("参数键已存在");
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
        SystemSetting setting = settingRepository.findById(id).orElseThrow(() -> BusinessException.notFound("系统参数不存在"));
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
        SystemSetting setting = settingRepository.findById(id).orElseThrow(() -> BusinessException.notFound("系统参数不存在"));
        // 删除Redis缓存
        redisTemplate.delete(cachePrefix + setting.getConfigKey());
        settingRepository.delete(setting);
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
        if (typeRepository.findByCode(require(command.code(), "请输入字典编码")).isPresent()) throw new BusinessException("字典编码已存在");
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
        return saveDictionaryType(typeRepository.findById(id).orElseThrow(() -> BusinessException.notFound("字典类型不存在")), command);
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
        DictionaryType type = typeRepository.findById(id).orElseThrow(() -> BusinessException.notFound("字典类型不存在"));
        // 检查是否还有关联的字典数据
        if (!dataRepository.findByTypeCodeOrderBySortOrderAscIdAsc(type.getCode()).isEmpty()) throw new BusinessException("请先删除字典数据");
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
        return saveDictionaryData(dataRepository.findById(id).orElseThrow(() -> BusinessException.notFound("字典数据不存在")), command);
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
        setting.setGroupCode(require(command.groupCode(), "请输入参数分组"));
        setting.setConfigKey(require(command.configKey(), "请输入参数键"));
        setting.setName(require(command.name(), "请输入参数名称"));
        boolean sensitive = Boolean.TRUE.equals(command.sensitive());
        // 判断是否需要更新配置值：非敏感参数总是更新；敏感参数在值非空或新建时才更新
        if (!sensitive || !blank(command.configValue()) || setting.getId() == null) {
            // 敏感参数需要加密存储
            setting.setConfigValue(sensitive ? cryptoService.encrypt(command.configValue()) : command.configValue());
        }
        setting.setSensitive(sensitive);
        // 默认启用
        setting.setEnabled(command.enabled() == null || command.enabled());
        SystemSetting saved = settingRepository.save(setting);
        // 清除缓存，确保下次读取最新值
        redisTemplate.delete(cachePrefix + saved.getConfigKey());
        return toView(saved);
    }

    /**
     * 保存字典类型（内部方法）
     *
     * @param type 要保存的字典类型实体（新建或已有）
     * @param command 字典类型命令对象
     * @return 保存后的字典类型
     */
    private DictionaryType saveDictionaryType(DictionaryType type, DictionaryTypeCommand command) {
        type.setCode(require(command.code(), "请输入字典编码"));
        type.setName(require(command.name(), "请输入字典名称"));
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
        String typeCode = require(command.typeCode(), "请选择字典类型");
        // 验证字典类型是否存在
        if (typeRepository.findByCode(typeCode).isEmpty()) throw BusinessException.notFound("字典类型不存在");
        data.setTypeCode(typeCode);
        data.setLabel(require(command.label(), "请输入字典标签"));
        data.setDictValue(require(command.dictValue(), "请输入字典值"));
        // 排序号默认为0
        data.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        // 默认启用
        data.setEnabled(command.enabled() == null || command.enabled());
        return dataRepository.save(data);
    }

    /**
     * 将系统参数实体转换为视图对象（内部方法）
     *
     * <p>敏感参数的值会被屏蔽显示为 "******"。
     *
     * @param item 系统参数实体
     * @return 系统参数视图对象
     */
    private SettingView toView(SystemSetting item) {
        return new SettingView(item.getId(), item.getGroupCode(), item.getConfigKey(), item.getName(),
            Boolean.TRUE.equals(item.getSensitive()) ? "******" : item.getConfigValue(),
            item.getSensitive(), item.getEnabled(), item.getUpdatedAt());
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
     * @param message 验证失败时的错误消息
     * @return 去除首尾空白后的字符串
     * @throws BusinessException 如果字符串为空或空白
     */
    private String require(String value, String message) {
        if (blank(value)) throw new BusinessException(message);
        return value.trim();
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
    public record SettingView(Long id, String groupCode, String configKey, String name, String configValue, Boolean sensitive, Boolean enabled, java.time.Instant updatedAt) {}

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
