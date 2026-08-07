package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerModels;
import com.baseai.platform.automation.ApiTriggerService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.MailDeliveryClient;
import com.baseai.platform.service.MailManagementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 执行受管邮件、通知、数据库、缓存、对象存储和消息发布节点。 */
@Component
public class WorkflowConnectorNodeExecutor implements WorkflowNodeExecutor {
    private static final Set<String> TYPES = Set.of(
        "EMAIL_SEND", "IM_NOTIFY", "SQL_QUERY", "REDIS_COMMAND", "S3_OBJECT", "KAFKA_PUBLISH", "RABBITMQ_PUBLISH"
    );
    private final ObjectMapper objectMapper;
    private final WorkflowExpressionService expressions;
    private final WorkflowConnectionService connections;
    private final MailManagementService mailManagementService;
    private final MailDeliveryClient mailDeliveryClient;
    private final ApiTriggerService apiTriggerService;

    /** 注入连接、邮件及安全 HTTP 调用能力。 */
    public WorkflowConnectorNodeExecutor(ObjectMapper objectMapper, WorkflowExpressionService expressions,
                                         WorkflowConnectionService connections, MailManagementService mailManagementService,
                                         MailDeliveryClient mailDeliveryClient, ApiTriggerService apiTriggerService) {
        this.objectMapper = objectMapper;
        this.expressions = expressions;
        this.connections = connections;
        this.mailManagementService = mailManagementService;
        this.mailDeliveryClient = mailDeliveryClient;
        this.apiTriggerService = apiTriggerService;
    }

    /** 返回连接执行器支持的节点集合。 */
    @Override
    public Set<String> types() { return TYPES; }

    /** 解析非敏感节点参数后调用对应受管连接。 */
    @Override
    public Result execute(Request request) {
        JsonNode config = expressions.resolve(request.config(), request.context());
        return Result.output(switch (request.type()) {
            case "EMAIL_SEND" -> email(config);
            case "IM_NOTIFY" -> notify(config);
            case "SQL_QUERY" -> sql(config);
            case "REDIS_COMMAND" -> redis(config);
            case "S3_OBJECT" -> s3(config);
            case "KAFKA_PUBLISH" -> kafka(config);
            case "RABBITMQ_PUBLISH" -> rabbit(config);
            default -> throw new BusinessException("workflow.nodeTypeInvalid");
        });
    }

    /** 使用现有邮件路由发送工作流邮件。 */
    private JsonNode email(JsonNode config) {
        Long routeId = requiredConnection(config, "routeId");
        MailManagementService.ResolvedRoute route = mailManagementService.resolveRoute(routeId);
        Map<String, Object> result = mailDeliveryClient.send(route, singleLine(config.path("subject").asText("")),
            config.path("body").asText(""));
        if (!Boolean.TRUE.equals(result.get("sent"))) throw new BusinessException(502, "mail.sendFailed");
        return objectMapper.createObjectNode().put("sent", true).put("routeCode", route.businessCode());
    }

    /** 复用平台 SSRF/TLS 防护向受管通知 Webhook 发送消息。 */
    private JsonNode notify(JsonNode config) {
        WorkflowConnectionService.StoredConnection connection = connections.resolved(requiredConnection(config, "connectionId"), Set.of("WEBHOOK"));
        JsonNode secret = connection.config();
        String body = config.path("body").isTextual() ? config.path("body").asText() : config.path("body").toString();
        ApiTriggerModels.Command command = new ApiTriggerModels.Command(
            "Workflow notification", "Workflow notification node", secret.path("method").asText("POST"), secret.path("url").asText(),
            secret.path("headers").isMissingNode() ? "{}" : secret.path("headers").toString(), "{}", body,
            config.path("contentType").asText("application/json"), null, config.path("timeoutSeconds").asInt(15),
            true, false, "", "POST", "", "application/json", "data.token", "Authorization", "Bearer ");
        ApiTriggerModels.ExecutionResult result = apiTriggerService.test(command);
        return objectMapper.createObjectNode().put("httpStatus", result.httpStatus()).put("durationMs", result.durationMs())
            .put("body", result.responseBody());
    }

