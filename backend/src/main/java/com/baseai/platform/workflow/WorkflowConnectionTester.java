package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerModels;
import com.baseai.platform.automation.ApiTriggerService;
import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.ConnectionFactory;
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
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 对当前用户拥有的连接执行最小无副作用可用性检查。 */
@Service
public class WorkflowConnectionTester {
    private final WorkflowConnectionService connectionService;
    private final ApiTriggerService apiTriggerService;

    /** 注入连接存储和安全 HTTP 服务。 */
    public WorkflowConnectionTester(WorkflowConnectionService connectionService, ApiTriggerService apiTriggerService) {
        this.connectionService = connectionService;
        this.apiTriggerService = apiTriggerService;
    }

    /** 按连接类型执行测试并隐藏底层异常细节。 */
    public Map<String, Object> test(Long id) {
        WorkflowConnectionService.StoredConnection connection = connectionService.ownedForTest(id);
        try {
            switch (connection.connectionType()) {
                case "MYSQL", "POSTGRESQL" -> testJdbc(connection.config());
                case "REDIS" -> testRedis(connection.config());
                case "S3" -> testS3(connection.config());
                case "KAFKA" -> testKafka(connection.config());
                case "RABBITMQ" -> testRabbit(connection.config());
                case "WEBHOOK" -> testWebhook(connection.config());
                default -> throw new BusinessException("workflow.connectionTypeInvalid");
            }
            return Map.of("connected", true, "connectionType", connection.connectionType());
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.connectionTestFailed"); }
    }

    /** 使用只读查询验证 JDBC 连接。 */
    private void testJdbc(JsonNode config) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", config.path("username").asText());
        properties.setProperty("password", config.path("password").asText());
        try (Connection connection = DriverManager.getConnection(config.path("url").asText(), properties);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet ignored = statement.executeQuery("SELECT 1")) { /* 连接与查询均成功即通过。 */ }
        }
    }

    /** 使用 PING 验证独立 Redis。 */
    private void testRedis(JsonNode config) {
        try (RedisClient client = RedisClient.create(config.path("uri").asText());
             io.lettuce.core.api.StatefulRedisConnection<String, String> connection = client.connect()) {
            if (!"PONG".equalsIgnoreCase(connection.sync().ping())) throw new BusinessException("workflow.connectionTestFailed");
        }
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
}
