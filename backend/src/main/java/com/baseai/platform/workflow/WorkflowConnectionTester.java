package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerModels;
import com.baseai.platform.automation.ApiTriggerService;
import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import io.lettuce.core.RedisClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 对当前用户拥有的连接执行最小无副作用可用性检查。 */
@Service
public class WorkflowConnectionTester {
    private final WorkflowConnectionService connectionService;
    private final ApiTriggerService apiTriggerService;
    private final ObjectMapper objectMapper;
    private final com.baseai.platform.knowledge.VectorStoreService vectorStoreService;
    private final WorkflowRedisClientFactory redisClients;

    /** 注入连接存储和安全 HTTP 服务。 */
    public WorkflowConnectionTester(WorkflowConnectionService connectionService, ApiTriggerService apiTriggerService,
                                    ObjectMapper objectMapper,
                                    com.baseai.platform.knowledge.VectorStoreService vectorStoreService,
                                    WorkflowRedisClientFactory redisClients) {
        this.connectionService = connectionService;
        this.apiTriggerService = apiTriggerService;
        this.objectMapper = objectMapper;
        this.vectorStoreService = vectorStoreService;
        this.redisClients = redisClients;
    }

    /** 按连接类型执行测试，采集轻量只读指标并留存最近一次检测结果。 */
    public Map<String, Object> test(Long id) {
        WorkflowConnectionService.StoredConnection connection = connectionService.ownedForTest(id);
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            switch (connection.connectionType()) {
                case "MYSQL", "POSTGRESQL" -> info.putAll(probeJdbc(connection.config()));
                case "REDIS" -> info.putAll(probeRedis(connection.config()));
                case "S3" -> testS3(connection.config());
                case "OSS" -> testOss(connection.config());
                case "KAFKA" -> testKafka(connection.config());
                case "RABBITMQ" -> testRabbit(connection.config());
                case "WEBHOOK" -> testWebhook(connection.config());
                case "TAVILY" -> testTavily(connection.config());
                case "PLUGIN" -> testPlugin(connection.config());
                case "QDRANT", "MILVUS", "ELASTICSEARCH" -> { /* 向量探测同时验证连通性。 */ }
                default -> throw new BusinessException("workflow.connectionTypeInvalid");
            }
            int latency = (int) (System.currentTimeMillis() - start);
            boolean connected = true;
            if (Set.of("POSTGRESQL", "QDRANT", "MILVUS", "ELASTICSEARCH").contains(connection.connectionType())) {
                com.baseai.platform.knowledge.VectorStoreService.Capability capability = vectorStoreService.probe(connection);
                String status = capability.supported() ? "SUPPORTED" : "UNSUPPORTED";
                connectionService.recordVectorCapability(id, status, capability.engine(), capability.version(), capability.reason());
                connected = capability.supported();
                info.put("vectorEngine", capability.engine());
                info.put("vectorVersion", capability.version());
                if (!connected) info.put("reason", capability.reason());
            }
            connectionService.recordTestResult(id, connected, latency, jsonInfo(info));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", connected);
            result.put("connectionType", connection.connectionType());
            result.put("vectorSupported", connected && Set.of("POSTGRESQL", "QDRANT", "MILVUS", "ELASTICSEARCH")
                .contains(connection.connectionType()));
            result.put("latencyMs", latency);
            result.put("info", info);
            return result;
        } catch (BusinessException exception) {
            recordFailure(id, start); throw exception;
        } catch (Exception exception) {
            recordFailure(id, start); throw new BusinessException("workflow.connectionTestFailed");
        }
    }

    /** 失败同样留存最近一次检测结果和耗时，便于页面展示离线状态。 */
    private void recordFailure(Long id, long start) {
        try { connectionService.recordTestResult(id, false, (int) (System.currentTimeMillis() - start), "{}"); }
        catch (Exception ignored) { /* 留存失败不能掩盖原始业务异常。 */ }
    }

    /** 序列化留存指标，失败时退化为空对象。 */
    private String jsonInfo(Map<String, Object> info) {
        try { return objectMapper.writeValueAsString(info); } catch (Exception exception) { return "{}"; }
    }

    /** 确认插件连接具备固定组件身份和结构化凭据；实际调用仍在节点执行时校验。 */
    private void testPlugin(JsonNode config) {
        if (config.path("pluginComponentId").asLong() <= 0 || !config.path("credentials").isObject()) {
            throw new BusinessException("workflow.connectionInvalid");
        }
    }

    /** 使用只读查询验证 JDBC 连接并采集版本与活动连接数指标。 */
    private Map<String, Object> probeJdbc(JsonNode config) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", config.path("username").asText());
        properties.setProperty("password", config.path("password").asText());
        Map<String, Object> info = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(config.path("url").asText(), properties);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet ignored = statement.executeQuery("SELECT 1")) { /* 连接与查询均成功即通过。 */ }
            try (ResultSet rs = statement.executeQuery("SELECT version()")) {
                if (rs.next()) info.put("version", rs.getString(1));
            } catch (Exception ignored) { /* 版本指标采集失败不影响连通性结论。 */ }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.processlist")) {
                if (rs.next()) info.put("activeConnections", rs.getInt(1));
            } catch (Exception ignored) { /* PostgreSQL 无 processlist，改用 pg_stat_activity。 */ }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM pg_stat_activity")) {
                if (rs.next()) info.putIfAbsent("activeConnections", rs.getInt(1));
            } catch (Exception ignored) { /* MySQL 已在上方采集，忽略。 */ }
        }
        return info;
    }

    /** 使用 PING 验证独立 Redis 并采集版本与内存指标。 */
    private Map<String, Object> probeRedis(JsonNode config) {
        Map<String, Object> info = new LinkedHashMap<>();
        try (RedisClient client = redisClients.create(io.lettuce.core.RedisURI.create(config.path("uri").asText()));
             io.lettuce.core.api.StatefulRedisConnection<String, String> connection = client.connect()) {
            if (!"PONG".equalsIgnoreCase(connection.sync().ping())) throw new BusinessException("workflow.connectionTestFailed");
            try {
                Map<String, String> sections = new LinkedHashMap<>();
                for (String line : connection.sync().info().split("\\r?\\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) sections.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
                if (sections.get("redis_version") != null) info.put("version", sections.get("redis_version"));
                if (sections.get("used_memory_human") != null) info.put("usedMemory", sections.get("used_memory_human"));
                if (sections.get("connected_clients") != null) info.put("connectedClients", Long.valueOf(sections.get("connected_clients")));
            } catch (Exception ignored) { /* 指标采集失败不影响连通性结论。 */ }
        }
        return info;
    }

    /** 使用 HeadBucket 验证 S3 凭据和限定 Bucket。 */
    private void testS3(JsonNode config) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(config.path("region").asText("us-east-1")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                config.path("accessKey").asText(), config.path("secretKey").asText())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(config.path("pathStyle").asBoolean(true)).build());
        if (config.hasNonNull("endpoint")) builder.endpointOverride(URI.create(config.path("endpoint").asText()));
        try (S3Client client = builder.build()) { client.headBucket(request -> request.bucket(config.path("bucket").asText())); }
    }

    /** 使用 ListBuckets 验证 OSS 凭据，并确认限定 Bucket 存在。 */
    private void testOss(JsonNode config) {
        String endpoint = config.path("endpoint").asText();
        OSS client = new OSSClientBuilder().build(endpoint, config.path("accessKey").asText(), config.path("secretKey").asText());
        try {
            String bucket = config.path("bucket").asText();
            if (!client.doesBucketExist(bucket)) throw new BusinessException("workflow.connectionTestFailed");
        } finally { client.shutdown(); }
    }

    /** 读取 Topic 名称验证 Kafka 认证。 */
    private void testKafka(JsonNode config) throws Exception {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.path("bootstrapServers").asText());
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        WorkflowConnectorNodeExecutor.addKafkaSecurity(properties, config);
        try (AdminClient client = AdminClient.create(properties)) { client.listTopics().names().get(10, TimeUnit.SECONDS); }
    }

    /** 建立并立即关闭 RabbitMQ 连接。 */
    private void testRabbit(JsonNode config) throws Exception {
        ConnectionFactory factory = new ConnectionFactory(); factory.setUri(config.path("uri").asText()); factory.setConnectionTimeout(10_000);
        try (com.rabbitmq.client.Connection ignored = factory.newConnection()) { }
    }

    /** 通过现有安全策略验证 Webhook 可访问性。 */
    private void testWebhook(JsonNode config) {
        ApiTriggerModels.Command command = new ApiTriggerModels.Command(
            "Workflow connection test", "Workflow connection test", config.path("testMethod").asText("GET"),
            config.path("url").asText(), config.path("headers").isMissingNode() ? "{}" : config.path("headers").toString(),
            "{}", "", "application/json", null, 10, true, false, "", "POST", "", "application/json",
            "data.token", "Authorization", "Bearer ");
        apiTriggerService.test(command);
    }

    /** 使用官方只读 Usage 接口验证 Tavily Bearer 凭据。 */
    private void testTavily(JsonNode config) {
        String headers;
        try { headers = objectMapper.writeValueAsString(Map.of("Authorization", "Bearer " + config.path("apiKey").asText())); }
        catch (Exception exception) { throw new BusinessException("workflow.connectionInvalid"); }
        ApiTriggerModels.Command command = new ApiTriggerModels.Command(
            "Tavily connection test", "Tavily connection test", "GET", "https://api.tavily.com/usage",
            headers, "{}", "", "application/json", null, 10, true, false, "", "POST", "", "application/json",
            "data.token", "Authorization", "Bearer ");
        ApiTriggerModels.ExecutionResult result = apiTriggerService.test(command);
        if (result.httpStatus() < 200 || result.httpStatus() >= 300) {
            throw new BusinessException("workflow.connectionTestFailed");
        }
    }
}
