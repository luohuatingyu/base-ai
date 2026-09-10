package com.baseai.platform.datasource;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.baseai.platform.workflow.WorkflowConnectionTester;
import com.baseai.platform.workflow.WorkflowModels;
import com.baseai.platform.workflow.WorkflowNodeMarketplaceService;
import com.baseai.platform.workflow.WorkflowPluginOAuthService;
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

/** 提供全部受管数据源的配置、测试和插件 OAuth 接口。 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {
    private final WorkflowConnectionService connectionService;
    private final WorkflowConnectionTester connectionTester;
    private final WorkflowNodeMarketplaceService marketplaceService;
    private final WorkflowPluginOAuthService pluginOAuthService;

    /** 注入受管连接、连通性测试、插件目录和 OAuth 服务。 */
    public DataSourceController(WorkflowConnectionService connectionService,
                                WorkflowConnectionTester connectionTester,
                                WorkflowNodeMarketplaceService marketplaceService,
                                WorkflowPluginOAuthService pluginOAuthService) {
        this.connectionService = connectionService;
        this.connectionTester = connectionTester;
        this.marketplaceService = marketplaceService;
        this.pluginOAuthService = pluginOAuthService;
    }

    /** 查询当前用户可见的脱敏数据源。 */
    @GetMapping
    @RequiredPermission("operations:data-source:list")
    public List<WorkflowModels.ConnectionView> list() { return connectionService.connections(); }

    /** 创建由当前用户拥有的受管数据源。 */
    @PostMapping
    @RequiredPermission("operations:data-source:create")
    public WorkflowModels.ConnectionView create(@RequestBody WorkflowModels.ConnectionCommand command) {
        return connectionService.create(command);
    }

    /** 更新当前用户拥有的数据源并保留未改动的脱敏凭据。 */
    @PutMapping("/{id}")
    @RequiredPermission("operations:data-source:update")
    public WorkflowModels.ConnectionView update(@PathVariable Long id,
                                                @RequestBody WorkflowModels.ConnectionCommand command) {
        return connectionService.update(id, command);
    }

    /** 删除未被工作流版本或有效同步计划引用的数据源。 */
    @DeleteMapping("/{id}")
    @RequiredPermission("operations:data-source:delete")
    public void delete(@PathVariable Long id) { connectionService.delete(id); }

    /** 对当前用户拥有的数据源执行无副作用连通性测试。 */
    @PostMapping("/{id}/test")
    @RequiredPermission("operations:data-source:test")
    public Map<String, Object> test(@PathVariable Long id) { return connectionTester.test(id); }

    /** 实时探测数据源状态并返回轻量指标，同时刷新页面展示的最近检测结果。 */
    @GetMapping("/{id}/status")
    @RequiredPermission("operations:data-source:test")
    public Map<String, Object> status(@PathVariable Long id) { return connectionTester.test(id); }

    /** 查询已安装且可用于插件数据源配置的组件。 */
    @GetMapping("/plugin-component-options")
    @RequiredPermission("operations:data-source:list")
    public List<WorkflowModels.PluginComponentOption> pluginComponentOptions() {
        return marketplaceService.componentOptions();
    }

    /** 为当前用户拥有的插件数据源创建一次性 OAuth 授权请求。 */
    @PostMapping("/{id}/oauth/authorize")
    @RequiredPermission("operations:data-source:update")
    public WorkflowModels.PluginOAuthAuthorization authorizePlugin(
        @PathVariable Long id, @RequestBody WorkflowModels.PluginOAuthAuthorizeCommand command) {
        return pluginOAuthService.authorize(id, command);
    }

    /** 消费一次性 OAuth state 并把交换结果加密写回插件数据源。 */
    @PostMapping("/plugin-oauth/callback")
    @RequiredPermission("operations:data-source:update")
    public WorkflowModels.PluginOAuthCallbackResult pluginOAuthCallback(
        @RequestBody WorkflowModels.PluginOAuthCallbackCommand command) {
        return pluginOAuthService.callback(command);
    }
}
