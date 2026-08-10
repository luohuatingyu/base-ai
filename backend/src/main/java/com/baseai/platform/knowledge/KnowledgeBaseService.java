package com.baseai.platform.knowledge;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.workflow.WorkflowConnectionService;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 管理知识库、文档切片、向量索引及 RAG 检索的数据一致性。 */
@Service
public class KnowledgeBaseService {
    private static final Set<String> VECTOR_CONNECTION_TYPES = Set.of("POSTGRESQL", "QDRANT", "MILVUS", "ELASTICSEARCH");
    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private final JdbcTemplate jdbcTemplate;
    private final ConfigCryptoService cryptoService;
    private final WorkflowConnectionService connectionService;
    private final VectorStoreService vectorStoreService;
    private final AiChatClient aiChatClient;
    private final LlmManagementService llmManagementService;

    /** 注入系统元数据、加密、模型和外部向量存储组件。 */
    public KnowledgeBaseService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, ConfigCryptoService cryptoService,
                                WorkflowConnectionService connectionService, VectorStoreService vectorStoreService,
                                AiChatClient aiChatClient, LlmManagementService llmManagementService) {
        this.jdbcTemplate=jdbcTemplate;this.cryptoService=cryptoService;this.connectionService=connectionService;
        this.vectorStoreService=vectorStoreService;this.aiChatClient=aiChatClient;this.llmManagementService=llmManagementService;
    }

    /** 查询当前用户可见知识库，管理员可查看全部但写操作仍遵循所有者边界。 */
    public List<View> list() {
        AuthUser user=AuthContext.require();String sql="SELECT * FROM knowledge_base WHERE voided=false "+(user.roles().contains("ADMIN")?"":"AND owner_user_id=? ")+"ORDER BY id DESC";
        return user.roles().contains("ADMIN")?jdbcTemplate.query(sql,(rs,row)->map(rs)):jdbcTemplate.query(sql,(rs,row)->map(rs),user.id());
    }

    /** 返回当前所有者可供 RAG 节点选择的已启用知识库。 */
    public List<Option> options() {
        return jdbcTemplate.query("SELECT id,code,name,storage_type FROM knowledge_base WHERE voided=false AND enabled=true AND owner_user_id=? ORDER BY name",
            (rs,row)->new Option(rs.getLong("id"),rs.getString("code"),rs.getString("name"),rs.getString("storage_type")),AuthContext.require().id());
    }

    /** 创建知识库元数据，首次文档索引时再按模型实际输出维度创建外部资源。 */
    @Transactional
    public View create(Command command) {
        Validated validated=validate(command,null);String resource="MANAGED".equals(validated.resourceMode())
            ?"base_ai_kb_"+UUID.randomUUID().toString().replace("-",""):resource(command.resourceName());
        try{KeyHolder keys=new GeneratedKeyHolder();jdbcTemplate.update(connection->{PreparedStatement statement=connection.prepareStatement("""
            INSERT INTO knowledge_base(code,name,description,connection_id,storage_type,resource_mode,resource_name,
                embedding_model_id,distance_metric,chunk_size,chunk_overlap,owner_user_id,enabled)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,Statement.RETURN_GENERATED_KEYS);int index=1;statement.setString(index++,code(command.code()));statement.setString(index++,text(command.name()));
            statement.setString(index++,truncate(command.description(),500));statement.setLong(index++,command.connectionId());statement.setString(index++,validated.storageType());
            statement.setString(index++,validated.resourceMode());statement.setString(index++,resource);statement.setLong(index++,command.embeddingModelId());
            statement.setString(index++,validated.distance());statement.setInt(index++,validated.chunkSize());statement.setInt(index++,validated.chunkOverlap());
            statement.setLong(index++,AuthContext.require().id());statement.setBoolean(index,!Boolean.FALSE.equals(command.enabled()));return statement;},keys);
            return requireOwned(keys.getKey().longValue());}
        catch(org.springframework.dao.DataIntegrityViolationException exception){throw new BusinessException(409,"knowledge.codeExists");}
    }

    /** 更新不改变既有索引身份的知识库；已有文档时禁止更换连接、资源或向量模型。 */
    @Transactional
    public View update(Long id,Command command) {
        View existing=requireOwned(id);Validated validated=validate(command,existing);Integer documents=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE knowledge_base_id=?",Integer.class,id);
        if(documents!=null&&documents>0&&(!existing.connectionId().equals(command.connectionId())||!existing.embeddingModelId().equals(command.embeddingModelId())||!existing.distanceMetric().equals(validated.distance())
            ||!existing.resourceMode().equals(validated.resourceMode())||(!"MANAGED".equals(existing.resourceMode())&&!existing.resourceName().equals(resource(command.resourceName()))))) {
            throw new BusinessException("knowledge.reindexRequired");
        }
        String resourceName="MANAGED".equals(existing.resourceMode())?existing.resourceName():resource(command.resourceName());
        jdbcTemplate.update("""
            UPDATE knowledge_base SET code=?,name=?,description=?,connection_id=?,storage_type=?,resource_mode=?,resource_name=?,
                embedding_model_id=?,distance_metric=?,chunk_size=?,chunk_overlap=?,enabled=?,updated_at=NOW()
            WHERE id=? AND voided=false
            """,
            code(command.code()),text(command.name()),truncate(command.description(),500),command.connectionId(),validated.storageType(),validated.resourceMode(),resourceName,
            command.embeddingModelId(),validated.distance(),validated.chunkSize(),validated.chunkOverlap(),!Boolean.FALSE.equals(command.enabled()),id);
        return requireOwned(id);
    }

    /** 删除知识库；托管模式删除隔离资源，预建模式只清理平台写入的文档向量。 */
    @Transactional
    public void delete(Long id) {
        View existing=requireOwned(id);WorkflowConnectionService.StoredConnection connection=connection(existing);
        if(existing.embeddingDimension()!=null){
            if("MANAGED".equals(existing.resourceMode()))vectorStoreService.deleteResource(connection,existing.resourceName());
            else for(Long documentId:jdbcTemplate.queryForList("SELECT id FROM knowledge_document WHERE knowledge_base_id=?",Long.class,id))vectorStoreService.deleteDocument(connection,existing.resourceName(),documentId);
        }
        jdbcTemplate.update("UPDATE knowledge_base SET enabled=false,voided=true,updated_at=NOW() WHERE id=?",id);
    }

    /** 查询知识库文档状态。 */
    public List<DocumentView> documents(Long knowledgeBaseId) {
        requireVisible(knowledgeBaseId);return jdbcTemplate.query("SELECT * FROM knowledge_document WHERE knowledge_base_id=? ORDER BY id DESC",(rs,row)->new DocumentView(
            rs.getLong("id"),rs.getString("file_name"),rs.getString("content_type"),rs.getString("status"),rs.getInt("chunk_count"),rs.getString("error_message"),timestamp(rs.getTimestamp("created_at")),timestamp(rs.getTimestamp("updated_at"))),knowledgeBaseId);
    }

    /** 提取、切片、向量化并写入文档；状态始终反映完整批次最终结果。 */
    public DocumentView indexDocument(Long knowledgeBaseId,String fileName,String contentType,byte[] bytes) {
        View base=requireOwned(knowledgeBaseId);if(!base.enabled())throw new BusinessException("knowledge.disabled");
        if(bytes==null||bytes.length==0)throw new BusinessException("knowledge.documentEmpty");if(bytes.length>MAX_DOCUMENT_BYTES)throw new BusinessException("knowledge.documentTooLarge");
        String extracted=extract(bytes,fileName);List<String> chunks=chunks(extracted,base.chunkSize(),base.chunkOverlap());String hash=sha256(bytes);
        prepareFailedDocumentRetry(base,hash);
        Long documentId;
        try{documentId=insertDocument(base.id(),safeFileName(fileName),truncate(contentType,120),hash);}catch(org.springframework.dao.DataIntegrityViolationException exception){throw new BusinessException(409,"knowledge.documentExists");}
        try{
            List<List<Double>> embeddings=new ArrayList<>();for(int offset=0;offset<chunks.size();offset+=256){List<String> batch=chunks.subList(offset,Math.min(chunks.size(),offset+256));embeddings.addAll(aiChatClient.embed(base.embeddingModelId(),batch).embeddings());}
            int dimension=embeddings.get(0).size();if(embeddings.stream().anyMatch(item->item.size()!=dimension))throw new BusinessException("knowledge.embeddingResponseInvalid");
            if(base.embeddingDimension()!=null&&base.embeddingDimension()!=dimension)throw new BusinessException("knowledge.vectorDimensionMismatch");
            WorkflowConnectionService.StoredConnection connection=connection(base);vectorStoreService.ensureResource(connection,base.resourceName(),base.resourceMode(),dimension,base.distanceMetric());
            if(base.embeddingDimension()==null)jdbcTemplate.update("UPDATE knowledge_base SET embedding_dimension=?,updated_at=NOW() WHERE id=? AND embedding_dimension IS NULL",dimension,base.id());
            List<VectorStoreService.VectorItem> items=new ArrayList<>();for(int index=0;index<chunks.size();index++){long chunkId=insertChunk(base.id(),documentId,index,chunks.get(index));items.add(new VectorStoreService.VectorItem(chunkId,documentId,embeddings.get(index)));}
            vectorStoreService.upsert(connection,base.resourceName(),items);jdbcTemplate.update("UPDATE knowledge_document SET status='READY',chunk_count=?,error_message='',updated_at=NOW() WHERE id=?",chunks.size(),documentId);
        }catch(RuntimeException exception){jdbcTemplate.update("UPDATE knowledge_document SET status='FAILED',error_message=?,updated_at=NOW() WHERE id=?",truncate(exception instanceof BusinessException business?business.getMessageKey():"knowledge.indexFailed",500),documentId);throw exception;}
        return document(documentId);
    }

    /** 删除单个文档的外部向量和内部加密切片。 */
    @Transactional
    public void deleteDocument(Long knowledgeBaseId,Long documentId) {
        View base=requireOwned(knowledgeBaseId);Integer found=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE id=? AND knowledge_base_id=?",Integer.class,documentId,knowledgeBaseId);
        if(found==null||found==0)throw BusinessException.notFound("knowledge.documentNotFound");
        vectorStoreService.deleteDocument(connection(base),base.resourceName(),documentId);
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id=?",documentId);jdbcTemplate.update("DELETE FROM knowledge_document WHERE id=?",documentId);
    }

    /** 使用知识库固定向量模型检索并解密匹配片段。 */
    public Retrieval retrieve(Long knowledgeBaseId,String query,int topK,double threshold,Long ownerId) {
        View base=requireForOwner(knowledgeBaseId,ownerId);if(base.embeddingDimension()==null)throw new BusinessException("knowledge.notIndexed");
        String normalized=text(query);if(normalized.isBlank()||normalized.length()>500)throw new BusinessException("knowledge.queryInvalid");
        if(topK<1||topK>50||threshold<0||threshold>1)throw new BusinessException("knowledge.searchParametersInvalid");
        List<Double> vector=aiChatClient.embed(base.embeddingModelId(),List.of(normalized)).embeddings().get(0);if(vector.size()!=base.embeddingDimension())throw new BusinessException("knowledge.vectorDimensionMismatch");
        List<VectorStoreService.Match> matches=vectorStoreService.search(connection(base),base.resourceName(),vector,topK,threshold,base.distanceMetric());Map<Long,Chunk> chunks=loadChunks(matches.stream().map(VectorStoreService.Match::chunkId).toList());
        List<RetrievedChunk> output=new ArrayList<>();for(VectorStoreService.Match match:matches){Chunk chunk=chunks.get(match.chunkId());if(chunk!=null)output.add(new RetrievedChunk(chunk.id(),chunk.documentId(),chunk.fileName(),chunk.content(),match.score()));}
        return new Retrieval(base.id(),base.name(),List.copyOf(output));
    }

    /** 校验命令、向量模型和已探测连接能力。 */
    private Validated validate(Command command,View existing) {
        if(command==null||text(command.code()).isBlank()||text(command.name()).isBlank()||command.connectionId()==null||command.embeddingModelId()==null)throw new BusinessException("knowledge.invalid");
        WorkflowConnectionService.StoredConnection connection=connectionService.requireOwnedAndEnabled(command.connectionId(),AuthContext.require().id(),VECTOR_CONNECTION_TYPES);
        if(!"SUPPORTED".equals(connection.vectorStatus()))throw new BusinessException("knowledge.vectorConnectionUnverified");
        llmManagementService.resolveModel(command.embeddingModelId(),"embedding_model",false,null);
        String storage="POSTGRESQL".equals(connection.connectionType())?"PGVECTOR":connection.connectionType();String mode=text(command.resourceMode()).toUpperCase(Locale.ROOT);
        if(!Set.of("MANAGED","EXISTING").contains(mode))throw new BusinessException("knowledge.resourceModeInvalid");if("EXISTING".equals(mode))resource(command.resourceName());
        String distance=text(command.distanceMetric()).toUpperCase(Locale.ROOT);if(distance.isBlank())distance="COSINE";if(!Set.of("COSINE","L2","IP").contains(distance))throw new BusinessException("knowledge.distanceInvalid");
        int chunkSize=command.chunkSize()==null?400:command.chunkSize();int overlap=command.chunkOverlap()==null?60:command.chunkOverlap();if(chunkSize<50||chunkSize>500||overlap<0||overlap>=chunkSize)throw new BusinessException("knowledge.chunkConfigInvalid");
        return new Validated(storage,mode,distance,chunkSize,overlap);
    }

    /** 读取知识库引用的已授权向量连接。 */
    private WorkflowConnectionService.StoredConnection connection(View base){return connectionService.requireOwnedAndEnabled(base.connectionId(),base.ownerUserId(),VECTOR_CONNECTION_TYPES);}
    /** 当前用户必须拥有记录。 */
    private View requireOwned(Long id){return requireForOwner(id,AuthContext.require().id());}
    /** 读取指定所有者知识库，防止工作流跨租户引用。 */
    private View requireForOwner(Long id,Long ownerId){List<View> rows=jdbcTemplate.query("SELECT * FROM knowledge_base WHERE id=? AND owner_user_id=? AND voided=false",(rs,row)->map(rs),id,ownerId);if(rows.isEmpty())throw BusinessException.notFound("knowledge.notFound");return rows.get(0);}
    /** 管理列表允许管理员读取，但不改变写边界。 */
    private View requireVisible(Long id){AuthUser user=AuthContext.require();if(user.roles().contains("ADMIN")){List<View> rows=jdbcTemplate.query("SELECT * FROM knowledge_base WHERE id=? AND voided=false",(rs,row)->map(rs),id);if(!rows.isEmpty())return rows.get(0);}return requireOwned(id);}
    /** 插入索引中状态的文档并返回主键。 */
    private Long insertDocument(Long baseId,String fileName,String contentType,String hash){KeyHolder keys=new GeneratedKeyHolder();jdbcTemplate.update(connection->{PreparedStatement statement=connection.prepareStatement("INSERT INTO knowledge_document(knowledge_base_id,file_name,content_type,content_hash,status) VALUES (?,?,?,?, 'INDEXING')",Statement.RETURN_GENERATED_KEYS);statement.setLong(1,baseId);statement.setString(2,fileName);statement.setString(3,contentType);statement.setString(4,hash);return statement;},keys);return keys.getKey().longValue();}
    /** 同一文件仅允许替换失败记录，并在重传前清理可能存在的外部向量。 */
    private void prepareFailedDocumentRetry(View base,String hash){List<ExistingDocument> rows=jdbcTemplate.query("SELECT id,status FROM knowledge_document WHERE knowledge_base_id=? AND content_hash=?",(rs,row)->new ExistingDocument(rs.getLong("id"),rs.getString("status")),base.id(),hash);if(rows.isEmpty())return;ExistingDocument existing=rows.get(0);if(!"FAILED".equals(existing.status()))throw new BusinessException(409,"knowledge.documentExists");if(base.embeddingDimension()!=null)vectorStoreService.deleteDocument(connection(base),base.resourceName(),existing.id());jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id=?",existing.id());jdbcTemplate.update("DELETE FROM knowledge_document WHERE id=?",existing.id());}
    /** 加密保存切片并返回主键。 */
    private Long insertChunk(Long baseId,Long documentId,int index,String content){KeyHolder keys=new GeneratedKeyHolder();jdbcTemplate.update(connection->{PreparedStatement statement=connection.prepareStatement("INSERT INTO knowledge_chunk(knowledge_base_id,document_id,chunk_index,content_encrypted,content_hash) VALUES (?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);statement.setLong(1,baseId);statement.setLong(2,documentId);statement.setInt(3,index);statement.setString(4,cryptoService.encrypt(content));statement.setString(5,sha256(content.getBytes(StandardCharsets.UTF_8)));return statement;},keys);return keys.getKey().longValue();}
    /** 按匹配 ID 一次读取并解密切片。 */
    private Map<Long,Chunk> loadChunks(List<Long> ids){if(ids.isEmpty())return Map.of();String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));Map<Long,Chunk> result=new LinkedHashMap<>();jdbcTemplate.query("SELECT c.id,c.document_id,c.content_encrypted,d.file_name FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id WHERE c.id IN ("+placeholders+")",rs->{long id=rs.getLong(1);result.put(id,new Chunk(id,rs.getLong(2),rs.getString(4),cryptoService.decrypt(rs.getString(3))));},ids.toArray());return result;}
    /** 使用 Tika 自动检测常用文档并拒绝空正文。 */
    private String extract(byte[] bytes,String fileName){try{Metadata metadata=new Metadata();metadata.set("resourceName",safeFileName(fileName));BodyContentHandler handler=new BodyContentHandler(2_000_000);new AutoDetectParser().parse(new ByteArrayInputStream(bytes),handler,metadata,new ParseContext());String value=handler.toString().trim();if(value.isBlank())throw new BusinessException("knowledge.documentEmpty");return value;}catch(BusinessException exception){throw exception;}catch(Exception exception){throw new BusinessException("knowledge.documentInvalid");}}
    /** 按字符窗口切片并保留受控重叠。 */
    static List<String> chunks(String content,int size,int overlap){String normalized=content.replace("\r\n","\n").trim();List<String> result=new ArrayList<>();int start=0;while(start<normalized.length()){int end=Math.min(normalized.length(),start+size);if(end<normalized.length()){int boundary=normalized.lastIndexOf('\n',end);if(boundary>start+size/2)end=boundary;}String item=normalized.substring(start,end).trim();if(!item.isBlank())result.add(item);if(end>=normalized.length())break;start=Math.max(start+1,end-overlap);}if(result.isEmpty())throw new BusinessException("knowledge.documentEmpty");return List.copyOf(result);}
    /** 映射知识库视图。 */
    private View map(java.sql.ResultSet rs)throws java.sql.SQLException{int dimension=rs.getInt("embedding_dimension");return new View(rs.getLong("id"),rs.getString("code"),rs.getString("name"),rs.getString("description"),rs.getLong("connection_id"),rs.getString("storage_type"),rs.getString("resource_mode"),rs.getString("resource_name"),rs.getLong("embedding_model_id"),rs.wasNull()?null:dimension,rs.getString("distance_metric"),rs.getInt("chunk_size"),rs.getInt("chunk_overlap"),rs.getLong("owner_user_id"),rs.getBoolean("enabled"),timestamp(rs.getTimestamp("created_at")),timestamp(rs.getTimestamp("updated_at")));}
    private DocumentView document(Long id){return jdbcTemplate.queryForObject("SELECT * FROM knowledge_document WHERE id=?",(rs,row)->new DocumentView(rs.getLong("id"),rs.getString("file_name"),rs.getString("content_type"),rs.getString("status"),rs.getInt("chunk_count"),rs.getString("error_message"),timestamp(rs.getTimestamp("created_at")),timestamp(rs.getTimestamp("updated_at"))),id);}
    private String code(String value){String normalized=text(value).toUpperCase(Locale.ROOT);if(!normalized.matches("[A-Z][A-Z0-9_-]{1,79}"))throw new BusinessException("workflow.codeInvalid");return normalized;}
    private String resource(String value){String normalized=text(value).toLowerCase(Locale.ROOT);if(!normalized.matches("[a-z][a-z0-9_]{2,119}"))throw new BusinessException("knowledge.resourceNameInvalid");return normalized;}
    private String safeFileName(String value){String normalized=text(value).replace("\\","/");normalized=normalized.substring(normalized.lastIndexOf('/')+1);return truncate(normalized.isBlank()?"document":normalized,255);}
    private String text(String value){return value==null?"":value.trim();}private String truncate(String value,int maximum){String normalized=text(value);return normalized.length()<=maximum?normalized:normalized.substring(0,maximum);}
    private String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception exception){throw new IllegalStateException(exception);}}
    private LocalDateTime timestamp(java.sql.Timestamp value){return value==null?null:value.toLocalDateTime();}

    public record Command(String code,String name,String description,Long connectionId,String resourceMode,String resourceName,Long embeddingModelId,String distanceMetric,Integer chunkSize,Integer chunkOverlap,Boolean enabled){}
    public record View(Long id,String code,String name,String description,Long connectionId,String storageType,String resourceMode,String resourceName,Long embeddingModelId,Integer embeddingDimension,String distanceMetric,int chunkSize,int chunkOverlap,Long ownerUserId,boolean enabled,LocalDateTime createdAt,LocalDateTime updatedAt){}
    public record Option(Long id,String code,String name,String storageType){}
    public record DocumentView(Long id,String fileName,String contentType,String status,int chunkCount,String errorMessage,LocalDateTime createdAt,LocalDateTime updatedAt){}
    public record RetrievedChunk(Long chunkId,Long documentId,String fileName,String content,double score){}
    public record Retrieval(Long knowledgeBaseId,String knowledgeBaseName,List<RetrievedChunk> matches){}
    private record Validated(String storageType,String resourceMode,String distance,int chunkSize,int chunkOverlap){}
    private record Chunk(Long id,Long documentId,String fileName,String content){}
    private record ExistingDocument(Long id,String status){}
}
