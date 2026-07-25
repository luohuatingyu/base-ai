package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.ApiKeyManagementService;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system/api-keys")
public class ApiKeyManagementController {
    private final ApiKeyManagementService service;

    public ApiKeyManagementController(ApiKeyManagementService service) {
        this.service = service;
    }

    /** 分页查询 API Key。 */
    @GetMapping
    @RequiredPermission("system:api-key:list")
    public PlatformAdminService.PageResult<ApiKeyManagementService.ApiKeyView> list(
        @RequestParam(required = false) String keyword, @RequestParam(required = false) Boolean enabled,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "5") int size) {
        return service.list(keyword, enabled, page, size);
    }

    /** 查询可绑定的启用用户。 */
    @GetMapping("/owners")
    @RequiredPermission("system:api-key:list")
    public List<ApiKeyManagementService.OwnerView> owners() {
        return service.owners();
    }

    /** 查询代码允许开放的 API 目录。 */
    @GetMapping("/endpoints")
    @RequiredPermission("system:api-key:list")
    public List<com.baseai.platform.security.ApiKeyEndpointCatalogService.EndpointView> endpoints() {
        return service.endpoints();
    }

    /** 创建并一次性返回完整 API Key。 */
    @PostMapping
    @RequiredPermission("system:api-key:create")
    public ApiKeyManagementService.CreatedApiKey create(@RequestBody ApiKeyManagementService.ApiKeyCommand command) {
        return service.create(command);
    }

    /** 更新 API Key 配置。 */
    @PutMapping("/{id}")
    @RequiredPermission("system:api-key:update")
    public ApiKeyManagementService.ApiKeyView update(@PathVariable Long id,
                                                       @RequestBody ApiKeyManagementService.ApiKeyCommand command) {
        return service.update(id, command);
    }

    /** 轮换 API Key Secret。 */
    @PostMapping("/{id}/rotate")
    @RequiredPermission("system:api-key:rotate")
    public ApiKeyManagementService.RotatedApiKey rotate(@PathVariable Long id) {
        return service.rotate(id);
    }

    /** 启用 API Key。 */
    @PostMapping("/{id}/enable")
    @RequiredPermission("system:api-key:update")
    public ApiKeyManagementService.ApiKeyView enable(@PathVariable Long id) {
        return service.changeEnabled(id, true);
    }

    /** 停用 API Key。 */
    @PostMapping("/{id}/disable")
    @RequiredPermission("system:api-key:update")
    public ApiKeyManagementService.ApiKeyView disable(@PathVariable Long id) {
        return service.changeEnabled(id, false);
    }

    /** 永久吊销 API Key。 */
    @DeleteMapping("/{id}")
    @RequiredPermission("system:api-key:delete")
    public void revoke(@PathVariable Long id) {
        service.revoke(id);
    }
}
