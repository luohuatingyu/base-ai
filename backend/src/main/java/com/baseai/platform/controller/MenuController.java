package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/menus")
public class MenuController {
    private final PlatformAdminService service;
    public MenuController(PlatformAdminService service) { this.service = service; }
    @GetMapping @RequiredPermission("system:menu:list") public List<PlatformAdminService.MenuView> list() { return service.menus(); }
    @PostMapping @RequiredPermission("system:menu:create") public PlatformAdminService.MenuView create(@RequestBody PlatformAdminService.MenuCommand command) { return service.createMenu(command); }
    @PutMapping("/{id}") @RequiredPermission("system:menu:update") public PlatformAdminService.MenuView update(@PathVariable Long id, @RequestBody PlatformAdminService.MenuCommand command) { return service.updateMenu(id, command); }
    @DeleteMapping("/{id}") @RequiredPermission("system:menu:delete") public void delete(@PathVariable Long id) { service.deleteMenu(id); }
}
