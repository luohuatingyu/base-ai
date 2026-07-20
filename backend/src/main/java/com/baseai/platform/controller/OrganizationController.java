package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class OrganizationController {
    private final PlatformAdminService service;

    public OrganizationController(PlatformAdminService service) { this.service = service; }

    @GetMapping("/departments") @RequiredPermission("system:department:list")
    public List<PlatformAdminService.DepartmentView> departments() { return service.departments(); }
    @PostMapping("/departments") @RequiredPermission("system:department:create")
    public PlatformAdminService.DepartmentView createDepartment(@RequestBody PlatformAdminService.DepartmentCommand command) { return service.createDepartment(command); }
    @PutMapping("/departments/{id}") @RequiredPermission("system:department:update")
    public PlatformAdminService.DepartmentView updateDepartment(@PathVariable Long id, @RequestBody PlatformAdminService.DepartmentCommand command) { return service.updateDepartment(id, command); }
    @DeleteMapping("/departments/{id}") @RequiredPermission("system:department:delete")
    public void deleteDepartment(@PathVariable Long id) { service.deleteDepartment(id); }

    @GetMapping("/positions") @RequiredPermission("system:position:list")
    public List<PlatformAdminService.PositionView> positions() { return service.positions(); }
    @PostMapping("/positions") @RequiredPermission("system:position:create")
    public PlatformAdminService.PositionView createPosition(@RequestBody PlatformAdminService.PositionCommand command) { return service.createPosition(command); }
    @PutMapping("/positions/{id}") @RequiredPermission("system:position:update")
    public PlatformAdminService.PositionView updatePosition(@PathVariable Long id, @RequestBody PlatformAdminService.PositionCommand command) { return service.updatePosition(id, command); }
    @DeleteMapping("/positions/{id}") @RequiredPermission("system:position:delete")
    public void deletePosition(@PathVariable Long id) { service.deletePosition(id); }
}
