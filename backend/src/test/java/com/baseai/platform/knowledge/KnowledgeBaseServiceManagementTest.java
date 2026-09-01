package com.baseai.platform.knowledge;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceManagementTest {
    private JdbcTemplate jdbcTemplate;
    private WorkflowConnectionService connectionService;
    private VectorStoreService vectorStoreService;
    private KnowledgeBaseService service;

    /** 创建 MySQL 兼容内存表与隔离依赖，验证真实分页 SQL。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource=new JdbcDataSource();dataSource.setURL("jdbc:h2:mem:knowledge-management-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate=new JdbcTemplate(dataSource);jdbcTemplate.execute("""
            CREATE TABLE knowledge_base(id BIGINT PRIMARY KEY,code VARCHAR(80),name VARCHAR(120),description VARCHAR(500),
              connection_id BIGINT,storage_type VARCHAR(24),resource_mode VARCHAR(16),resource_name VARCHAR(120),
              embedding_model_id BIGINT,embedding_dimension INT,distance_metric VARCHAR(16),chunk_size INT,chunk_overlap INT,
              owner_user_id BIGINT,enabled BOOLEAN,voided BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,knowledge_base_id BIGINT,file_name VARCHAR(255),
              content_type VARCHAR(120),content_hash VARCHAR(64),status VARCHAR(20),chunk_count INT,error_message VARCHAR(500),
              created_at TIMESTAMP,updated_at TIMESTAMP)
            """);
        jdbcTemplate.execute("CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,knowledge_base_id BIGINT,document_id BIGINT,chunk_index INT,content_encrypted CLOB,content_hash VARCHAR(64),created_at TIMESTAMP)");
        connectionService=mock(WorkflowConnectionService.class);vectorStoreService=mock(VectorStoreService.class);
        WorkflowConnectionService.StoredConnection connection=new WorkflowConnectionService.StoredConnection(21L,"VECTOR","Vector","QDRANT",new ObjectMapper().createObjectNode(),7L,true,null,null);
        when(connectionService.requireOwnedAndEnabled(anyLong(),anyLong(),anySet())).thenReturn(connection);
        service=new KnowledgeBaseService(jdbcTemplate,mock(com.baseai.platform.automation.ConfigCryptoService.class),connectionService,
            vectorStoreService,mock(com.baseai.platform.service.AiChatClient.class),mock(com.baseai.platform.service.LlmManagementService.class),mock(com.baseai.platform.document.DocumentParser.class));
        insertBase(1L,"ALPHA","Alpha handbook",7L,true,"PGVECTOR",10);insertBase(2L,"BETA","Beta docs",8L,false,"QDRANT",20);insertBase(3L,"VOID","Voided docs",8L,true,"MILVUS",30);
        jdbcTemplate.update("UPDATE knowledge_base SET voided=true WHERE id=3");
        insertDocument(11L,1L,"guide.txt","READY",3,"");insertDocument(12L,1L,"failed.pdf","FAILED",0,"knowledge.indexFailed");
        insertDocument(21L,2L,"other.txt","READY",2,"");insertDocument(31L,3L,"voided.txt","FAILED",0,"knowledge.indexFailed");authenticate(7L,Set.of());
    }

    /** 清理线程登录态，避免权限测试相互污染。 */
    @AfterEach
    void tearDown(){AuthContext.clear();}

    /** 普通用户分页筛选必须隔离所有者，并返回不受筛选影响的准确汇总。 */
    @Test
    void pagesFiltersAndSummarizesOwnedKnowledgeBases(){
        KnowledgeBaseService.ManagementPage page=service.management("alpha",true,"pgvector",0,200);
        assertEquals(1,page.page());assertEquals(50,page.size());assertEquals(1,page.total());assertEquals(1,page.items().size());
        assertEquals(2,page.items().get(0).documentCount());assertEquals(1,page.items().get(0).readyDocumentCount());
        assertEquals(1,page.items().get(0).failedDocumentCount());assertEquals(3,page.items().get(0).chunkCount());
        assertEquals(new KnowledgeBaseService.ManagementSummary(1,1,2,1),page.summary());
        assertEquals(0,service.management("%",null,null,1,20).total());
    }

    /** 管理员可分页查看全部知识库，但汇总仍排除已作废记录。 */
    @Test
    void administratorSeesAllVisibleKnowledgeBases(){
        authenticate(99L,Set.of("ADMIN"));KnowledgeBaseService.ManagementPage page=service.management(null,null,null,1,1);
        assertEquals(2,page.total());assertEquals(1,page.items().size());assertEquals(new KnowledgeBaseService.ManagementSummary(2,1,3,1),page.summary());
    }

    /** 文档分页必须组合文件名与状态筛选并保持知识库隔离。 */
    @Test
    void pagesDocumentsByNameAndStatus(){
        KnowledgeBaseService.DocumentPage page=service.documentPage(1L,"failed","failed",1,20);
        assertEquals(1,page.total());assertEquals("failed.pdf",page.items().get(0).fileName());assertEquals("knowledge.indexFailed",page.items().get(0).errorMessage());
        assertThrows(BusinessException.class,()->service.documentPage(1L,"x".repeat(256),null,1,20));
        assertThrows(BusinessException.class,()->service.documentPage(1L,null,"UNKNOWN",1,20));
    }

    /** 启停操作仅允许拥有者，管理员查看权限不得扩大为跨用户写权限。 */
    @Test
    void togglesOnlyOwnedKnowledgeBase(){
        assertFalse(service.setEnabled(1L,new KnowledgeBaseService.EnabledCommand(false)).enabled());
        authenticate(99L,Set.of("ADMIN"));assertEquals(404,assertThrows(BusinessException.class,()->service.setEnabled(1L,new KnowledgeBaseService.EnabledCommand(true))).getStatus());
        assertThrows(BusinessException.class,()->service.setEnabled(2L,new KnowledgeBaseService.EnabledCommand(null)));
    }

    /** 批量删除必须去重并保留不存在或外部向量删除失败的文档。 */
    @Test
    void batchDeleteReturnsPerDocumentFailures(){
        doThrow(new BusinessException("knowledge.vectorWriteFailed")).when(vectorStoreService).deleteDocument(any(),anyString(),eq(12L));
        KnowledgeBaseService.BatchDeleteResult result=service.deleteDocuments(1L,new KnowledgeBaseService.BatchDeleteCommand(List.of(11L,11L,12L,99L)));
        assertEquals(List.of(11L),result.deletedIds());assertEquals(2,result.failures().size());
        assertTrue(result.failures().stream().anyMatch(item->item.documentId()==12L&&"knowledge.vectorWriteFailed".equals(item.messageKey())));
        assertTrue(result.failures().stream().anyMatch(item->item.documentId()==99L&&"knowledge.documentNotFound".equals(item.messageKey())));
        assertEquals(0,jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE id=11",Integer.class));
        assertEquals(1,jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE id=12",Integer.class));
    }

    /** 空集合、非法 ID 和超量集合不得触发批量删除。 */
    @Test
    void rejectsInvalidDocumentBatches(){
        assertThrows(BusinessException.class,()->service.deleteDocuments(1L,new KnowledgeBaseService.BatchDeleteCommand(List.of())));
        assertThrows(BusinessException.class,()->service.deleteDocuments(1L,new KnowledgeBaseService.BatchDeleteCommand(List.of(-1L))));
        assertThrows(BusinessException.class,()->service.deleteDocuments(1L,new KnowledgeBaseService.BatchDeleteCommand(java.util.stream.LongStream.rangeClosed(1,101).boxed().toList())));
    }

    /** 插入具有稳定时间顺序的知识库测试数据。 */
    private void insertBase(Long id,String code,String name,Long owner,boolean enabled,String storage,int minute){jdbcTemplate.update("""
        INSERT INTO knowledge_base VALUES (?,?,?,?,21,?,'MANAGED',?,31,768,'COSINE',400,60,?,?,false,CURRENT_TIMESTAMP,DATEADD('MINUTE',?,CURRENT_TIMESTAMP))
        """,id,code,name,name+" description",storage,"base_"+id,owner,enabled,minute);}

    /** 插入文档状态与切片统计测试数据。 */
    private void insertDocument(Long id,Long baseId,String name,String status,int chunks,String error){jdbcTemplate.update("""
        INSERT INTO knowledge_document VALUES (?,?,?,'text/plain',?, ?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        """,id,baseId,name,"hash-"+id,status,chunks,error);}

    /** 设置固定登录用户及角色。 */
    private void authenticate(Long id,Set<String> roles){AuthContext.set(new AuthUser(id,"user-"+id,roles,Set.of(),AuthenticationType.TOKEN,null,null));}
}
