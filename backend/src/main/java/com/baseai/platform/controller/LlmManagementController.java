package com.baseai.platform.controller;

import com.baseai.platform.domain.LlmModel;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.LlmManagementService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM 供应商、模型和路由管理接口。
 *
 * <p>控制器只负责 HTTP 参数绑定、权限声明和服务委托，具体业务规则由
 * {@link LlmManagementService} 统一处理。</p>
 */
@RestController
@RequestMapping("/api/models")
public class LlmManagementController {
    private final LlmManagementService service;
    public LlmManagementController(LlmManagementService service){this.service=service;}

    /** 查询可用的模型供应商。 */
    @GetMapping("/providers") @RequiredPermission("model:provider:list") public List<LlmManagementService.ProviderView> providers(){return service.providers();}
    /** 查询指定供应商的明文 API Key，仅允许具备编辑权限的用户调用。 */
    @GetMapping("/providers/{id}/api-keys") @RequiredPermission("model:provider:update") public LlmManagementService.ProviderApiKeysView providerApiKeys(@PathVariable Long id){return service.providerApiKeys(id);}
    /** 创建模型供应商。 */
    @PostMapping("/providers") @RequiredPermission("model:provider:create") public LlmManagementService.ProviderView createProvider(@RequestBody LlmManagementService.ProviderCommand command){return service.createProvider(command);}
    /** 更新模型供应商。 */
    @PutMapping("/providers/{id}") @RequiredPermission("model:provider:update") public LlmManagementService.ProviderView updateProvider(@PathVariable Long id,@RequestBody LlmManagementService.ProviderCommand command){return service.updateProvider(id,command);}
    /** 删除模型供应商。 */
    @DeleteMapping("/providers/{id}") @RequiredPermission("model:provider:delete") public void deleteProvider(@PathVariable Long id){service.deleteProvider(id);}

    /** 查询模型列表。 */
    @GetMapping @RequiredPermission("model:model:list") public List<LlmModel> models(){return service.models();}
    /** 查询可配置的模型类型目录。 */
    @GetMapping("/model-types") @RequiredPermission("model:model:list") public List<LlmManagementService.ModelTypeOption> modelTypes(){return service.modelTypes();}
    /** 创建模型。 */
    @PostMapping @RequiredPermission("model:model:create") public LlmModel createModel(@RequestBody LlmManagementService.ModelCommand command){return service.createModel(command);}
    /** 更新模型。 */
    @PutMapping("/{id}") @RequiredPermission("model:model:update") public LlmModel updateModel(@PathVariable Long id,@RequestBody LlmManagementService.ModelCommand command){return service.updateModel(id,command);}
    /** 删除模型。 */
    @DeleteMapping("/{id}") @RequiredPermission("model:model:delete") public void deleteModel(@PathVariable Long id){service.deleteModel(id);}
    /** 测试指定模型的连通性和调用结果。 */
    @PostMapping("/{id}/test") @RequiredPermission("model:model:update") public Map<String,Object> testModel(@PathVariable Long id,@RequestParam(required=false) String thinkingLevel){return service.testModel(id,thinkingLevel);}

    /** 查询模型路由。 */
    @GetMapping("/routes") @RequiredPermission("model:route:list") public List<LlmManagementService.RouteView> routes(){return service.routes();}
    /** 创建模型路由。 */
    @PostMapping("/routes") @RequiredPermission("model:route:create") public LlmManagementService.RouteView createRoute(@RequestBody LlmManagementService.RouteCommand command){return service.createRoute(command);}
    /** 更新模型路由。 */
    @PutMapping("/routes/{id}") @RequiredPermission("model:route:update") public LlmManagementService.RouteView updateRoute(@PathVariable Long id,@RequestBody LlmManagementService.RouteCommand command){return service.updateRoute(id,command);}
    /** 删除模型路由。 */
    @DeleteMapping("/routes/{id}") @RequiredPermission("model:route:delete") public void deleteRoute(@PathVariable Long id){service.deleteRoute(id);}
    /** 检查模型可用性并将可用候选加载到内存路由。 */
    @PostMapping("/routes/sync") @RequiredPermission("model:route:update") public List<LlmManagementService.ModelHealthView> syncRoutes(@RequestBody(required=false) Map<String,List<Long>> body){return service.syncRoutes(body==null?List.of():body.get("providerIds"));}
    /** 从路由供应商池移除成员，并同步内存快照。 */
    @DeleteMapping("/routes/{routeId}/providers/{providerId}") @RequiredPermission("model:route:update") public void removeProvider(@PathVariable Long routeId,@PathVariable Long providerId){service.removeProviderFromRoute(routeId,providerId);}
}
