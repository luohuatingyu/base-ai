package com.baseai.platform.controller;

import com.baseai.platform.domain.DictionaryData;
import com.baseai.platform.domain.DictionaryType;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.SystemConfigurationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemConfigurationController {
    private final SystemConfigurationService service;
    public SystemConfigurationController(SystemConfigurationService service){this.service=service;}

    @GetMapping("/settings") @RequiredPermission("system:setting:list") public List<SystemConfigurationService.SettingView> settings(){return service.settings();}
    @PostMapping("/settings") @RequiredPermission("system:setting:create") public SystemConfigurationService.SettingView createSetting(@RequestBody SystemConfigurationService.SettingCommand command){return service.createSetting(command);}
    @PutMapping("/settings/{id}") @RequiredPermission("system:setting:update") public SystemConfigurationService.SettingView updateSetting(@PathVariable Long id,@RequestBody SystemConfigurationService.SettingCommand command){return service.updateSetting(id,command);}
    @DeleteMapping("/settings/{id}") @RequiredPermission("system:setting:delete") public void deleteSetting(@PathVariable Long id){service.deleteSetting(id);}

    @GetMapping("/dictionaries/types") @RequiredPermission("system:dictionary:list") public List<DictionaryType> types(){return service.dictionaryTypes();}
    @PostMapping("/dictionaries/types") @RequiredPermission("system:dictionary:create") public DictionaryType createType(@RequestBody SystemConfigurationService.DictionaryTypeCommand command){return service.createDictionaryType(command);}
    @PutMapping("/dictionaries/types/{id}") @RequiredPermission("system:dictionary:update") public DictionaryType updateType(@PathVariable Long id,@RequestBody SystemConfigurationService.DictionaryTypeCommand command){return service.updateDictionaryType(id,command);}
    @DeleteMapping("/dictionaries/types/{id}") @RequiredPermission("system:dictionary:delete") public void deleteType(@PathVariable Long id){service.deleteDictionaryType(id);}
    @GetMapping("/dictionaries/data") @RequiredPermission("system:dictionary:list") public List<DictionaryData> data(@RequestParam String typeCode){return service.dictionaryData(typeCode);}
    @PostMapping("/dictionaries/data") @RequiredPermission("system:dictionary:create") public DictionaryData createData(@RequestBody SystemConfigurationService.DictionaryDataCommand command){return service.createDictionaryData(command);}
    @PutMapping("/dictionaries/data/{id}") @RequiredPermission("system:dictionary:update") public DictionaryData updateData(@PathVariable Long id,@RequestBody SystemConfigurationService.DictionaryDataCommand command){return service.updateDictionaryData(id,command);}
    @DeleteMapping("/dictionaries/data/{id}") @RequiredPermission("system:dictionary:delete") public void deleteData(@PathVariable Long id){service.deleteDictionaryData(id);}
}
