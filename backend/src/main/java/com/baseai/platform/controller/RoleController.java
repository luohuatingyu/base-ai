package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/roles")
@RequiredPermission("system:role:manage")
public class RoleController {
    private final PlatformAdminService service;
    public RoleController(PlatformAdminService service) { this.service = service; }
    @GetMapping public List<PlatformAdminService.RoleView> list() { return service.roles(); }
    @PostMapping public PlatformAdminService.RoleView create(@RequestBody PlatformAdminService.RoleCommand command) { return service.createRole(command); }
    @PutMapping("/{id}") public PlatformAdminService.RoleView update(@PathVariable Long id, @RequestBody PlatformAdminService.RoleCommand command) { return service.updateRole(id, command); }
}
