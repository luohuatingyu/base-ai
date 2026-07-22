package com.baseai.platform.controller;

import com.baseai.platform.domain.DictionaryData;
import com.baseai.platform.domain.DictionaryType;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.SystemConfigurationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统参数和字典管理接口。
 *
 * <p>所有写操作都通过权限注解进行访问控制，数据校验和持久化由业务服务负责。</p>
 */
@RestController
@RequestMapping("/api/system")
public class SystemConfigurationController {
    private final SystemConfigurationService service;
    public SystemConfigurationController(SystemConfigurationService service){this.service=service;}

    /** 查询系统参数。 */
    @GetMapping("/settings") @RequiredPermission("system:setting:list") public List<SystemConfigurationService.SettingView> settings(){return service.settings();}
    /** 创建系统参数。 */
    @PostMapping("/settings") @RequiredPermission("system:setting:create") public SystemConfigurationService.SettingView createSetting(@RequestBody SystemConfigurationService.SettingCommand command){return service.createSetting(command);}
    /** 更新系统参数。 */
    @PutMapping("/settings/{id}") @RequiredPermission("system:setting:update") public SystemConfigurationService.SettingView updateSetting(@PathVariable Long id,@RequestBody SystemConfigurationService.SettingCommand command){return service.updateSetting(id,command);}
    /** 删除系统参数。 */
    @DeleteMapping("/settings/{id}") @RequiredPermission("system:setting:delete") public void deleteSetting(@PathVariable Long id){service.deleteSetting(id);}

    /** 查询字典类型。 */
    @GetMapping("/dictionaries/types") @RequiredPermission("system:dictionary:list") public List<DictionaryType> types(){return service.dictionaryTypes();}
    /** 创建字典类型。 */
    @PostMapping("/dictionaries/types") @RequiredPermission("system:dictionary:create") public DictionaryType createType(@RequestBody SystemConfigurationService.DictionaryTypeCommand command){return service.createDictionaryType(command);}
    /** 更新字典类型。 */
    @PutMapping("/dictionaries/types/{id}") @RequiredPermission("system:dictionary:update") public DictionaryType updateType(@PathVariable Long id,@RequestBody SystemConfigurationService.DictionaryTypeCommand command){return service.updateDictionaryType(id,command);}
    /** 删除字典类型。 */
    @DeleteMapping("/dictionaries/types/{id}") @RequiredPermission("system:dictionary:delete") public void deleteType(@PathVariable Long id){service.deleteDictionaryType(id);}
    /** 按类型编码查询字典数据。 */
    @GetMapping("/dictionaries/data") @RequiredPermission("system:dictionary:list") public List<DictionaryData> data(@RequestParam String typeCode){return service.dictionaryData(typeCode);}
    /** 创建字典数据。 */
    @PostMapping("/dictionaries/data") @RequiredPermission("system:dictionary:create") public DictionaryData createData(@RequestBody SystemConfigurationService.DictionaryDataCommand command){return service.createDictionaryData(command);}
    /** 更新字典数据。 */
    @PutMapping("/dictionaries/data/{id}") @RequiredPermission("system:dictionary:update") public DictionaryData updateData(@PathVariable Long id,@RequestBody SystemConfigurationService.DictionaryDataCommand command){return service.updateDictionaryData(id,command);}
    /** 删除字典数据。 */
    @DeleteMapping("/dictionaries/data/{id}") @RequiredPermission("system:dictionary:delete") public void deleteData(@PathVariable Long id){service.deleteDictionaryData(id);}
}
