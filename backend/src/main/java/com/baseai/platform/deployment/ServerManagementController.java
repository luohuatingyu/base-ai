package com.baseai.platform.deployment;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceIgnored;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 提供服务器配置、连通性测试和部署记录接口。 */
@RestController
@RequestMapping("/api/servers")
public class ServerManagementController {
    private final ServerManagementService service;

    /** 注入服务器管理服务。 */
    public ServerManagementController(ServerManagementService service) { this.service = service; }
    /** 查询服务器列表。 */
    @GetMapping @RequiredPermission("operations:server:list") public List<ServerModels.ServerView> list() { return service.servers(); }
    /** 创建服务器。 */
    @PostMapping @RequiredPermission("operations:server:create") public ServerModels.ServerView create(@RequestBody ServerModels.ServerCommand command) { return service.create(command); }
    /** 更新服务器。 */
    @PutMapping("/{id}") @RequiredPermission("operations:server:update") public ServerModels.ServerView update(@PathVariable Long id, @RequestBody ServerModels.ServerCommand command) { return service.update(id, command); }
    /** 删除服务器。 */
    @DeleteMapping("/{id}") @RequiredPermission("operations:server:delete") public void delete(@PathVariable Long id) { service.delete(id); }
    /** 测试服务器。 */
    @PostMapping("/{id}/test") @RequiredPermission("operations:server:test") public Map<String, Object> test(@PathVariable Long id) { return service.test(id); }
    /** 异步部署或回滚指定版本。 */
    @PostMapping("/{id}/deploy") @RequiredPermission("operations:server:deploy") @TraceIgnored public ServerModels.DeploymentView deploy(@PathVariable Long id, @RequestBody ServerModels.DeploymentCommand command) { return service.deploy(id, command); }
    /** 查询服务器部署历史。 */
    @GetMapping("/{id}/deployments") @RequiredPermission("operations:server:logs") public List<ServerModels.DeploymentView> deployments(@PathVariable Long id) { return service.deployments(id); }
    /** 查询部署详情。 */
    @GetMapping("/deployments/{id}") @RequiredPermission("operations:server:logs") public ServerModels.DeploymentView deployment(@PathVariable Long id) { return service.deployment(id); }
}
