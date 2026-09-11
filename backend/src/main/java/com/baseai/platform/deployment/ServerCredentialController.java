package com.baseai.platform.deployment;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceType;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 提供服务器凭据的增删改查接口。 */
@RestController
@RequestMapping("/api/server-credentials")
public class ServerCredentialController {
    private final ServerCredentialService service;
    /** 注入凭据服务。 */
    public ServerCredentialController(ServerCredentialService service) { this.service = service; }
    /** 查询脱敏凭据。 */
    @GetMapping @RequiredPermission("operations:server:list") public List<CredentialModels.View> list() { return service.list(); }
    /** 创建凭据时禁止追踪和审计请求正文。 */
    @TraceType(value = "SERVER_CREDENTIAL", captureRequest = false)
    @PostMapping @RequiredPermission("operations:server:create") public CredentialModels.View create(@RequestBody CredentialModels.Command command) { return service.save(null, command); }
    /** 编辑凭据时禁止记录包含秘密的请求正文。 */
    @TraceType(value = "SERVER_CREDENTIAL", captureRequest = false)
    @PutMapping("/{id}") @RequiredPermission("operations:server:update") public CredentialModels.View update(@PathVariable Long id, @RequestBody CredentialModels.Command command) { return service.save(id, command); }
    /** 删除未引用凭据。 */
    @DeleteMapping("/{id}") @RequiredPermission("operations:server:delete") public void delete(@PathVariable Long id) { service.delete(id); }
    /** 使用显式 POST 记录查看动作，服务层额外校验管理员身份。 */
    @TraceType(value = "SERVER_CREDENTIAL", captureRequest = false)
    @PostMapping("/{id}/secret") @RequiredPermission("operations:server:list")
    public CredentialModels.Secrets reveal(@PathVariable Long id, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.reveal(id);
    }
}
