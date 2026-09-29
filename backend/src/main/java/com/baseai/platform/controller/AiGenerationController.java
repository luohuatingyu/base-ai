package com.baseai.platform.controller;

import com.baseai.platform.modeltype.ArkMediaProtocol;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.MediaGenerationService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 独立媒体生成入口；历史聊天接口保持兼容，任务始终按用户归属隔离。 */
@RestController
@RequestMapping("/api/ai/generation")
@RequiredPermission("ai:generation:invoke")
public class AiGenerationController {
    private final MediaGenerationService service;
    /** 注入已有生成服务，避免新旧接口的状态机分叉。 */
    public AiGenerationController(MediaGenerationService service) { this.service = service; }
    /** 获取当前可用的生成模型及版本能力。 */
    @GetMapping("/models") public List<MediaGenerationService.ModelOption> models() { return service.models(); }
    /** 返回当前用户指定输出类型的历史任务。 */
    @GetMapping public Map<String, Object> list(@RequestParam(defaultValue="image_model") String type, @RequestParam(defaultValue="0") int page) { return service.list(type, page); }
    /** 创建一次生成任务，使用平台统一请求格式。 */
    @PostMapping public Map<String, Object> generate(@RequestBody MediaGenerationService.GenerateRequest request) { return service.generate(request); }
    /** 查询任务时再次检查归属，并同步供应商终态。 */
    @GetMapping("/{id}") public Map<String, Object> status(@PathVariable long id) { return service.status(id); }
    /** 取消任务通过统一服务协调供应商与本地状态。 */
    @PostMapping("/{id}/cancel") public Map<String, Object> cancel(@PathVariable long id) { return service.cancel(id); }
    /** 只允许删除已结束的当前用户任务。 */
    @DeleteMapping("/{id}") public void delete(@PathVariable long id) { service.delete(id); }
    /** 模型管理员配置自定义接入点时获取可选能力画像。 */
    @GetMapping("/profiles") @RequiredPermission("ai:model:model:list")
    public List<Map<String, Object>> profiles() { return ArkMediaProtocol.profiles().stream().map(ArkMediaProtocol::capabilities).toList(); }
}
