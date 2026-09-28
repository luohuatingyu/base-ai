package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.modeltype.LlmModelTypeRegistry;
import com.baseai.platform.modeltype.LlmOperation;
import com.baseai.platform.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 图片与视频各自调用独立 JSON 协议，持久化任务并复用统一的 Token 用量记录。 */
@Service
public class MediaGenerationService {
    private static final Set<String> SUCCESS = Set.of("SUCCEEDED", "SUCCESS", "COMPLETED", "DONE");
    private static final Set<String> FAILURE = Set.of("FAILED", "ERROR", "CANCELLED", "CANCELED");
    private static final Set<String> PENDING = Set.of("QUEUED", "PENDING", "RUNNING", "PROCESSING", "IN_PROGRESS");
    private final JdbcTemplate jdbc;
    private final LlmManagementService management;
    private final LlmUsageService usage;
    private final ObjectMapper mapper;
    private final LlmModelTypeRegistry modelTypeRegistry;

    /** 注入任务存储、可信模型配置、用量服务、JSON 编解码器和类型协议注册表。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MediaGenerationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  LlmManagementService management, LlmUsageService usage, ObjectMapper mapper,
                                  LlmModelTypeRegistry modelTypeRegistry) {
        this.jdbc = jdbc;
        this.management = management;
        this.usage = usage;
        this.mapper = mapper;
        this.modelTypeRegistry = modelTypeRegistry;
    }

    /** 兼容既有单元测试构造方式。 */
    public MediaGenerationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  LlmManagementService management, LlmUsageService usage, ObjectMapper mapper) {
        this(jdbc, management, usage, mapper, new LlmModelTypeRegistry());
    }

    /** 返回启用且只声明一种生成能力的模型，不向浏览器暴露供应商密钥。 */
    public List<ModelOption> models() {
        var providers = management.providers().stream().filter(LlmManagementService.ProviderView::enabled)
            .collect(java.util.stream.Collectors.toMap(LlmManagementService.ProviderView::id, item -> item));
        return management.models().stream()
            .filter(item -> Boolean.TRUE.equals(item.getEnabled()) && providers.containsKey(item.getProviderId()))
            .filter(item -> item.getSupportedModelTypes().size() == 1
                && (modelTypeRegistry.supports(item.getSupportedModelTypes().get(0), LlmOperation.IMAGE_GENERATION)
                    || modelTypeRegistry.supports(item.getSupportedModelTypes().get(0), LlmOperation.VIDEO_GENERATION)))
            .map(item -> new ModelOption(item.getId(), item.getName(), item.getModelName(), item.getSupportedModelTypes().get(0),
                providers.get(item.getProviderId()).code(), providers.get(item.getProviderId()).name())).toList();
    }

    /** 校验归属和模型后创建一次调用；失败重试必须由用户重新提交并产生新的任务及用量。 */
    public Map<String, Object> generate(GenerateRequest request) {
        Long owner = AuthContext.require().id();
        validate(request);
        requireConversation(owner, request.conversationId());
        var candidate = candidate(request.modelId(), request.modelType());
        String usageId = usage.start(LlmUsageService.Start.current(null, owner, request.conversationId(),
            modelTypeRegistry.supports(request.modelType(), LlmOperation.IMAGE_GENERATION)
                ? "AI_IMAGE_GENERATION" : "AI_VIDEO_GENERATION", "media_generation", request.modelType()));
        if (usageId == null) throw new BusinessException("llm.generationRecordFailed");
        long id;
        try { id = insertTask(owner, request, candidate, usageId); }
        catch (RuntimeException exception) {
            usage.finish(usageId, new LlmUsageService.Completion("FAILED", candidate.providerCode(), candidate.model(), false,
                null, null, null, "PersistenceException", "TASK_SAVE_FAILED", Instant.now()));
            throw exception;
        }
        Map<String, Object> task = task(id, owner);
        try { apply(task, invoke(candidate, request.modelType(), request.prompt().trim(), request.options(), null)); }
        catch (RuntimeException exception) { fail(task, "PROVIDER_REQUEST_FAILED"); }
        return publicTask(task(id, owner));
    }

    /** 查看自己的任务，轮询与后台恢复均更新原始用量记录。 */
    public synchronized Map<String, Object> status(long id) {
        Long owner = AuthContext.require().id();
        poll(task(id, owner));
        return publicTask(task(id, owner));
    }

    /** 从会话恢复媒体历史；任务与文本消息分别存储，生成产物不进入聊天上下文。 */
    public List<Map<String, Object>> history(long conversationId) {
        Long owner = AuthContext.require().id();
        requireConversation(owner, conversationId);
        return jdbc.queryForList("SELECT id FROM ai_media_generation_task WHERE owner_user_id=? AND conversation_id=? ORDER BY id DESC LIMIT 100", owner, conversationId)
            .stream().map(row -> publicTask(task(((Number) row.get("id")).longValue(), owner))).toList();
    }

    /** 浏览器关闭后继续轮询持久化任务；重启后可恢复已取得供应商编号的任务。 */
    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public synchronized void pollPending() {
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,owner_user_id FROM ai_media_generation_task WHERE status='RUNNING' ORDER BY updated_at LIMIT 20")) {
            try { poll(task(((Number) row.get("id")).longValue(), ((Number) row.get("owner_user_id")).longValue())); }
            catch (RuntimeException ignored) { /* 存储或配置临时不可用时保留任务，下一轮重试。 */ }
        }
    }

    /** 限制单任务总时长，轮询网络错误不触发重复创建或重复计费。 */
    private void poll(Map<String, Object> task) {
        if (!"RUNNING".equals(task.get("status"))) return;
        if (((Number) task.get("ageSeconds")).longValue() >= 1800) { fail(task, "GENERATION_TIMEOUT"); return; }
        String remoteId = (String) task.get("providerTaskId");
        if (remoteId == null) return;
        try {
            var candidate = candidate(((Number) task.get("modelId")).longValue(), (String) task.get("generationType"));
            // 配置变更不能把旧供应商的任务编号发送给新的供应商。
            if (!candidate.providerCode().equals(task.get("providerCode")) || !candidate.model().equals(task.get("modelName"))) {
                fail(task, "MODEL_CONFIG_CHANGED"); return;
            }
            apply(task, invoke(candidate, (String) task.get("generationType"), null, Map.of(), remoteId));
        } catch (RestClientException exception) {
            // 临时 HTTP/网络错误仍保留运行态；到达总时限后才结束记录。
        } catch (RuntimeException exception) { fail(task, "PROVIDER_RESPONSE_INVALID"); }
    }

    /** 固定相对路径并禁止重定向，用户只能传递生成参数，不能更改凭据发送目的地。 */
    private JsonNode invoke(LlmManagementService.WorkerCandidate candidate, String type, String prompt,
                            Map<String, Object> options, String remoteId) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(Math.min(300, Math.max(1, candidate.timeoutSeconds() == null ? 60 : candidate.timeoutSeconds()))));
        RestClient client = RestClient.builder().requestFactory(factory).build();
        String base = candidate.baseUrl().replaceAll("/+$", "");
        String key = candidate.apiKeys().get(0);
        if (remoteId != null) {
            String statusPath = modelTypeRegistry.generationStatusPath(type, remoteId);
            if (statusPath == null) throw new BusinessException("llm.modelTypeUnsupported");
            return client.get().uri(URI.create(base + statusPath))
                .headers(headers -> headers.setBearerAuth(key)).retrieve().body(JsonNode.class);
        }
        Map<String, Object> body = new LinkedHashMap<>(options == null ? Map.of() : options);
        body.put("model", candidate.model()); body.put("prompt", prompt);
        String path = modelTypeRegistry.generationPath(type);
        if (path == null) throw new BusinessException("llm.modelTypeUnsupported");
        return client.post().uri(URI.create(base + path))
            .contentType(MediaType.APPLICATION_JSON).headers(headers -> headers.setBearerAuth(key)).body(body).retrieve().body(JsonNode.class);
    }

    /** 响应使用替换而非累加语义保存 Token，错误状态优先于媒体字段。 */
    private void apply(Map<String, Object> task, JsonNode response) {
        if (response == null || !response.isObject()) throw new IllegalArgumentException("Empty response");
        var tokens = tokens(response.path("usage"));
        usage.updateTokens((String) task.get("usageId"), tokens);
        String status = response.path("status").asText("").toUpperCase(Locale.ROOT);
        if (FAILURE.contains(status) || response.hasNonNull("error")) { fail(task, "PROVIDER_FAILED"); return; }
        boolean video = "video_model".equals(task.get("generationType"));
        List<Map<String, String>> outputs = outputs(response, video);
        if (!outputs.isEmpty() && (!video || SUCCESS.contains(status) || status.isEmpty())) {
            int updated = jdbc.update("UPDATE ai_media_generation_task SET output_json=?,status='SUCCEEDED',progress=100,finished_at=NOW(6),updated_at=NOW(6) WHERE id=? AND status='RUNNING'",
                json(Map.of("outputs", outputs)), task.get("id"));
            if (updated > 0) usage.finish((String) task.get("usageId"), new LlmUsageService.Completion("SUCCESS", (String) task.get("providerCode"),
                (String) task.get("modelName"), true, tokens.inputTokens(), tokens.outputTokens(), tokens.totalTokens(), null, null, Instant.now()));
            return;
        }
        String remoteId = response.path("id").asText(response.path("task_id").asText((String) task.get("providerTaskId")));
        if (!video || SUCCESS.contains(status) || !status.isEmpty() && !PENDING.contains(status)
            || remoteId == null || !remoteId.matches("[A-Za-z0-9_-]{1,200}")) throw new IllegalArgumentException("Invalid generation response");
        int progress = Math.max(0, Math.min(99, response.path("progress").asInt(0)));
        jdbc.update("UPDATE ai_media_generation_task SET provider_task_id=?,progress=?,updated_at=NOW(6) WHERE id=? AND status='RUNNING'", remoteId, progress, task.get("id"));
    }

    /** 只提取安全 HTTP(S) 地址或图片 Base64，拒绝脚本链接和未知内嵌格式。 */
    private List<Map<String, String>> outputs(JsonNode response, boolean video) {
        List<Map<String, String>> result = new ArrayList<>();
        List<JsonNode> nodes = new ArrayList<>(List.of(response, response.path("video"), response.path("result")));
        if (response.path("data").isArray()) response.path("data").forEach(nodes::add);
        for (JsonNode node : nodes) {
            for (String field : List.of("url", "output_url", "video_url")) {
                JsonNode value = node.path(field);
                if (!value.isTextual()) continue;
                URI uri = URI.create(value.asText());
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IllegalArgumentException("Invalid media URL");
                Map<String, String> output = Map.of("kind", "url", "value", value.asText());
                if (!result.contains(output)) result.add(output);
                break;
            }
            if (!video && node.path("b64_json").isTextual()) {
                String encoded = node.path("b64_json").asText();
                if (encoded.isBlank() || encoded.length() > 20 * 1024 * 1024 || Base64.getDecoder().decode(encoded).length == 0)
                    throw new IllegalArgumentException("Invalid image data");
                result.add(Map.of("kind", "base64", "value", encoded));
            }
        }
        return result;
    }

    /** 缺失、负数、小数和溢出的 Token 均保留 NULL，不估算媒体调用的 Token。 */
    private LlmUsageService.TokenUsage tokens(JsonNode value) {
        return new LlmUsageService.TokenUsage(token(value, "input_tokens", "prompt_tokens"),
            token(value, "output_tokens", "completion_tokens"), token(value, "total_tokens"));
    }

    /** 按兼容字段顺序读取非负长整数。 */
    private Long token(JsonNode value, String... keys) {
        for (String key : keys) {
            JsonNode node = value.path(key);
            if (node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0) return node.longValue();
        }
        return null;
    }

    /** 保存固定错误码；供应商异常正文、密钥和提示词均不进入错误提示。 */
    private void fail(Map<String, Object> task, String code) {
        int updated = jdbc.update("UPDATE ai_media_generation_task SET status='FAILED',error_message=?,finished_at=NOW(6),updated_at=NOW(6) WHERE id=? AND status='RUNNING'", code, task.get("id"));
        if (updated > 0) usage.finish((String) task.get("usageId"), new LlmUsageService.Completion("FAILED", (String) task.get("providerCode"),
            (String) task.get("modelName"), false, null, null, null, "MediaGenerationException", code, Instant.now()));
    }

    /** 插入持久任务；凭据只驻留于模型配置，不随任务保存。 */
    private long insertTask(Long owner, GenerateRequest request, LlmManagementService.WorkerCandidate candidate, String usageId) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO ai_media_generation_task(owner_user_id,conversation_id,model_id,generation_type,provider_code,model_name,prompt,request_json,usage_id,status,started_at)
                VALUES (?,?,?,?,?,?,?,?,?,'RUNNING',NOW(6))
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, owner); statement.setObject(2, request.conversationId()); statement.setLong(3, request.modelId());
            statement.setString(4, request.modelType()); statement.setString(5, candidate.providerCode()); statement.setString(6, candidate.model());
            statement.setString(7, request.prompt().trim()); statement.setString(8, json(request.options())); statement.setString(9, usageId);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    /** 查询所有者任务并联查用量，未知和越权使用相同响应。 */
    private Map<String, Object> task(long id, Long owner) {
        try {
            return jdbc.queryForMap("""
                SELECT t.id,t.conversation_id conversationId,t.model_id modelId,t.generation_type generationType,
                    t.provider_code providerCode,t.model_name modelName,t.prompt,t.provider_task_id providerTaskId,
                    t.output_json outputJson,t.usage_id usageId,t.status,t.progress,t.error_message errorMessage,
                    t.created_at createdAt,TIMESTAMPDIFF(SECOND,t.started_at,NOW(6)) ageSeconds,
                    u.trace_id traceId,u.input_tokens inputTokens,u.output_tokens outputTokens,u.total_tokens totalTokens
                FROM ai_media_generation_task t LEFT JOIN llm_usage_record u ON u.usage_id=t.usage_id
                WHERE t.id=? AND t.owner_user_id=?
                """, id, owner);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) { throw new BusinessException(404, "llm.generationTaskNotFound"); }
    }

    /** 输出任务状态与媒体结果，隐藏用于后端调度的字段。 */
    private Map<String, Object> publicTask(Map<String, Object> task) {
        Map<String, Object> result = new LinkedHashMap<>(task);
        result.remove("ageSeconds"); result.remove("providerTaskId");
        Object output = result.get("outputJson");
        try { result.put("outputJson", output == null ? Map.of() : mapper.readTree(output.toString())); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored media", exception); }
        return result;
    }

    /** 在任何调用及用量登记之前验证会话属于当前用户。 */
    private void requireConversation(Long owner, Long id) {
        if (id == null) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_conversation WHERE id=? AND owner_id=?", Integer.class, id, owner);
        if (count == null || count == 0) throw new BusinessException(404, "chat.notFound");
    }

    /** 验证两类生成的请求边界和可透传参数，禁止调用路径、认证等控制参数。 */
    private void validate(GenerateRequest request) {
        String requestedType = request == null ? "" : request.modelType();
        boolean generationType = modelTypeRegistry.supports(requestedType, LlmOperation.IMAGE_GENERATION)
            || modelTypeRegistry.supports(requestedType, LlmOperation.VIDEO_GENERATION);
        if (request == null || !generationType
            || request.modelId() == null || request.modelId() <= 0 || request.prompt() == null
            || request.prompt().isBlank() || request.prompt().length() > 100000
            || request.conversationId() != null && request.conversationId() <= 0) throw new BusinessException("llm.generationRequestInvalid");
        Map<String, Object> options = request.options() == null ? Map.of() : request.options();
        Set<String> allowed = modelTypeRegistry.supports(request.modelType(), LlmOperation.IMAGE_GENERATION)
            ? Set.of("size", "quality", "style", "n", "response_format")
            : Set.of("duration", "aspect_ratio", "resolution", "fps");
        if (!allowed.containsAll(options.keySet()) || options.values().stream().anyMatch(value -> value == null
            || !(value instanceof String || value instanceof Number) || value.toString().length() > 100))
            throw new BusinessException("llm.generationRequestInvalid");
        for (String field : List.of("n", "duration", "fps")) if (options.containsKey(field)) {
            JsonNode number = mapper.valueToTree(options.get(field));
            int max = field.equals("n") ? 4 : field.equals("duration") ? 60 : 120;
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 1 || number.intValue() > max)
                throw new BusinessException("llm.generationRequestInvalid");
        }
    }

    /** 拒绝历史混合类型配置，图片与视频不能通过篡改参数互相调用。 */
    private LlmManagementService.WorkerCandidate candidate(Long modelId, String type) {
        var route = management.resolveModel(modelId, type, false, null);
        if (route == null || route.candidates() == null || route.candidates().size() != 1) throw new BusinessException("llm.modelTypeUnsupported");
        var candidate = route.candidates().get(0);
        boolean supported = modelTypeRegistry.supports(type, LlmOperation.IMAGE_GENERATION)
            || modelTypeRegistry.supports(type, LlmOperation.VIDEO_GENERATION);
        if (!supported || candidate == null || !List.of(type).equals(candidate.supportedModelTypes()) || candidate.apiKeys() == null || candidate.apiKeys().isEmpty())
            throw new BusinessException("llm.modelTypeUnsupported");
        return candidate;
    }

    /** 序列化任务输出，失败时不泄露请求正文。 */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize media task", exception); }
    }

    public record GenerateRequest(String modelType, Long modelId, String prompt, Long conversationId, Map<String, Object> options) {}
    public record ModelOption(Long id, String name, String modelName, String modelType, String providerCode, String providerName) {}
}
