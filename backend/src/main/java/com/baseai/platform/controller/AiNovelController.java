package com.baseai.platform.controller;

import com.baseai.platform.service.AiNovelService;
import com.baseai.platform.security.RequiredPermission;
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

/** 提供 AI 小说原著、章节、参考资料和影视改编接口。 */
@RestController
@RequestMapping("/api/ai/novels")
@RequiredPermission("ai:novel:invoke")
public class AiNovelController {
    private final AiNovelService service;

    /** 注入原著工作台服务。 */
    public AiNovelController(AiNovelService service) { this.service = service; }

    /** 查询当前用户的原著作品列表。 */
    @GetMapping
    public List<Map<String, Object>> list() { return service.listNovels(); }

    /** 查询原著详情及章节、参考资料。 */
    @GetMapping("/{novelId}")
    public Map<String, Object> get(@PathVariable long novelId) { return service.getNovel(novelId); }

    /** 创建原著作品。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return service.createNovel(payload); }

    /** 更新原著元数据或大纲。 */
    @PutMapping("/{novelId}")
    public Map<String, Object> update(@PathVariable long novelId, @RequestBody Map<String, Object> payload) { return service.updateNovel(novelId, payload); }

    /** 删除原著及其章节。 */
    @DeleteMapping("/{novelId}")
    public void delete(@PathVariable long novelId) { service.deleteNovel(novelId); }

    /** 新增章节。 */
    @PostMapping("/{novelId}/chapters")
    public Map<String, Object> createChapter(@PathVariable long novelId, @RequestBody Map<String, Object> payload) { return service.createChapter(novelId, payload); }

    /** 更新章节正文。 */
    @PutMapping("/{novelId}/chapters/{chapterId}")
    public Map<String, Object> updateChapter(@PathVariable long novelId, @PathVariable long chapterId,
                                              @RequestBody Map<String, Object> payload) { return service.updateChapter(novelId, chapterId, payload); }

    /** 删除章节。 */
    @DeleteMapping("/{novelId}/chapters/{chapterId}")
    public void deleteChapter(@PathVariable long novelId, @PathVariable long chapterId) { service.deleteChapter(novelId, chapterId); }

    /** 新增人物、世界观、文风或研究资料。 */
    @PostMapping("/{novelId}/references")
    public Map<String, Object> createReference(@PathVariable long novelId, @RequestBody Map<String, Object> payload) { return service.createReference(novelId, payload); }

    /** 更新写作参考资料。 */
    @PutMapping("/{novelId}/references/{referenceId}")
    public Map<String, Object> updateReference(@PathVariable long novelId, @PathVariable long referenceId,
                                                @RequestBody Map<String, Object> payload) { return service.updateReference(novelId, referenceId, payload); }

    /** 删除写作参考资料。 */
    @DeleteMapping("/{novelId}/references/{referenceId}")
    public void deleteReference(@PathVariable long novelId, @PathVariable long referenceId) { service.deleteReference(novelId, referenceId); }

    /** 调用 AI 生成大纲、章节、续写、改写、润色或摘要。 */
    @PostMapping("/{novelId}/ai/write")
    public Map<String, Object> write(@PathVariable long novelId, @RequestBody AiNovelService.WriteRequest request) { return service.write(novelId, request); }

    /** 从指定章节提取影视改编剧本并写入 AI 视频项目。 */
    @PostMapping("/{novelId}/extract-to-video")
    public Map<String, Object> extractToVideo(@PathVariable long novelId, @RequestBody AiNovelService.ExtractRequest request) { return service.extractToVideo(novelId, request); }
}
