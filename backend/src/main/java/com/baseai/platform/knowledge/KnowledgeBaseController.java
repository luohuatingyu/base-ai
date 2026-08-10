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
    /** 查询当前用户可在工作流使用的知识库。 */
    @GetMapping("/options") @RequiredPermission("workflow:canvas:list") public List<KnowledgeBaseService.Option> options(){return service.options();}
    /** 创建知识库。 */
    @PostMapping @RequiredPermission("knowledge:base:create") public KnowledgeBaseService.View create(@RequestBody KnowledgeBaseService.Command command){return service.create(command);}
    /** 更新知识库。 */
    @PutMapping("/{id}") @RequiredPermission("knowledge:base:update") public KnowledgeBaseService.View update(@PathVariable Long id,@RequestBody KnowledgeBaseService.Command command){return service.update(id,command);}
    /** 删除知识库。 */
    @DeleteMapping("/{id}") @RequiredPermission("knowledge:base:delete") public void delete(@PathVariable Long id){service.delete(id);}
    /** 查询知识库文档。 */
    @GetMapping("/{id}/documents") @RequiredPermission("knowledge:base:list") public List<KnowledgeBaseService.DocumentView> documents(@PathVariable Long id){return service.documents(id);}
    /** 上传并建立文档索引。 */
    @PostMapping(value="/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiredPermission("knowledge:base:update") public KnowledgeBaseService.DocumentView upload(@PathVariable Long id,@RequestPart("file") MultipartFile file)throws IOException{return service.indexDocument(id,file.getOriginalFilename(),file.getContentType(),file.getBytes());}
    /** 删除文档及其向量。 */
    @DeleteMapping("/{id}/documents/{documentId}") @RequiredPermission("knowledge:base:update") public void deleteDocument(@PathVariable Long id,@PathVariable Long documentId){service.deleteDocument(id,documentId);}
}
