package com.baseai.platform.aivideo;

import com.baseai.platform.security.RequiredPermission;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 提供 AI 视频工作台的查询、维护和任务状态接口。 */
@RestController
@RequestMapping("/api/ai-video")
public class AiVideoController {
    private final AiVideoService service;

    /** 注入 AI 视频业务服务。 */
    public AiVideoController(AiVideoService service) {
        this.service = service;
    }

    /** 查询项目、资产、任务和成片汇总。 */
    @GetMapping("/overview")
    @RequiredPermission("ai:video:dashboard:list")
    public Map<String, Object> overview() { return service.overview(); }

    /** 查询指定资源列表。 */
    @GetMapping("/{resource}")
    @RequiredPermission("ai:video:dashboard:list")
    public List<Map<String, Object>> list(@PathVariable String resource,
                                          @RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) String assetType) {
        service.requirePermission(resource, "list", assetType, null);
        return service.list(resource, projectId, assetType);
    }

    /** 创建项目、剧本、分镜、资产、任务、渲染或发布记录。 */
    @PostMapping("/{resource}")
    @RequiredPermission("ai:video:dashboard:list")
    public Map<String, Object> create(@PathVariable String resource,
                                       @RequestParam(required = false) String assetType,
                                       @RequestBody Map<String, Object> payload) {
        service.requirePermission(resource, "create", assetType, null);
        return service.create(resource, payload, assetType);
    }

    /** 将已完成的独立视频生成任务回填为当前项目的成片。 */
    @PostMapping("/videos/from-generation")
    @RequiredPermission("ai:video:video:create")
    public Map<String, Object> promoteGeneration(@RequestBody Map<String, Object> payload) {
        return service.promoteGeneration(payload);
    }

    /** 更新工作台资源。 */
    @PutMapping("/{resource}/{id}")
    @RequiredPermission("ai:video:dashboard:list")
    public Map<String, Object> update(@PathVariable String resource, @PathVariable Long id,
                                       @RequestBody Map<String, Object> payload) {
        service.requirePermission(resource, "update", null, id);
        return service.update(resource, id, payload);
    }

    /** 删除工作台资源。 */
    @DeleteMapping("/{resource}/{id}")
    public void delete(@PathVariable String resource, @PathVariable Long id) { service.requirePermission(resource, "delete", null, id); service.delete(resource, id); }

    /** 取消生成任务。 */
    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancelTask(@PathVariable Long id) { service.requirePermission("tasks", "update"); return service.cancelTask(id); }

    /** 重试失败或取消的生成任务。 */
    @PostMapping("/tasks/{id}/retry")
    public Map<String, Object> retryTask(@PathVariable Long id) { service.requirePermission("tasks", "update"); return service.retryTask(id); }

    /** 提交成片审核结果。 */
    @PostMapping("/videos/{videoId}/review")
    public Map<String, Object> review(@PathVariable Long videoId, @RequestBody Map<String, Object> payload) {
        service.requirePermission("reviews", "create");
        return service.reviewVideo(videoId, payload);
    }

    /** 将发布记录标记为已发布。 */
    @PostMapping("/publishes/{id}/complete")
    public Map<String, Object> completePublish(@PathVariable Long id) { service.requirePermission("publishes", "update"); return service.markPublished(id); }
}