    /** 使用参数化语句访问受管 MySQL 或 PostgreSQL。 */
    private JsonNode sql(JsonNode config) {
        WorkflowConnectionService.StoredConnection connection = connections.resolved(requiredConnection(config, "connectionId"),
            Set.of("MYSQL", "POSTGRESQL"));
        JsonNode secret = connection.config();
        String query = config.path("query").asText("").trim();
        if (query.isBlank() || query.contains(";") || query.contains("--") || query.contains("/*")) {
            throw new BusinessException("workflow.sqlUnsafe");
        }
        boolean select = query.regionMatches(true, 0, "SELECT", 0, 6) || query.regionMatches(true, 0, "WITH", 0, 4);
        if (!select && !secret.path("allowWrite").asBoolean(false)) throw new BusinessException("workflow.sqlWriteForbidden");
        Properties properties = new Properties();
        properties.setProperty("user", secret.path("username").asText());
        properties.setProperty("password", secret.path("password").asText());
        try (Connection jdbc = DriverManager.getConnection(secret.path("url").asText(), properties)) {
            jdbc.setReadOnly(!secret.path("allowWrite").asBoolean(false));
            try (PreparedStatement statement = jdbc.prepareStatement(query)) {
                statement.setQueryTimeout(Math.max(1, Math.min(config.path("timeoutSeconds").asInt(30), 120)));
                int index = 1;
                for (JsonNode parameter : config.path("parameters")) setParameter(statement, index++, parameter);
                if (!select) return objectMapper.createObjectNode().put("updated", statement.executeUpdate());
                try (ResultSet resultSet = statement.executeQuery()) { return rows(resultSet, config.path("maxRows").asInt(1000)); }
            }
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.connectionExecutionFailed"); }
    }

    /** 将 JSON 参数安全绑定到 JDBC 预编译语句。 */
    private void setParameter(PreparedStatement statement, int index, JsonNode value) throws Exception {
        if (value == null || value.isNull()) statement.setNull(index, Types.NULL);
        else if (value.isIntegralNumber()) statement.setLong(index, value.asLong());
        else if (value.isFloatingPointNumber()) statement.setBigDecimal(index, value.decimalValue());
        else if (value.isBoolean()) statement.setBoolean(index, value.asBoolean());
        else statement.setString(index, value.isTextual() ? value.asText() : value.toString());
    }

    /** 将 JDBC 结果转换为有限对象数组。 */
    private JsonNode rows(ResultSet resultSet, int requestedLimit) throws Exception {
        int limit = Math.max(1, Math.min(requestedLimit, 10_000));
        ResultSetMetaData metadata = resultSet.getMetaData();
        ArrayNode rows = objectMapper.createArrayNode();
        while (resultSet.next() && rows.size() < limit) {
            ObjectNode row = rows.addObject();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                Object value = resultSet.getObject(index);
                row.set(metadata.getColumnLabel(index), objectMapper.valueToTree(value));
            }
        }
        return objectMapper.createObjectNode().put("count", rows.size()).set("rows", rows);
    }

