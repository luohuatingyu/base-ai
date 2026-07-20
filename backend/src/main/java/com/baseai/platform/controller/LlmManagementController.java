package com.baseai.platform.controller;

import com.baseai.platform.domain.LlmModel;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.LlmManagementService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class LlmManagementController {
    private final LlmManagementService service;
    public LlmManagementController(LlmManagementService service){this.service=service;}

    @GetMapping("/providers") @RequiredPermission("model:provider:list") public List<LlmManagementService.ProviderView> providers(){return service.providers();}
    @PostMapping("/providers") @RequiredPermission("model:provider:create") public LlmManagementService.ProviderView createProvider(@RequestBody LlmManagementService.ProviderCommand command){return service.createProvider(command);}
    @PutMapping("/providers/{id}") @RequiredPermission("model:provider:update") public LlmManagementService.ProviderView updateProvider(@PathVariable Long id,@RequestBody LlmManagementService.ProviderCommand command){return service.updateProvider(id,command);}
    @DeleteMapping("/providers/{id}") @RequiredPermission("model:provider:delete") public void deleteProvider(@PathVariable Long id){service.deleteProvider(id);}

    @GetMapping @RequiredPermission("model:model:list") public List<LlmModel> models(){return service.models();}
    @PostMapping @RequiredPermission("model:model:create") public LlmModel createModel(@RequestBody LlmManagementService.ModelCommand command){return service.createModel(command);}
    @PutMapping("/{id}") @RequiredPermission("model:model:update") public LlmModel updateModel(@PathVariable Long id,@RequestBody LlmManagementService.ModelCommand command){return service.updateModel(id,command);}
    @DeleteMapping("/{id}") @RequiredPermission("model:model:delete") public void deleteModel(@PathVariable Long id){service.deleteModel(id);}
    @PostMapping("/{id}/test") @RequiredPermission("model:model:update") public Map<String,Object> testModel(@PathVariable Long id){return service.testModel(id);}

    @GetMapping("/routes") @RequiredPermission("model:route:list") public List<LlmManagementService.RouteView> routes(){return service.routes();}
    @PostMapping("/routes") @RequiredPermission("model:route:create") public LlmManagementService.RouteView createRoute(@RequestBody LlmManagementService.RouteCommand command){return service.createRoute(command);}
    @PutMapping("/routes/{id}") @RequiredPermission("model:route:update") public LlmManagementService.RouteView updateRoute(@PathVariable Long id,@RequestBody LlmManagementService.RouteCommand command){return service.updateRoute(id,command);}
    @DeleteMapping("/routes/{id}") @RequiredPermission("model:route:delete") public void deleteRoute(@PathVariable Long id){service.deleteRoute(id);}
}
