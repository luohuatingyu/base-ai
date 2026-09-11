package com.baseai.platform.deployment;

import com.baseai.platform.security.RequiredPermission;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 提供服务器凭据的增删改查接口。 */
@RestController
@RequestMapping("/api/server-credentials")
public class ServerCredentialController {
    private final ServerCredentialService service;
    public ServerCredentialController(ServerCredentialService service) { this.service = service; }
    @GetMapping @RequiredPermission("operations:server:list") public List<CredentialModels.View> list() { return service.list(); }
    @PostMapping @RequiredPermission("operations:server:create") public CredentialModels.View create(@RequestBody CredentialModels.Command command) { return service.save(null, command); }
    @PutMapping("/{id}") @RequiredPermission("operations:server:update") public CredentialModels.View update(@PathVariable Long id, @RequestBody CredentialModels.Command command) { return service.save(id, command); }
    @DeleteMapping("/{id}") @RequiredPermission("operations:server:delete") public void delete(@PathVariable Long id) { service.delete(id); }
}
