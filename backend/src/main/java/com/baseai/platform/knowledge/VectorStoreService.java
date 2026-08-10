package com.baseai.platform.knowledge;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** 以统一契约访问 pgvector、Qdrant、Milvus 和 Elasticsearch 向量存储。 */
@Service
public class VectorStoreService {
    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 注入 JSON 工具，HTTP 客户端保持禁止重定向以配合出站策略。 */
    public VectorStoreService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    /** 无副作用探测连接是否真正具备向量能力。 */
    public Capability probe(WorkflowConnectionService.StoredConnection connection) {
        return switch (connection.connectionType()) {
            case "POSTGRESQL" -> probePg(connection.config());
            case "QDRANT" -> probeQdrant(connection.config());
            case "MILVUS" -> probeMilvus(connection.config());
            case "ELASTICSEARCH" -> probeElasticsearch(connection.config());
            default -> new Capability(false, connection.connectionType(), "", "connection type is not a vector store");
        };
    }

    /** 创建平台托管资源或严格校验预建资源的维度与距离算法。 */
    public void ensureResource(WorkflowConnectionService.StoredConnection connection, String resource, String mode,
                               int dimension, String distance) {
        validateResource(resource); validateDimension(dimension);
        boolean managed = "MANAGED".equalsIgnoreCase(mode);
        switch (connection.connectionType()) {
            case "POSTGRESQL" -> ensurePg(connection.config(), resource, managed, dimension, distance);
            case "QDRANT" -> ensureQdrant(connection.config(), resource, managed, dimension, distance);
            case "MILVUS" -> ensureMilvus(connection.config(), resource, managed, dimension, distance);
            case "ELASTICSEARCH" -> ensureElasticsearch(connection.config(), resource, managed, dimension, distance);
            default -> throw new BusinessException("knowledge.vectorStoreUnsupported");
        }
    }

    /** 幂等写入一个批次的切片向量。 */
    public void upsert(WorkflowConnectionService.StoredConnection connection, String resource, List<VectorItem> items) {
        if (items == null || items.isEmpty()) return;
        switch (connection.connectionType()) {
            case "POSTGRESQL" -> upsertPg(connection.config(), resource, items);
            case "QDRANT" -> upsertQdrant(connection.config(), resource, items);
            case "MILVUS" -> upsertMilvus(connection.config(), resource, items);
            case "ELASTICSEARCH" -> upsertElasticsearch(connection.config(), resource, items);
            default -> throw new BusinessException("knowledge.vectorStoreUnsupported");
        }
    }

    /** 在单一知识库资源内执行向量相似度检索。 */
    public List<Match> search(WorkflowConnectionService.StoredConnection connection, String resource,
                              List<Double> vector, int topK, double threshold, String distance) {
        return switch (connection.connectionType()) {
            case "POSTGRESQL" -> searchPg(connection.config(), resource, vector, topK, threshold, distance);
            case "QDRANT" -> searchQdrant(connection.config(), resource, vector, topK, threshold, distance);
            case "MILVUS" -> searchMilvus(connection.config(), resource, vector, topK, threshold, distance);
            case "ELASTICSEARCH" -> searchElasticsearch(connection.config(), resource, vector, topK, threshold);
            default -> throw new BusinessException("knowledge.vectorStoreUnsupported");
        };
    }

    /** 删除平台管理的隔离资源；预建资源由调用方选择不调用本方法。 */
    public void deleteResource(WorkflowConnectionService.StoredConnection connection, String resource) {
        validateResource(resource);
        switch (connection.connectionType()) {
            case "POSTGRESQL" -> executePg(connection.config(), "DROP TABLE IF EXISTS " + resource);
            case "QDRANT" -> request(connection.config(), "DELETE", "/collections/" + resource, null);
            case "MILVUS" -> request(connection.config(), "POST", "/v2/vectordb/collections/drop",
                objectMapper.createObjectNode().put("dbName",connection.config().path("database").asText("default")).put("collectionName", resource));
            case "ELASTICSEARCH" -> request(connection.config(), "DELETE", "/" + resource, null);
            default -> throw new BusinessException("knowledge.vectorStoreUnsupported");
        }
    }

