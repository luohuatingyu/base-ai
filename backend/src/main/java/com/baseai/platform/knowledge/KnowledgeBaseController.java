package com.baseai.platform.knowledge;

import com.baseai.platform.security.RequiredPermission;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** 提供知识库配置、文档索引和工作流资源选项接口。 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;
    /** 注入知识库服务。 */
    public KnowledgeBaseController(KnowledgeBaseService service){this.service=service;}
    /** 查询当前用户可见知识库。 */
    @GetMapping @RequiredPermission("knowledge:base:list") public List<KnowledgeBaseService.View> list(){return service.list();}
    /** 分页查询知识库管理视图及全局汇总。 */
    @GetMapping("/management") @RequiredPermission("knowledge:base:list")
    public KnowledgeBaseService.ManagementPage management(@RequestParam(required=false) String keyword,
        @RequestParam(required=false) Boolean enabled,@RequestParam(required=false) String storageType,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return service.management(keyword,enabled,storageType,page,size);
    }
    /** 查询当前用户可在工作流使用的知识库。 */
    @GetMapping("/options") @RequiredPermission("workflow:canvas:list") public List<KnowledgeBaseService.Option> options(){return service.options();}
    /** 创建知识库。 */
    @PostMapping @RequiredPermission("knowledge:base:create") public KnowledgeBaseService.View create(@RequestBody KnowledgeBaseService.Command command){return service.create(command);}
    /** 更新知识库。 */
    @PutMapping("/{id}") @RequiredPermission("knowledge:base:update") public KnowledgeBaseService.View update(@PathVariable Long id,@RequestBody KnowledgeBaseService.Command command){return service.update(id,command);}
    /** 快速启用或停用知识库。 */
    @PatchMapping("/{id}/enabled") @RequiredPermission("knowledge:base:update")
    public KnowledgeBaseService.View setEnabled(@PathVariable Long id,@RequestBody KnowledgeBaseService.EnabledCommand command){return service.setEnabled(id,command);}
    /** 删除知识库。 */
    @DeleteMapping("/{id}") @RequiredPermission("knowledge:base:delete") public void delete(@PathVariable Long id){service.delete(id);}
    /** 查询知识库文档。 */
    @GetMapping("/{id}/documents") @RequiredPermission("knowledge:base:list") public List<KnowledgeBaseService.DocumentView> documents(@PathVariable Long id){return service.documents(id);}
    /** 分页查询知识库文档和索引状态。 */
    @GetMapping("/{id}/documents/page") @RequiredPermission("knowledge:base:list")
    public KnowledgeBaseService.DocumentPage documentPage(@PathVariable Long id,@RequestParam(required=false) String keyword,
        @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size){return service.documentPage(id,keyword,status,page,size);}
    /** 上传并建立文档索引。 */
    @PostMapping(value="/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiredPermission("knowledge:base:update") public KnowledgeBaseService.DocumentView upload(@PathVariable Long id,@RequestPart("file") MultipartFile file)throws IOException{return service.indexDocument(id,file.getOriginalFilename(),file.getContentType(),file.getBytes());}
    /** 删除文档及其向量。 */
    @DeleteMapping("/{id}/documents/{documentId}") @RequiredPermission("knowledge:base:update") public void deleteDocument(@PathVariable Long id,@PathVariable Long documentId){service.deleteDocument(id,documentId);}
    /** 批量删除文档并返回逐项处理结果。 */
    @PostMapping("/{id}/documents/batch-delete") @RequiredPermission("knowledge:base:update")
    public KnowledgeBaseService.BatchDeleteResult deleteDocuments(@PathVariable Long id,@RequestBody KnowledgeBaseService.BatchDeleteCommand command){return service.deleteDocuments(id,command);}
}
