package com.baseai.platform.knowledge;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorStoreServiceTest {
    private final ObjectMapper objectMapper=new ObjectMapper();private final Map<String,String> responses=new ConcurrentHashMap<>();private HttpServer server;private String url;
    /** 启动本地官方协议替身，隔离真实外部服务。 */
    @BeforeEach void setUp()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();String body=responses.getOrDefault(path,switch(path){case "/collections"->"{\"result\":{\"collections\":[]}}";case "/v2/vectordb/collections/list"->"{\"code\":0,\"data\":{\"collectionNames\":[]}}";default->"{\"title\":\"qdrant - vector search engine\",\"tagline\":\"You Know, for Search\",\"version\":{\"number\":\"8.17.0\"}}";});byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();url="http://127.0.0.1:"+server.getAddress().getPort();}
    /** 停止临时 HTTP 服务。 */
    @AfterEach void tearDown(){server.stop(0);}

    /** Qdrant、Milvus 和 Elasticsearch 必须通过各自只读 API 确认能力。 */
    @Test void probesHttpVectorStores()throws Exception{VectorStoreService service=new VectorStoreService(objectMapper);
        VectorStoreService.Capability qdrant=service.probe(connection("QDRANT",Map.of("url",url,"apiKey","secret")));assertTrue(qdrant.supported());assertEquals("QDRANT",qdrant.engine());
        VectorStoreService.Capability milvus=service.probe(connection("MILVUS",Map.of("url",url,"token","root:Milvus","database","default")));assertTrue(milvus.supported());assertEquals("MILVUS",milvus.engine());
        VectorStoreService.Capability elastic=service.probe(connection("ELASTICSEARCH",Map.of("url",url,"product","ELASTICSEARCH")));assertTrue(elastic.supported());assertEquals("8.17.0",elastic.version());
    }

    /** 不可达目标返回受控不支持状态而不是泄漏底层连接异常。 */
    @Test void reportsUnavailableStoreAsUnsupported()throws Exception{VectorStoreService.Capability result=new VectorStoreService(objectMapper).probe(connection("QDRANT",Map.of("url","http://127.0.0.1:1")));assertFalse(result.supported());assertEquals("QDRANT",result.engine());assertFalse(result.reason().contains("127.0.0.1"));}

    /** 绑定预建资源时必须同时校验维度和距离算法。 */
    @Test void validatesExistingResourceDimensionAndMetric(){responses.put("/collections/existing","{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}");responses.put("/existing/_mapping","{\"existing\":{\"mappings\":{\"properties\":{\"embedding\":{\"type\":\"dense_vector\",\"dims\":3,\"similarity\":\"cosine\"}}}}}");responses.put("/v2/vectordb/collections/describe","{\"code\":0,\"data\":{\"fields\":[{\"name\":\"embedding\",\"params\":[{\"key\":\"dim\",\"value\":\"3\"}]}],\"indexes\":[{\"fieldName\":\"embedding\",\"indexName\":\"embedding\",\"metricType\":\"COSINE\"}]}}");VectorStoreService service=new VectorStoreService(objectMapper);service.ensureResource(connection("QDRANT",Map.of("url",url)),"existing","EXISTING",3,"COSINE");service.ensureResource(connection("MILVUS",Map.of("url",url)),"existing","EXISTING",3,"COSINE");service.ensureResource(connection("ELASTICSEARCH",Map.of("url",url,"product","ELASTICSEARCH")),"existing","EXISTING",3,"COSINE");BusinessException mismatch=assertThrows(BusinessException.class,()->service.ensureResource(connection("QDRANT",Map.of("url",url)),"existing","EXISTING",3,"L2"));assertEquals("knowledge.vectorMetricMismatch",mismatch.getMessageKey());}

    /** 构造不包含真实凭据的连接记录。 */
    private WorkflowConnectionService.StoredConnection connection(String type,Map<String,String> values){JsonNode config=objectMapper.valueToTree(values);return new WorkflowConnectionService.StoredConnection(1L,"TEST","Test",type,config,7L,true,null,null);}
}
