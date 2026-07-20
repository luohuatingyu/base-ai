package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/menus")
@RequiredPermission("system:menu:manage")
public class MenuController {
    private final PlatformAdminService service;
    public MenuController(PlatformAdminService service) { this.service = service; }
    @GetMapping public List<PlatformAdminService.MenuView> list() { return service.menus(); }
    @PostMapping public PlatformAdminService.MenuView create(@RequestBody PlatformAdminService.MenuCommand command) { return service.createMenu(command); }
    @PutMapping("/{id}") public PlatformAdminService.MenuView update(@PathVariable Long id, @RequestBody PlatformAdminService.MenuCommand command) { return service.updateMenu(id, command); }
}
