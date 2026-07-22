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

@Service
public class SystemConfigurationService {
    private final SystemSettingRepository settingRepository;
    private final DictionaryTypeRepository typeRepository;
    private final DictionaryDataRepository dataRepository;
    private final ConfigCryptoService cryptoService;
    private final StringRedisTemplate redisTemplate;
    private final String cachePrefix;

    public SystemConfigurationService(SystemSettingRepository settingRepository, DictionaryTypeRepository typeRepository,
                                      DictionaryDataRepository dataRepository, ConfigCryptoService cryptoService,
                                      StringRedisTemplate redisTemplate, PlatformProperties properties) {
        this.settingRepository = settingRepository;
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
        this.cryptoService = cryptoService;
        this.redisTemplate = redisTemplate;
        this.cachePrefix = properties.getPlatform().getCode() + ":setting:";
    }

    /** 查询全部系统参数并屏蔽敏感值。 */
    public List<SettingView> settings() {
        return settingRepository.findAll().stream().sorted(Comparator.comparing(SystemSetting::getGroupCode).thenComparing(SystemSetting::getConfigKey))
            .map(this::toView).toList();
    }

    /** 创建系统参数并刷新缓存。 */
    @Transactional
    public SettingView createSetting(SettingCommand command) {
        if (settingRepository.findByConfigKey(require(command.configKey(), "请输入参数键")).isPresent()) throw new BusinessException("参数键已存在");
        return saveSetting(new SystemSetting(), command);
    }

    /** 更新系统参数，敏感值留空时保留原值。 */
    @Transactional
    public SettingView updateSetting(Long id, SettingCommand command) {
        SystemSetting setting = settingRepository.findById(id).orElseThrow(() -> BusinessException.notFound("系统参数不存在"));
        return saveSetting(setting, command);
    }

    /** 删除系统参数及其缓存。 */
    @Transactional
    public void deleteSetting(Long id) {
        SystemSetting setting = settingRepository.findById(id).orElseThrow(() -> BusinessException.notFound("系统参数不存在"));
        redisTemplate.delete(cachePrefix + setting.getConfigKey());
        settingRepository.delete(setting);
    }

    /** 读取启用参数，优先使用 Redis 缓存。 */
    public Optional<String> value(String key) {
        String cacheKey = cachePrefix + key;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return Optional.of(cached);
        return settingRepository.findByConfigKey(key).filter(item -> Boolean.TRUE.equals(item.getEnabled())).map(item -> {
            String value = Boolean.TRUE.equals(item.getSensitive()) ? cryptoService.decrypt(item.getConfigValue()) : item.getConfigValue();
            redisTemplate.opsForValue().set(cacheKey, value == null ? "" : value, Duration.ofMinutes(10));
            return value;
        });
    }

    /** 查询字典类型。 */
    public List<DictionaryType> dictionaryTypes() { return typeRepository.findAll().stream().sorted(Comparator.comparing(DictionaryType::getCode)).toList(); }

    /** 创建字典类型。 */
    @Transactional
    public DictionaryType createDictionaryType(DictionaryTypeCommand command) {
        if (typeRepository.findByCode(require(command.code(), "请输入字典编码")).isPresent()) throw new BusinessException("字典编码已存在");
        return saveDictionaryType(new DictionaryType(), command);
    }

    /** 更新字典类型。 */
    @Transactional
    public DictionaryType updateDictionaryType(Long id, DictionaryTypeCommand command) {
        return saveDictionaryType(typeRepository.findById(id).orElseThrow(() -> BusinessException.notFound("字典类型不存在")), command);
    }

    /** 删除没有字典数据的类型。 */
    @Transactional
    public void deleteDictionaryType(Long id) {
        DictionaryType type = typeRepository.findById(id).orElseThrow(() -> BusinessException.notFound("字典类型不存在"));
        if (!dataRepository.findByTypeCodeOrderBySortOrderAscIdAsc(type.getCode()).isEmpty()) throw new BusinessException("请先删除字典数据");
        typeRepository.delete(type);
    }

    /** 查询指定类型的字典数据。 */
    public List<DictionaryData> dictionaryData(String typeCode) { return dataRepository.findByTypeCodeOrderBySortOrderAscIdAsc(typeCode); }

    /** 创建字典数据。 */
    @Transactional
    public DictionaryData createDictionaryData(DictionaryDataCommand command) { return saveDictionaryData(new DictionaryData(), command); }

    /** 更新字典数据。 */
    @Transactional
    public DictionaryData updateDictionaryData(Long id, DictionaryDataCommand command) {
        return saveDictionaryData(dataRepository.findById(id).orElseThrow(() -> BusinessException.notFound("字典数据不存在")), command);
    }

    /** 删除字典数据。 */
    public void deleteDictionaryData(Long id) { dataRepository.deleteById(id); }

    private SettingView saveSetting(SystemSetting setting, SettingCommand command) {
        setting.setGroupCode(require(command.groupCode(), "请输入参数分组"));
        setting.setConfigKey(require(command.configKey(), "请输入参数键"));
        setting.setName(require(command.name(), "请输入参数名称"));
        boolean sensitive = Boolean.TRUE.equals(command.sensitive());
        if (!sensitive || !blank(command.configValue()) || setting.getId() == null) {
            setting.setConfigValue(sensitive ? cryptoService.encrypt(command.configValue()) : command.configValue());
        }
        setting.setSensitive(sensitive);
        setting.setEnabled(command.enabled() == null || command.enabled());
        SystemSetting saved = settingRepository.save(setting);
        redisTemplate.delete(cachePrefix + saved.getConfigKey());
        return toView(saved);
    }
    private DictionaryType saveDictionaryType(DictionaryType type, DictionaryTypeCommand command) {
        type.setCode(require(command.code(), "请输入字典编码")); type.setName(require(command.name(), "请输入字典名称"));
        type.setDescription(trim(command.description())); type.setEnabled(command.enabled() == null || command.enabled()); return typeRepository.save(type);
    }
    private DictionaryData saveDictionaryData(DictionaryData data, DictionaryDataCommand command) {
        String typeCode = require(command.typeCode(), "请选择字典类型");
        if (typeRepository.findByCode(typeCode).isEmpty()) throw BusinessException.notFound("字典类型不存在");
        data.setTypeCode(typeCode); data.setLabel(require(command.label(), "请输入字典标签")); data.setDictValue(require(command.dictValue(), "请输入字典值"));
        data.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder()); data.setEnabled(command.enabled() == null || command.enabled()); return dataRepository.save(data);
    }
    private SettingView toView(SystemSetting item) { return new SettingView(item.getId(), item.getGroupCode(), item.getConfigKey(), item.getName(), Boolean.TRUE.equals(item.getSensitive()) ? "******" : item.getConfigValue(), item.getSensitive(), item.getEnabled(), item.getUpdatedAt()); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String trim(String value) { return blank(value) ? null : value.trim(); }
    private String require(String value, String message) { if (blank(value)) throw new BusinessException(message); return value.trim(); }

    public record SettingCommand(String groupCode, String configKey, String name, String configValue, Boolean sensitive, Boolean enabled) {}
    public record SettingView(Long id, String groupCode, String configKey, String name, String configValue, Boolean sensitive, Boolean enabled, java.time.Instant updatedAt) {}
    public record DictionaryTypeCommand(String code, String name, String description, Boolean enabled) {}
    public record DictionaryDataCommand(String typeCode, String label, String dictValue, Integer sortOrder, Boolean enabled) {}
}