    /** 执行显式允许的 Redis 命令并限制写权限。 */
    private JsonNode redis(JsonNode config) {
        WorkflowConnectionService.StoredConnection connection = connections.resolved(requiredConnection(config, "connectionId"), Set.of("REDIS"));
        JsonNode secret = connection.config();
        String command = config.path("command").asText("GET").toUpperCase(Locale.ROOT);
        Set<String> writes = Set.of("SET", "DEL", "HSET", "LPUSH", "RPUSH", "PUBLISH");
        if (writes.contains(command) && !secret.path("allowWrite").asBoolean(false)) throw new BusinessException("workflow.redisWriteForbidden");
        if (!config.path("arguments").isArray()) throw new BusinessException("workflow.dataInputInvalid");
        ArrayNode args = (ArrayNode) config.path("arguments");
        RedisURI uri = RedisURI.create(secret.path("uri").asText());
        try (RedisClient client = RedisClient.create(uri); StatefulRedisConnection<String, String> state = client.connect()) {
            RedisCommands<String, String> sync = state.sync();
            String prefix = secret.path("keyPrefix").asText("");
            Object value = switch (command) {
                case "GET" -> sync.get(key(prefix, args, 0));
                case "SET" -> sync.set(key(prefix, args, 0), argument(args, 1));
                case "DEL" -> sync.del(key(prefix, args, 0));
                case "HGET" -> sync.hget(key(prefix, args, 0), argument(args, 1));
                case "HSET" -> sync.hset(key(prefix, args, 0), argument(args, 1), argument(args, 2));
                case "LPUSH" -> sync.lpush(key(prefix, args, 0), argument(args, 1));
                case "RPUSH" -> sync.rpush(key(prefix, args, 0), argument(args, 1));
                case "LRANGE" -> sync.lrange(key(prefix, args, 0), Long.parseLong(argument(args, 1)), Long.parseLong(argument(args, 2)));
                case "PUBLISH" -> sync.publish(key(prefix, args, 0), argument(args, 1));
                default -> throw new BusinessException("workflow.redisCommandForbidden");
            };
            return objectMapper.createObjectNode().set("value", objectMapper.valueToTree(value));
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.connectionExecutionFailed"); }
    }

    /** 校验并添加连接限定的 Redis Key 前缀。 */
    private String key(String prefix, ArrayNode arguments, int index) { return prefix + argument(arguments, index); }

    /** 读取必需的 Redis 字符串参数。 */
    private String argument(ArrayNode arguments, int index) {
        if (arguments == null || index >= arguments.size() || arguments.get(index).isNull()) throw new BusinessException("workflow.dataInputInvalid");
        return arguments.get(index).asText();
    }

    /** 执行受 Bucket 和 Key 前缀约束的 S3 操作。 */
    private JsonNode s3(JsonNode config) {
        WorkflowConnectionService.StoredConnection connection = connections.resolved(requiredConnection(config, "connectionId"), Set.of("S3"));
        JsonNode secret = connection.config();
        String bucket = config.path("bucket").asText(secret.path("bucket").asText());
        if (bucket.isBlank() || !bucket.equals(secret.path("bucket").asText())) throw new BusinessException("workflow.s3PathForbidden");
        String prefix = secret.path("keyPrefix").asText("");
        String key = config.path("key").asText("");
        if (!key.isBlank() && !key.startsWith(prefix)) throw new BusinessException("workflow.s3PathForbidden");
        String operation = config.path("operation").asText("GET").toUpperCase(Locale.ROOT);
        if (!"LIST".equals(operation) && key.isBlank()) throw new BusinessException("workflow.s3PathForbidden");
        String listPrefix = config.path("prefix").asText(prefix);
        if ("LIST".equals(operation) && !listPrefix.startsWith(prefix)) throw new BusinessException("workflow.s3PathForbidden");
        try (S3Client client = s3Client(secret)) {
            return switch (operation) {
                case "PUT" -> {
                    byte[] bytes = config.hasNonNull("base64") ? Base64.getDecoder().decode(config.path("base64").asText())
                        : config.path("content").asText("").getBytes(StandardCharsets.UTF_8);
                    client.putObject(builder -> builder.bucket(bucket).key(key).contentType(config.path("contentType").asText("application/octet-stream")),
                        RequestBody.fromBytes(bytes));
                    yield objectMapper.createObjectNode().put("uploaded", true).put("bytes", bytes.length).put("key", key);
                }
                case "GET" -> {
                    ResponseBytes<GetObjectResponse> bytes = client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
                    yield objectMapper.createObjectNode().put("base64", Base64.getEncoder().encodeToString(bytes.asByteArray()))
                        .put("bytes", bytes.asByteArray().length)
                        .put("contentType", bytes.response().contentType());
                }
                case "LIST" -> {
                    List<S3Object> objects = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                        .prefix(listPrefix).maxKeys(Math.max(1, Math.min(config.path("maxKeys").asInt(100), 1000))).build()).contents();
                    yield objectMapper.createObjectNode().set("objects", objectMapper.valueToTree(objects.stream()
                        .map(item -> Map.of("key", item.key(), "size", item.size())).toList()));
                }
                case "DELETE" -> {
                    if (!secret.path("allowDelete").asBoolean(false)) throw new BusinessException("workflow.s3DeleteForbidden");
                    client.deleteObject(builder -> builder.bucket(bucket).key(key));
                    yield objectMapper.createObjectNode().put("deleted", true).put("key", key);
                }
                default -> throw new BusinessException("workflow.dataOperationInvalid");
            };
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.connectionExecutionFailed"); }
    }

    /** 使用受管密钥创建短生命周期 S3 客户端。 */
    private S3Client s3Client(JsonNode config) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(config.path("region").asText("us-east-1")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                config.path("accessKey").asText(), config.path("secretKey").asText())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(config.path("pathStyle").asBoolean(true)).build());
        if (config.hasNonNull("endpoint")) builder.endpointOverride(URI.create(config.path("endpoint").asText()));
        return builder.build();
    }

    /** 向限定 Topic 发布 Kafka 消息并等待 Broker 确认。 */
    private JsonNode kafka(JsonNode config) {
        WorkflowConnectionService.StoredConnection connection = connections.resolved(requiredConnection(config, "connectionId"), Set.of("KAFKA"));
        JsonNode secret = connection.config();
        String topic = config.path("topic").asText();
        if (!topic.startsWith(secret.path("topicPrefix").asText(""))) throw new BusinessException("workflow.messageDestinationForbidden");
        Properties properties = kafkaProperties(secret);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, config.path("key").asText(null),
                textValue(config.path("value")))).get(Math.min(config.path("timeoutSeconds").asInt(30), 120), TimeUnit.SECONDS);
            return objectMapper.createObjectNode().put("topic", metadata.topic()).put("partition", metadata.partition()).put("offset", metadata.offset());
        } catch (Exception exception) { throw new BusinessException("workflow.connectionExecutionFailed"); }
    }

    /** 构造 Kafka 生产者安全配置。 */
    private Properties kafkaProperties(JsonNode config) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.path("bootstrapServers").asText());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all"); properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        addKafkaSecurity(properties, config);
        return properties;
    }

    /** 添加可选 Kafka TLS/SASL 配置。 */
    static void addKafkaSecurity(Properties properties, JsonNode config) {
        String protocol = config.path("securityProtocol").asText("");
        if (!protocol.isBlank()) properties.put("security.protocol", protocol);
        if (config.hasNonNull("saslMechanism")) properties.put("sasl.mechanism", config.path("saslMechanism").asText());
        if (config.hasNonNull("username") || config.hasNonNull("password")) {
            properties.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                + jaas(config.path("username").asText()) + "\" password=\"" + jaas(config.path("password").asText()) + "\";");
        }
    }

    /** 转义 JAAS 账号密码，防止配置注入。 */
    private static String jaas(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", ""); }

    /** 向限定 Exchange 发布 RabbitMQ 消息并等待 Broker Confirm。 */
    private JsonNode rabbit(JsonNode config) {
        WorkflowConnectionService.StoredConnection connection = connections.resolved(requiredConnection(config, "connectionId"), Set.of("RABBITMQ"));
        JsonNode secret = connection.config();
        String exchange = config.path("exchange").asText();
        if (!exchange.startsWith(secret.path("exchangePrefix").asText(""))) throw new BusinessException("workflow.messageDestinationForbidden");
        ConnectionFactory factory = new ConnectionFactory();
        try {
            factory.setUri(secret.path("uri").asText()); factory.setConnectionTimeout(10_000);
            try (com.rabbitmq.client.Connection rabbit = factory.newConnection(); Channel channel = rabbit.createChannel()) {
                channel.confirmSelect();
                channel.basicPublish(exchange, config.path("routingKey").asText(""), null,
                    textValue(config.path("value")).getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(Math.min(config.path("timeoutSeconds").asInt(30), 120) * 1000L);
                return objectMapper.createObjectNode().put("published", true).put("exchange", exchange);
            }
        } catch (Exception exception) { throw new BusinessException("workflow.connectionExecutionFailed"); }
    }

    /** 读取必需的正整数连接或路由 ID。 */
    private Long requiredConnection(JsonNode config, String field) {
        if (!config.hasNonNull(field) || config.path(field).asLong() <= 0) throw new BusinessException("workflow.connectionInvalid");
        return config.path(field).asLong();
    }

    /** 将 JSON 值转换为消息文本。 */
    private String textValue(JsonNode value) { return value.isTextual() ? value.asText() : value.toString(); }

    /** 拒绝邮件主题换行注入。 */
    private String singleLine(String value) {
        if (value.contains("\r") || value.contains("\n")) throw new BusinessException("workflow.dataInputInvalid");
        return value;
    }
}
