package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/users")
@RequiredPermission("system:user:manage")
public class UserController {
    private final PlatformAdminService service;
    public UserController(PlatformAdminService service) { this.service = service; }
    @GetMapping public List<PlatformAdminService.UserView> list() { return service.users(); }
    @PostMapping public PlatformAdminService.UserView create(@RequestBody PlatformAdminService.UserCommand command) { return service.createUser(command); }
    @PutMapping("/{id}") public PlatformAdminService.UserView update(@PathVariable Long id, @RequestBody PlatformAdminService.UserCommand command) { return service.updateUser(id, command); }
}