    /** 只删除平台写入的指定文档向量，预建资源本身永不被删除。 */
    public void deleteDocument(WorkflowConnectionService.StoredConnection connection,String resource,long documentId){
        validateResource(resource);
        switch(connection.connectionType()){
            case "POSTGRESQL" -> {try(Connection value=pg(connection.config());PreparedStatement statement=value.prepareStatement("DELETE FROM "+resource+" WHERE document_id=?")){statement.setLong(1,documentId);statement.executeUpdate();}catch(Exception exception){throw new BusinessException("knowledge.vectorWriteFailed");}}
            case "QDRANT" -> {ObjectNode match=objectMapper.createObjectNode();match.putObject("filter").putArray("must").addObject().put("key","document_id").putObject("match").put("value",documentId);request(connection.config(),"POST","/collections/"+resource+"/points/delete?wait=true",match);}
            case "MILVUS" -> {ObjectNode body=objectMapper.createObjectNode().put("dbName",connection.config().path("database").asText("default")).put("collectionName",resource).put("filter","document_id == "+documentId);requireMilvusSuccess(request(connection.config(),"POST","/v2/vectordb/entities/delete",body));}
            case "ELASTICSEARCH" -> {ObjectNode body=objectMapper.createObjectNode();body.putObject("query").putObject("term").put("document_id",documentId);request(connection.config(),"POST","/"+resource+"/_delete_by_query?refresh=true",body);}
            default -> throw new BusinessException("knowledge.vectorStoreUnsupported");
        }
    }

    /** 检查 PostgreSQL vector 扩展及距离运算是否可用。 */
    private Capability probePg(JsonNode config) {
        try (Connection connection = pg(config); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            String version;
            try (ResultSet result = statement.executeQuery("SELECT extversion FROM pg_extension WHERE extname='vector'")) {
                if (!result.next()) return new Capability(false, "PGVECTOR", "", "vector extension is not enabled");
                version = result.getString(1);
            }
            try (ResultSet result = statement.executeQuery("SELECT 1 - ('[1,0]'::vector <=> '[1,0]'::vector)")) {
                if (!result.next()) throw new IllegalStateException("vector operator unavailable");
            }
            return new Capability(true, "PGVECTOR", version, "");
        } catch (Exception exception) { return unsupported("PGVECTOR", exception); }
    }

    /** 检查 Qdrant 服务身份和 Collection API。 */
    private Capability probeQdrant(JsonNode config) {
        try {
            JsonNode root = request(config, "GET", "", null);
            JsonNode collections=request(config, "GET", "/collections", null);
            if(!root.path("title").asText("").toLowerCase(Locale.ROOT).contains("qdrant")
                ||!collections.path("result").path("collections").isArray())throw new IllegalStateException("not Qdrant");
            return new Capability(true, "QDRANT", root.path("version").asText("unknown"), "");
        } catch (Exception exception) { return unsupported("QDRANT", exception); }
    }

    /** 检查 Milvus v2 REST Collection API。 */
    private Capability probeMilvus(JsonNode config) {
        try {
            JsonNode result = request(config, "POST", "/v2/vectordb/collections/list", objectMapper.createObjectNode()
                .put("dbName", config.path("database").asText("default")));
            requireMilvusSuccess(result);
            if(!result.path("data").path("collectionNames").isArray())throw new IllegalStateException("not Milvus");
            return new Capability(true, "MILVUS", result.path("data").path("version").asText("v2"), "");
        } catch (Exception exception) { return unsupported("MILVUS", exception); }
    }

    /** 检查 Elasticsearch 身份、版本和产品标识。 */
    private Capability probeElasticsearch(JsonNode config) {
        try {
            JsonNode root = request(config, "GET", "", null);
            String version = root.path("version").path("number").asText();
            if (version.isBlank() || root.path("tagline").asText("").isBlank()) throw new IllegalStateException("not Elasticsearch");
            return new Capability(true, "ELASTICSEARCH", version, "");
        } catch (Exception exception) { return unsupported("ELASTICSEARCH", exception); }
    }

