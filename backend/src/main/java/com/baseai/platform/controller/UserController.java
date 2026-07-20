package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/users")
public class UserController {
    private final PlatformAdminService service;
    public UserController(PlatformAdminService service) { this.service = service; }
    /** 分页查询用户。 */
    @GetMapping @RequiredPermission("system:user:list")
    public PlatformAdminService.PageResult<PlatformAdminService.UserView> list(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) Boolean enabled, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.users(keyword, enabled, page, size);
    }
    @PostMapping @RequiredPermission("system:user:create") public PlatformAdminService.UserView create(@RequestBody PlatformAdminService.UserCommand command) { return service.createUser(command); }
    @PutMapping("/{id}") @RequiredPermission("system:user:update") public PlatformAdminService.UserView update(@PathVariable Long id, @RequestBody PlatformAdminService.UserCommand command) { return service.updateUser(id, command); }
    @DeleteMapping("/{id}") @RequiredPermission("system:user:delete") public void delete(@PathVariable Long id) { service.deleteUser(id); }
}
