package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 为已发布 Kafka 和 RabbitMQ 触发节点维护受控消费者生命周期。 */
@Service
public class WorkflowMessageTriggerManager {
    private static final Logger log = LoggerFactory.getLogger(WorkflowMessageTriggerManager.class);
    private final WorkflowService workflowService;
    private final WorkflowConnectionService connectionService;
    private final WorkflowTriggerService triggerService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 32, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "workflow-message-trigger"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    /** 注入工作流、连接、触发和 JSON 服务。 */
    public WorkflowMessageTriggerManager(WorkflowService workflowService, WorkflowConnectionService connectionService,
                                         WorkflowTriggerService triggerService, ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.connectionService = connectionService;
        this.triggerService = triggerService;
        this.objectMapper = objectMapper;
    }

    /** 周期性对齐当前已发布的消息触发节点。 */
    @Scheduled(fixedDelay = 30_000L)
    public void refresh() {
        Map<String, WorkflowModels.TriggerDefinition> expected = new HashMap<>();
        workflowService.triggerDefinitions().stream()
            .filter(item -> "KAFKA_TRIGGER".equals(item.nodeType()) || "RABBITMQ_TRIGGER".equals(item.nodeType()))
            .filter(this::configurationExecutable)
            .forEach(item -> expected.put(key(item), item));
        expected.forEach((key, trigger) -> workers.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.future().isDone()) return existing;
            if (existing != null) existing.close();
            try { return start(trigger); }
            catch (RejectedExecutionException exception) {
                log.warn("Workflow message trigger capacity reached: {}", key); return null;
            }
        }));
        workers.keySet().removeIf(key -> {
            if (expected.containsKey(key)) return false;
            Worker worker = workers.get(key); if (worker != null) worker.close(); return true;
        });
    }

    /** 资源修订失效时停止消费者，重新发布后刷新周期会自动恢复。 */
    private boolean configurationExecutable(WorkflowModels.TriggerDefinition trigger) {
        try { workflowService.validateExecutableConfiguration(workflowService.storedVersion(trigger.versionId())); return true; }
        catch (RuntimeException exception) { log.warn("Workflow message trigger disabled until republished: {}", key(trigger)); return false; }
    }

    /** 根据触发器类型启动后台消费者。 */
    private Worker start(WorkflowModels.TriggerDefinition trigger) {
        AtomicBoolean running = new AtomicBoolean(true);
        Future<?> future = executor.submit(() -> {
            try {
                if ("KAFKA_TRIGGER".equals(trigger.nodeType())) consumeKafka(trigger, running);
                else consumeRabbit(trigger, running);
            } catch (Exception exception) {
                if (running.get()) log.error("Workflow message trigger stopped: {}", key(trigger), exception);
            }
        });
        return new Worker(running, future);
    }

    /** 使用手动提交消费 Kafka，工作流运行入队后才确认 Offset。 */
    private void consumeKafka(WorkflowModels.TriggerDefinition trigger, AtomicBoolean running) {
        JsonNode config = trigger.config();
        WorkflowConnectionService.StoredConnection connection = connectionService.resolved(config.path("connectionId").asLong(),
            java.util.Set.of("KAFKA"));
        String topic = config.path("topic").asText();
        String prefix = connection.config().path("topicPrefix").asText("");
        if (topic.isBlank() || !topic.startsWith(prefix)) throw new BusinessException("workflow.messageDestinationForbidden");
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.config().path("bootstrapServers").asText());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, config.path("groupId").asText("base-ai-" + trigger.workflowId() + "-" + trigger.nodeId()));
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.path("offsetReset").asText("latest"));
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Math.max(1, Math.min(config.path("maxPollRecords").asInt(10), 100)));
        WorkflowConnectorNodeExecutor.addKafkaSecurity(properties, connection.config());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(java.util.List.of(topic));
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records) {
                    String eventId = record.topic() + ":" + record.partition() + ":" + record.offset();
                    Map<String, Object> inputs = new java.util.LinkedHashMap<>();
                    inputs.put("topic", record.topic()); inputs.put("partition", record.partition()); inputs.put("offset", record.offset());
                    inputs.put("key", record.key()); inputs.put("value", jsonOrText(record.value()));
                    if (deliver(trigger, eventId, inputs)) {
                        consumer.commitSync(Map.of(new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()),
                            new org.apache.kafka.clients.consumer.OffsetAndMetadata(record.offset() + 1)));
                    }
                }
            }
        }
    }

    /** 使用手动 Ack 消费 RabbitMQ，失败时重新入队。 */
    private void consumeRabbit(WorkflowModels.TriggerDefinition trigger, AtomicBoolean running) throws Exception {
        JsonNode config = trigger.config();
        WorkflowConnectionService.StoredConnection connection = connectionService.resolved(config.path("connectionId").asLong(),
            java.util.Set.of("RABBITMQ"));
        String queue = config.path("queue").asText();
        if (queue.isBlank() || !queue.startsWith(connection.config().path("queuePrefix").asText(""))) {
            throw new BusinessException("workflow.messageDestinationForbidden");
        }
        ConnectionFactory factory = new ConnectionFactory(); factory.setUri(connection.config().path("uri").asText());
        try (com.rabbitmq.client.Connection rabbit = factory.newConnection(); Channel channel = rabbit.createChannel()) {
            channel.basicQos(Math.max(1, Math.min(config.path("prefetch").asInt(10), 100)));
            channel.basicConsume(queue, false, (tag, delivery) -> {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                String messageId = delivery.getProperties().getMessageId();
                String eventId = messageId == null || messageId.isBlank()
                    ? queue + ":" + digest(body) : queue + ":" + messageId;
                Map<String, Object> inputs = Map.of("queue", queue, "routingKey", delivery.getEnvelope().getRoutingKey(),
                    "value", jsonOrText(body), "redelivered", delivery.getEnvelope().isRedeliver());
                if (deliver(trigger, eventId, inputs)) channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                else channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            }, tag -> { });
            while (running.get() && rabbit.isOpen() && !Thread.currentThread().isInterrupted()) Thread.sleep(500L);
        }
    }

    /** 将事件交给幂等触发服务，重复事件视为已确认。 */
    private boolean deliver(WorkflowModels.TriggerDefinition trigger, String eventId, Map<String, Object> inputs) {
        try { triggerService.message(trigger, eventId, inputs); return true; }
        catch (BusinessException exception) {
            if ("workflow.triggerDuplicate".equals(exception.getMessageKey())) return true;
            log.warn("Workflow message delivery failed: {}", key(trigger)); return false;
        }
    }

    /** 优先将消息正文解析为 JSON，否则保留原文本。 */
    private Object jsonOrText(String value) {
        if (value == null) return "";
        try { return objectMapper.convertValue(objectMapper.readTree(value), Object.class); }
        catch (Exception exception) { return value; }
    }

    /** 计算缺少消息 ID 时的稳定摘要。 */
    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    /** 生成包含版本的消费者键。 */
    private String key(WorkflowModels.TriggerDefinition trigger) {
        return trigger.workflowId() + ":" + trigger.versionId() + ":" + trigger.nodeId();
    }

    /** 停止全部消费者和后台线程。 */
    @PreDestroy
    public void shutdown() { workers.values().forEach(Worker::close); workers.clear(); executor.shutdownNow(); }

    /** 保存单个后台消费者的取消句柄。 */
    private record Worker(AtomicBoolean running, Future<?> future) {
        /** 请求消费者退出并中断阻塞等待。 */
        private void close() { running.set(false); future.cancel(true); }
    }
}