    /** 创建或校验 pgvector 表。 */
    private void ensurePg(JsonNode config, String resource, boolean managed, int dimension, String distance) {
        try (Connection connection = pg(config); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            if (managed) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + resource + " (chunk_id BIGINT PRIMARY KEY,document_id BIGINT NOT NULL,embedding vector(" + dimension + "))");
                statement.execute("CREATE INDEX IF NOT EXISTS " + resource + "_embedding_hnsw ON " + resource
                    + " USING hnsw (embedding " + pgOperatorClass(distance) + ")");
            }
            try (ResultSet result = statement.executeQuery("SELECT atttypmod-4 FROM pg_attribute WHERE attrelid='" + resource
                + "'::regclass AND attname='embedding' AND NOT attisdropped")) {
                if (!result.next() || result.getInt(1) != dimension) throw new BusinessException("knowledge.vectorDimensionMismatch");
            }
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("knowledge.vectorResourceInvalid"); }
    }

    /** 创建或校验 Qdrant Collection。 */
    private void ensureQdrant(JsonNode config, String resource, boolean managed, int dimension, String distance) {
        if (managed) {
            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("vectors").put("size", dimension).put("distance", qdrantDistance(distance));
            request(config, "PUT", "/collections/" + resource, body);
        }
        JsonNode result = request(config, "GET", "/collections/" + resource, null).path("result").path("config").path("params").path("vectors");
        if (result.path("size").asInt(-1) != dimension) throw new BusinessException("knowledge.vectorDimensionMismatch");
        if (!qdrantDistance(distance).equalsIgnoreCase(result.path("distance").asText())) throw new BusinessException("knowledge.vectorMetricMismatch");
    }

    /** 创建或校验 Milvus Collection。 */
    private void ensureMilvus(JsonNode config, String resource, boolean managed, int dimension, String distance) {
        if (managed) {
            ObjectNode body = objectMapper.createObjectNode().put("dbName", config.path("database").asText("default"))
                .put("collectionName", resource);
            ObjectNode schema = body.putObject("schema").put("autoId", false).put("enabledDynamicField", false);
            ArrayNode fields = schema.putArray("fields");
            fields.addObject().put("fieldName", "chunk_id").put("dataType", "Int64").put("isPrimary", true);
            fields.addObject().put("fieldName", "document_id").put("dataType", "Int64");
            fields.addObject().put("fieldName", "embedding").put("dataType", "FloatVector")
                .putObject("elementTypeParams").put("dim", String.valueOf(dimension));
            body.putArray("indexParams").addObject().put("fieldName", "embedding").put("indexName", "embedding")
                .put("indexType", "AUTOINDEX").put("metricType", metric(distance));
            requireMilvusSuccess(request(config, "POST", "/v2/vectordb/collections/create", body));
        }
        ObjectNode describe = objectMapper.createObjectNode().put("dbName", config.path("database").asText("default"))
            .put("collectionName", resource);
        JsonNode result = request(config, "POST", "/v2/vectordb/collections/describe", describe);
        requireMilvusSuccess(result);
        boolean valid = false;
        for (JsonNode field : result.path("data").path("fields")) {
            if ("embedding".equals(field.path("name").asText(field.path("fieldName").asText()))
                && dimension == milvusDimension(field)) valid = true;
        }
        if (!valid) throw new BusinessException("knowledge.vectorDimensionMismatch");
        boolean metricValid=false;for(JsonNode index:result.path("data").path("indexes")){if("embedding".equals(index.path("fieldName").asText())&&metric(distance).equalsIgnoreCase(index.path("metricType").asText()))metricValid=true;}
        if(!metricValid)throw new BusinessException("knowledge.vectorMetricMismatch");
    }

    /** 创建或校验 Elasticsearch dense_vector 索引。 */
    private void ensureElasticsearch(JsonNode config, String resource, boolean managed, int dimension, String distance) {
        if (managed) {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode properties = body.putObject("mappings").putObject("properties");
            properties.putObject("chunk_id").put("type", "long"); properties.putObject("document_id").put("type", "long");
            properties.putObject("embedding").put("type", "dense_vector").put("dims", dimension).put("index", true)
                .put("similarity", elasticSimilarity(distance));
            request(config, "PUT", "/" + resource, body);
        }
        JsonNode mapping = request(config, "GET", "/" + resource + "/_mapping", null).path(resource)
            .path("mappings").path("properties").path("embedding");
        if (!"dense_vector".equals(mapping.path("type").asText()) || mapping.path("dims").asInt(-1) != dimension) {
            throw new BusinessException("knowledge.vectorDimensionMismatch");
        }
        if(!elasticSimilarity(distance).equalsIgnoreCase(mapping.path("similarity").asText("cosine")))throw new BusinessException("knowledge.vectorMetricMismatch");
    }

    /** 批量写入 pgvector。 */
    private void upsertPg(JsonNode config, String resource, List<VectorItem> items) {
        validateResource(resource);
        try (Connection connection = pg(config); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + resource + "(chunk_id,document_id,embedding) VALUES (?,?,?::vector) ON CONFLICT(chunk_id) DO UPDATE SET document_id=EXCLUDED.document_id,embedding=EXCLUDED.embedding")) {
            for (VectorItem item : items) { statement.setLong(1,item.chunkId()); statement.setLong(2,item.documentId()); statement.setString(3,vector(item.vector())); statement.addBatch(); }
            statement.executeBatch();
        } catch (Exception exception) { throw new BusinessException("knowledge.vectorWriteFailed"); }
    }

    /** 批量写入 Qdrant。 */
    private void upsertQdrant(JsonNode config, String resource, List<VectorItem> items) {
        ArrayNode points = objectMapper.createArrayNode();
        for (VectorItem item : items) {
            ObjectNode point=points.addObject().put("id",item.chunkId());point.set("vector",objectMapper.valueToTree(item.vector()));
            point.putObject("payload").put("chunk_id",item.chunkId()).put("document_id",item.documentId());
        }
        request(config,"PUT","/collections/"+resource+"/points?wait=true",objectMapper.createObjectNode().set("points",points));
    }

    /** 批量写入 Milvus。 */
    private void upsertMilvus(JsonNode config, String resource, List<VectorItem> items) {
        ObjectNode body=objectMapper.createObjectNode().put("dbName",config.path("database").asText("default")).put("collectionName",resource);
        ArrayNode data=body.putArray("data");
        for(VectorItem item:items)data.addObject().put("chunk_id",item.chunkId()).put("document_id",item.documentId()).set("embedding",objectMapper.valueToTree(item.vector()));
        requireMilvusSuccess(request(config,"POST","/v2/vectordb/entities/upsert",body));
    }

    /** 批量写入 Elasticsearch。 */
    private void upsertElasticsearch(JsonNode config, String resource, List<VectorItem> items) {
        for(VectorItem item:items){ObjectNode body=objectMapper.createObjectNode().put("chunk_id",item.chunkId()).put("document_id",item.documentId());body.set("embedding",objectMapper.valueToTree(item.vector()));request(config,"PUT","/"+resource+"/_doc/"+item.chunkId()+"?refresh=false",body);}
        request(config,"POST","/"+resource+"/_refresh",objectMapper.createObjectNode());
    }

    /** 查询 pgvector，并按所选距离算法生成越大越相似的统一分值。 */
    private List<Match> searchPg(JsonNode config,String resource,List<Double> vector,int topK,double threshold,String distance){
        validateResource(resource);List<Match> matches=new ArrayList<>();String score=pgScoreExpression(distance);String sql="SELECT chunk_id,document_id,score FROM (SELECT chunk_id,document_id,"+score+" score FROM "+resource+") ranked WHERE score>=? ORDER BY score DESC LIMIT ?";
        try(Connection connection=pg(config);PreparedStatement statement=connection.prepareStatement(sql)){statement.setString(1,vector(vector));statement.setDouble(2,threshold);statement.setInt(3,topK);try(ResultSet rs=statement.executeQuery()){while(rs.next())matches.add(new Match(rs.getLong(1),rs.getLong(2),rs.getDouble(3)));}return matches;}
        catch(Exception exception){throw new BusinessException("knowledge.vectorSearchFailed");}
    }

    /** 查询 Qdrant。 */
    private List<Match> searchQdrant(JsonNode config,String resource,List<Double> vector,int topK,double threshold,String distance){ObjectNode body=objectMapper.createObjectNode().put("limit",topK).put("score_threshold",threshold).put("with_payload",true);body.set("query",objectMapper.valueToTree(vector));JsonNode result=request(config,"POST","/collections/"+resource+"/points/query",body).path("result").path("points");return matches(result,"score","payload");}
    /** 查询 Milvus。 */
    private List<Match> searchMilvus(JsonNode config,String resource,List<Double> vector,int topK,double threshold,String distance){ObjectNode body=objectMapper.createObjectNode().put("dbName",config.path("database").asText("default")).put("collectionName",resource).put("annsField","embedding").put("limit",topK);body.putObject("searchParams").put("metricType",metric(distance));body.putArray("outputFields").add("chunk_id").add("document_id");body.putArray("data").add(objectMapper.valueToTree(vector));JsonNode response=request(config,"POST","/v2/vectordb/entities/search",body);requireMilvusSuccess(response);List<Match> result=new ArrayList<>();for(Match match:matches(response.path("data"),"distance","")){double score=milvusScore(match.score(),distance);if(score>=threshold)result.add(new Match(match.chunkId(),match.documentId(),score));}return result;}
    /** 查询 Elasticsearch。 */
    private List<Match> searchElasticsearch(JsonNode config,String resource,List<Double> vector,int topK,double threshold){ObjectNode body=objectMapper.createObjectNode().put("size",topK).put("min_score",threshold);ObjectNode knn=body.putObject("knn").put("field","embedding").put("k",topK).put("num_candidates",Math.max(100,topK*10));knn.set("query_vector",objectMapper.valueToTree(vector));JsonNode hits=request(config,"POST","/"+resource+"/_search",body).path("hits").path("hits");List<Match> result=new ArrayList<>();for(JsonNode hit:hits){JsonNode source=hit.path("_source");result.add(new Match(source.path("chunk_id").asLong(),source.path("document_id").asLong(),hit.path("_score").asDouble()));}return result;}

    /** 从 Qdrant 或 Milvus 结果读取统一匹配结构。 */
    private List<Match> matches(JsonNode values,String scoreField,String payloadField){List<Match> result=new ArrayList<>();for(JsonNode value:values){JsonNode payload=payloadField.isBlank()?value:value.path(payloadField);result.add(new Match(payload.path("chunk_id").asLong(value.path("id").asLong()),payload.path("document_id").asLong(),value.path(scoreField).asDouble()));}return result;}

    /** 创建带凭据和超时的 REST 请求并拒绝非成功响应。 */
    private JsonNode request(JsonNode config,String method,String path,JsonNode body){
        try{String base=config.path("url").asText();URI uri=URI.create(base.replaceAll("/+$","")+path);HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).header("Accept","application/json");
            String apiKey=config.path("apiKey").asText("");String token=config.path("token").asText("");String username=config.path("username").asText("");
            if(!apiKey.isBlank())builder.header("ELASTICSEARCH".equalsIgnoreCase(config.path("product").asText())?"Authorization":"api-key",("ELASTICSEARCH".equalsIgnoreCase(config.path("product").asText())?"ApiKey ":"")+apiKey);
            else if(!token.isBlank())builder.header("Authorization","Bearer "+token);
            else if(!username.isBlank())builder.header("Authorization","Basic "+Base64.getEncoder().encodeToString((username+":"+config.path("password").asText()).getBytes(StandardCharsets.UTF_8)));
            String json=body==null?"":objectMapper.writeValueAsString(body);builder.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<InputStream> response=httpClient.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());try(InputStream stream=response.body()){if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("HTTP "+response.statusCode());byte[] bytes=stream.readNBytes(MAX_RESPONSE_BYTES+1);if(bytes.length>MAX_RESPONSE_BYTES)throw new IllegalStateException("response too large");String value=new String(bytes,StandardCharsets.UTF_8);return value.isBlank()?objectMapper.createObjectNode():objectMapper.readTree(value);}}
        catch(BusinessException exception){throw exception;}catch(Exception exception){throw new BusinessException("knowledge.vectorStoreUnavailable");}
    }

    /** 创建 PostgreSQL 连接。 */
    private Connection pg(JsonNode config)throws Exception{Properties properties=new Properties();properties.setProperty("user",config.path("username").asText());properties.setProperty("password",config.path("password").asText());return DriverManager.getConnection(config.path("url").asText(),properties);}
    /** 执行受控 PostgreSQL DDL。 */
    private void executePg(JsonNode config,String sql){try(Connection connection=pg(config);Statement statement=connection.createStatement()){statement.execute(sql);}catch(Exception exception){throw new BusinessException("knowledge.vectorResourceInvalid");}}
    /** 限制资源名只能由平台或管理员提供安全标识符。 */
    private void validateResource(String resource){if(resource==null||!resource.matches("[a-z][a-z0-9_]{2,119}"))throw new BusinessException("knowledge.resourceNameInvalid");}
    /** 限制通用稠密向量维度，实际产品更低上限由资源校验返回。 */
    private void validateDimension(int dimension){if(dimension<1||dimension>65535)throw new BusinessException("knowledge.vectorDimensionInvalid");}
    /** 将向量序列化为 pgvector 文本格式。 */
    private String vector(List<Double> values){return objectMapper.valueToTree(values).toString();}
    /** 规范距离算法。 */
    private String metric(String value){String normalized=value==null?"COSINE":value.toUpperCase(Locale.ROOT);if(!List.of("COSINE","L2","IP").contains(normalized))throw new BusinessException("knowledge.distanceInvalid");return normalized;}
    private String qdrantDistance(String value){return switch(metric(value)){case "L2"->"Euclid";case "IP"->"Dot";default->"Cosine";};}
    private String elasticSimilarity(String value){return switch(metric(value)){case "L2"->"l2_norm";case "IP"->"max_inner_product";default->"cosine";};}
    /** 返回 pgvector HNSW 操作符类。 */
    private String pgOperatorClass(String value){return switch(metric(value)){case "L2"->"vector_l2_ops";case "IP"->"vector_ip_ops";default->"vector_cosine_ops";};}
    /** 返回 pgvector 归一化分值表达式，参数占位符由调用方绑定查询向量。 */
    private String pgScoreExpression(String value){return switch(metric(value)){case "L2"->"1/(1+power(embedding <-> ?::vector,2))";case "IP"->"1/(1+exp(embedding <#> ?::vector))";default->"(2-(embedding <=> ?::vector))/2";};}
    /** Milvus L2 返回距离，其余返回相似度；统一转为较大值表示更相似。 */
    private double milvusScore(double value,String distance){return switch(metric(distance)){case "L2"->1D/(1D+value*value);case "IP"->1D/(1D+Math.exp(-value));default->(value+1D)/2D;};}
    /** 兼容 Milvus describe 中数组和对象两种维度参数结构。 */
    private int milvusDimension(JsonNode field){JsonNode params=field.path("params");if(params.isArray())for(JsonNode item:params)if("dim".equals(item.path("key").asText()))return item.path("value").asInt(-1);return params.path("dim").asInt(field.path("elementTypeParams").path("dim").asInt(-1));}
    /** 将外部异常折叠为不泄漏地址或凭据的能力结果。 */
    private Capability unsupported(String engine,Exception exception){return new Capability(false,engine,"",exception instanceof BusinessException?"capability request failed":exception.getClass().getSimpleName());}
    /** 校验 Milvus 标准成功码。 */
    private void requireMilvusSuccess(JsonNode result){if(result.has("code")&&result.path("code").asInt()!=0)throw new BusinessException("knowledge.vectorStoreUnavailable");}

    public record Capability(boolean supported,String engine,String version,String reason){}
    public record VectorItem(long chunkId,long documentId,List<Double> vector){}
    public record Match(long chunkId,long documentId,double score){}
}
