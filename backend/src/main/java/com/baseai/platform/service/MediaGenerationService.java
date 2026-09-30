package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.modeltype.LlmModelTypeRegistry;
import com.baseai.platform.modeltype.LlmOperation;
import com.baseai.platform.modeltype.MediaGenerationAgentRegistry;
import com.baseai.platform.modeltype.MediaGenerationAgentStrategy;
import com.baseai.platform.modeltype.MediaGenerationProtocolRegistry;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
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
    private final MediaGenerationAgentRegistry agentRegistry;
    private final MediaAssetService mediaAssets;
    private final OssMediaStorageService oss;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int ARK_MAX_IMAGE_BYTES = 30 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    /** JSON 请求需要为 Base64 膨胀和请求元数据预留空间，通用协议继续保持历史边界。 */
    private static final int MAX_VIDEO_BYTES = 6 * 1024 * 1024;
    private static final int ARK_MAX_VIDEO_BYTES = 50 * 1024 * 1024;
    private static final int ARK_MAX_AUDIO_BYTES = 15 * 1024 * 1024;
    private static final int MAX_INPUTS = 4;
    private static final int MAX_PROVIDER_ERROR_BYTES = 16 * 1024;
    private static final int MAX_PROVIDER_ERROR_CODE_LENGTH = 120;
    private static final int MAX_PROVIDER_ERROR_MESSAGE_LENGTH = 600;

    /** 注入任务存储、可信模型配置、用量服务、JSON 编解码器和类型协议注册表。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MediaGenerationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  LlmManagementService management, LlmUsageService usage, ObjectMapper mapper,
                                  LlmModelTypeRegistry modelTypeRegistry,
                                  MediaGenerationAgentRegistry agentRegistry,
                                  MediaAssetService mediaAssets,
                                  OssMediaStorageService oss) {
        this.jdbc = jdbc;
        this.management = management;
        this.usage = usage;
        this.mapper = mapper;
        this.modelTypeRegistry = modelTypeRegistry;
        this.agentRegistry = agentRegistry;
        this.mediaAssets = mediaAssets;
        this.oss = oss;
    }

    /** 兼容已注入模型类型注册表的调用方。 */
    public MediaGenerationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  LlmManagementService management, LlmUsageService usage, ObjectMapper mapper,
                                  LlmModelTypeRegistry modelTypeRegistry) {
        this(jdbc, management, usage, mapper, modelTypeRegistry,
            new MediaGenerationAgentRegistry(modelTypeRegistry, new MediaGenerationProtocolRegistry()), new MediaAssetService(), null);
    }

    /** 兼容既有显式注入协议注册表的调用方。 */
    public MediaGenerationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  LlmManagementService management, LlmUsageService usage, ObjectMapper mapper,
                                  LlmModelTypeRegistry modelTypeRegistry,
                                  MediaGenerationProtocolRegistry protocolRegistry) {
        this(jdbc, management, usage, mapper, modelTypeRegistry,
            new MediaGenerationAgentRegistry(modelTypeRegistry, protocolRegistry), new MediaAssetService(), null);
    }

    /** 兼容既有单元测试构造方式。 */
    public MediaGenerationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  LlmManagementService management, LlmUsageService usage, ObjectMapper mapper) {
        this(jdbc, management, usage, mapper, new LlmModelTypeRegistry());
    }

    /** 返回启用且声明图片或视频能力的模型；组合模型按输出类型展开，不向浏览器暴露供应商密钥。 */
    public List<ModelOption> models() {
        var providers = management.providers().stream().filter(LlmManagementService.ProviderView::enabled)
            .collect(java.util.stream.Collectors.toMap(LlmManagementService.ProviderView::id, item -> item));
        return management.models().stream()
            .filter(item -> Boolean.TRUE.equals(item.getEnabled()) && providers.containsKey(item.getProviderId()))
            .flatMap(item -> item.getSupportedModelTypes().stream()
                .filter(type -> modelTypeRegistry.supports(type, LlmOperation.IMAGE_GENERATION)
                    || modelTypeRegistry.supports(type, LlmOperation.VIDEO_GENERATION))
                .map(type -> mediaModelOption(item, type, providers.get(item.getProviderId()))))
            .toList();
    }

    /** 将一个模型配置展开为指定输出类型的媒体目录项。 */
    private ModelOption mediaModelOption(com.baseai.platform.domain.LlmModel model, String type, LlmManagementService.ProviderView provider) {
        String configuredProfile = modelProfile(model.getGenerationProfile(), model.getModelName());
        String protocol = agentRegistry.protocol(provider.strategyCode(), provider.code(), model.getModelName(), configuredProfile);
        return new ModelOption(model.getId(), model.getName(), model.getModelName(), type, provider.code(), provider.name(),
            protocol, agentRegistry.capabilities(provider.strategyCode(), provider.code(), model.getModelName(), configuredProfile, type));
    }

    /** 校验归属和模型后创建一次调用；失败重试必须由用户重新提交并产生新的任务及用量。 */
    public Map<String, Object> generate(GenerateRequest request) {
        Long owner = AuthContext.require().id();
        var candidate = request == null ? null : candidate(request.modelId(), request.modelType());
        validate(request, candidate);
        request = preparedRequest(request, candidate, publicOrigin());
        requireConversation(owner, request.conversationId());
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
        try { apply(task, invoke(candidate, request, null)); }
        catch (ProviderRequestException exception) { fail(task, exception.failureMessage()); }
        catch (RestClientException exception) { fail(task, networkFailureMessage(exception)); }
        catch (RuntimeException exception) { fail(task, "PROVIDER_REQUEST_FAILED"); }
        return publicTask(task(id, owner));
    }

    /** 查看自己的任务，轮询与后台恢复均更新原始用量记录。 */
    public synchronized Map<String, Object> status(long id) {
        Long owner = AuthContext.require().id();
        poll(task(id, owner));
        return publicTask(task(id, owner));
    }

    /** 取消当前用户的排队或运行任务，状态更新使用条件避免重复计费。 */
    public synchronized Map<String, Object> cancel(long id) {
        Long owner = AuthContext.require().id();
        Map<String, Object> value = task(id, owner);
        int updated = jdbc.update("UPDATE ai_media_generation_task SET status='CANCELLED',error_message='CANCELLED',finished_at=NOW(6),updated_at=NOW(6) WHERE id=? AND owner_user_id=? AND status IN ('QUEUED','RUNNING')", id, owner);
        if (updated > 0) usage.finish((String) value.get("usageId"), new LlmUsageService.Completion("CANCELLED", (String) value.get("providerCode"),
            (String) value.get("modelName"), false, null, null, null, "MediaGenerationException", "CANCELLED", Instant.now()));
        return publicTask(task(id, owner));
    }

    /** 删除已结束的媒体任务，禁止通过删除绕过运行中任务的计费状态。 */
    public void delete(long id) {
        Long owner = AuthContext.require().id();
        int updated = jdbc.update("DELETE FROM ai_media_generation_task WHERE id=? AND owner_user_id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED')", id, owner);
        if (updated == 0) throw new BusinessException(404, "llm.generationTaskNotFound");
    }

    /** 从会话恢复媒体历史；任务与文本消息分别存储，生成产物不进入聊天上下文。 */
    public List<Map<String, Object>> history(long conversationId) {
        Long owner = AuthContext.require().id();
        requireConversation(owner, conversationId);
        return jdbc.queryForList("SELECT id FROM ai_media_generation_task WHERE owner_user_id=? AND conversation_id=? ORDER BY id DESC LIMIT 100", owner, conversationId)
            .stream().map(row -> publicTask(task(((Number) row.get("id")).longValue(), owner))).toList();
    }

    /** 独立生成页按所有者分页查询，包含升级前关联聊天的历史任务。 */
    public Map<String, Object> list(String type, int page) {
        if (!Set.of("image_model", "video_model").contains(type) || page < 0 || page > 100000) throw new BusinessException("llm.generationRequestInvalid");
        Long owner = AuthContext.require().id();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_media_generation_task WHERE owner_user_id=? AND generation_type=?", Long.class, owner, type);
        var items = jdbc.queryForList("SELECT id FROM ai_media_generation_task WHERE owner_user_id=? AND generation_type=? ORDER BY id DESC LIMIT 20 OFFSET ?", owner, type, page * 20)
            .stream().map(row -> publicTask(task(((Number) row.get("id")).longValue(), owner))).toList();
        return Map.of("items", items, "total", total == null ? 0 : total);
    }

    /** 管理员显式配置优先；空值沿用官方模型 ID 自动识别。 */
    private static String modelProfile(String configured, String model) { return configured == null || configured.isBlank() ? model : configured; }
    /** 为生成候选项解析能力画像，不改变供应商实际 model 字段。 */
    private static String profile(LlmManagementService.WorkerCandidate candidate) { return modelProfile(candidate.generationProfile(), candidate.model()); }
    /** 按候选携带的供应商绑定策略解析 Agent，历史空值才使用兼容推断。 */
    private MediaGenerationAgentStrategy resolveStrategy(LlmManagementService.WorkerCandidate candidate) {
        return agentRegistry.resolve(candidate.strategyCode(), candidate.providerCode(), candidate.model(), profile(candidate));
    }

    /** 在登记用量前验证模式并规范请求；样片只允许引用当前用户同模型已完成的任务。 */
    private GenerateRequest preparedRequest(GenerateRequest request, LlmManagementService.WorkerCandidate candidate, String publicOrigin) {
        MediaGenerationAgentStrategy strategy = resolveStrategy(candidate);
        List<MediaInput> inputs = request.inputs() == null ? List.of() : request.inputs();
        if (strategy.id().equals("ARK") && "DRAFT_TO_VIDEO".equals(normalizedMode(request))) {
            inputs = inputs.stream().map(input -> {
                if (!"TASK".equals(input.kind()) || !input.dataUrl().matches("[0-9]{1,18}")) throw new BusinessException("llm.generationRequestInvalid");
                var draft = task(Long.parseLong(input.dataUrl()), AuthContext.require().id());
                try {
                    if (!"SUCCEEDED".equals(draft.get("status")) || !request.modelId().equals(((Number) draft.get("modelId")).longValue())
                        || !mapper.readTree(String.valueOf(draft.get("requestJson"))).path("options").path("draft").asBoolean()
                        || draft.get("providerTaskId") == null) throw new BusinessException("llm.generationRequestInvalid");
                } catch (java.io.IOException exception) { throw new BusinessException("llm.generationRequestInvalid"); }
                return new MediaInput("TASK", input.fileName(), "text/plain", String.valueOf(draft.get("providerTaskId")), "");
            }).toList();
        }
        if (strategy.supportsRemoteInputs()) inputs = mediaAssets.materialize(inputs, publicOrigin);
        List<Map<String, Object>> payload = inputPayload(inputs);
        Map<String, Object> options = strategy.normalizeOptions(request.modelType(), candidate.model(), profile(candidate),
            normalizedMode(request), request.prompt(), payload, request.options());
        // 提前构造一次供应商协议，保证错误地址和未知参数不会产生计费记录。
        strategy.request(request.modelType(), candidate.model(), profile(candidate), request.prompt(), payload, options);
        return new GenerateRequest(request.modelType(), request.modelId(), request.prompt(), request.conversationId(), normalizedMode(request), inputs, options);
    }

    /** 从反向代理转发头解析供应商可访问的平台来源地址。 */
    private String publicOrigin() {
        try {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) return null;
            var request = servlet.getRequest();
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isBlank()) return origin;
            String host = request.getHeader("X-Forwarded-Host");
            String proto = request.getHeader("X-Forwarded-Proto");
            if (host != null && !host.isBlank()) return (proto == null || proto.isBlank() ? request.getScheme() : proto) + "://" + host;
            return request.getScheme() + "://" + request.getServerName() + (request.getServerPort() > 0 && request.getServerPort() != 80 && request.getServerPort() != 443 ? ":" + request.getServerPort() : "");
        } catch (RuntimeException ignored) { return null; }
    }

    /** 消费方舟图片 SSE，保留成功图片及逐图错误，断流不能伪装为完整成功。 */
    JsonNode readImageStream(java.io.InputStream stream) throws java.io.IOException {
        var result = mapper.createObjectNode(); var data = result.putArray("data");
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        String line; StringBuilder event = new StringBuilder(); boolean completed = false;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                if (event.length() > 0) event.append('\n');
                event.append(line.substring(5).stripLeading());
                if (event.length() > 64 * 1024 * 1024) throw new java.io.IOException("Image event too large");
            } else if (line.isBlank() && event.length() > 0) {
                String raw = event.toString(); event.setLength(0);
                if (raw.equals("[DONE]")) { completed = true; continue; }
                JsonNode value = mapper.readTree(raw);
                appendImageStreamData(data, value);
                if (value.has("usage")) result.set("usage", value.get("usage"));
                if (value.path("type").asText().endsWith("completed")) completed = true;
            }
        }
        if (!completed) result.put("incomplete", true);
        if (data.isEmpty()) throw new java.io.IOException("Image stream has no result");
        return result;
    }

    /** 兼容方舟流式事件的顶层和 data 嵌套结果，避免组图流式响应被误判为空。 */
    private void appendImageStreamData(com.fasterxml.jackson.databind.node.ArrayNode target, JsonNode event) {
        if (event.has("url") || event.has("b64_json") || event.has("error")) target.add(event);
        JsonNode nested = event.path("data");
        if (nested.isObject() && (nested.has("url") || nested.has("b64_json") || nested.has("error"))) target.add(nested);
        if (nested.isArray()) nested.forEach(item -> {
            if (item.isObject() && (item.has("url") || item.has("b64_json") || item.has("error"))) target.add(item);
        });
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
            apply(task, invoke(candidate, storedRequest(task), remoteId));
        } catch (ProviderRequestException exception) {
            fail(task, exception.failureMessage());
        } catch (RestClientException exception) {
            // 临时 HTTP/网络错误仍保留运行态；到达总时限后才结束记录。
        } catch (RuntimeException exception) { fail(task, "PROVIDER_RESPONSE_INVALID"); }
    }

    /** 固定相对路径并禁止重定向，用户只能传递生成参数，不能更改凭据发送目的地。 */
    private JsonNode invoke(LlmManagementService.WorkerCandidate candidate, GenerateRequest request, String remoteId) {
        String type = request.modelType();
        MediaGenerationAgentStrategy strategy = resolveStrategy(candidate);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(Math.min(300, Math.max(1, candidate.timeoutSeconds() == null ? 60 : candidate.timeoutSeconds()))));
        RestClient client = RestClient.builder().requestFactory(factory).build();
        String base = candidate.baseUrl().replaceAll("/+$", "");
        String key = candidate.apiKeys().get(0);
        if (remoteId != null) {
            String statusPath = strategy.statusPath(type, candidate.model(), remoteId);
            if (statusPath == null) throw new BusinessException("llm.modelTypeUnsupported");
            String operationPath = strategy.operationPath(type, candidate.model());
            return client.get().uri(endpointUri(base, statusPath, operationPath))
                .headers(headers -> headers.setBearerAuth(key)).retrieve().body(JsonNode.class);
        }
        String path = strategy.generationPath(type, candidate.model());
        if (path == null) throw new BusinessException("llm.modelTypeUnsupported");
        Map<String, Object> body = strategy.request(type, candidate.model(), profile(candidate), request.prompt(),
            inputPayload(request.inputs()), request.options());
        if (body.isEmpty()) throw new BusinessException("llm.modelTypeUnsupported");
        // 提交接口同样支持管理员填写完整生成地址，避免再次追加协议路径导致供应商请求超时。
        return client.post().uri(endpointUri(base, path, path))
            .contentType(MediaType.APPLICATION_JSON).headers(headers -> headers.setBearerAuth(key)).body(body)
            .exchange((httpRequest, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    String errorBody = readProviderErrorBody(response.getBody());
                    throw providerRequestException(response.getStatusCode().value(), errorBody);
                }
                if (Boolean.TRUE.equals(body.get("stream"))) return readImageStream(response.getBody());
                return mapper.readTree(response.getBody());
            });
    }

    /** 读取有限长度的供应商错误正文，避免把大响应或敏感原文写入任务记录。 */
    private String readProviderErrorBody(InputStream stream) {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int remaining = MAX_PROVIDER_ERROR_BYTES;
            int count;
            while (remaining > 0 && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                if (count == 0) continue;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            return "";
        }
    }

    /** 将供应商非 2xx 响应转换为可展示但不泄露凭据的稳定错误描述。 */
    private ProviderRequestException providerRequestException(int status, String body) {
        JsonNode root;
        try { root = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body); }
        catch (Exception ignored) { root = mapper.createObjectNode(); }
        JsonNode error = root.path("error");
        String code = firstNonBlank(error.path("code").asText(null), root.path("code").asText(null), "HTTP_" + status);
        String message = firstNonBlank(error.path("message").asText(null), root.path("message").asText(null), "供应商返回 HTTP " + status);
        return new ProviderRequestException(status, cleanProviderText(code, MAX_PROVIDER_ERROR_CODE_LENGTH, "HTTP_" + status),
            cleanProviderText(message, MAX_PROVIDER_ERROR_MESSAGE_LENGTH, "供应商返回 HTTP " + status));
    }

    /** 将网络异常归类为有限的公开错误，不把内部 URL、请求头或堆栈写入任务记录。 */
    private String networkFailureMessage(RestClientException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) {
                return "PROVIDER_REQUEST_FAILED|NETWORK_TIMEOUT|NETWORK_TIMEOUT|供应商请求超时";
            }
            cause = cause.getCause();
        }
        return "PROVIDER_REQUEST_FAILED|NETWORK_ERROR|NETWORK_ERROR|无法连接供应商";
    }

    /** 统一压缩供应商文本并移除分隔符与控制字符，保证错误记录可安全解析。 */
    private String cleanProviderText(String value, int limit, String fallback) {
        String cleaned = value == null ? "" : value.replaceAll("[\\r\\n\\t|]", " ").replaceAll("\\s+", " ").trim();
        cleaned = cleaned.replaceAll("(?i)(api[-_ ]?key|authorization|bearer|token|secret)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2<redacted>");
        if (cleaned.isBlank()) cleaned = fallback;
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    /** 从候选字段中取首个非空文本，兼容不同供应商的错误结构。 */
    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    /** 将供应商根地址与协议路径拼接；若管理员已填写完整接口地址则避免重复追加路径。 */
    private URI endpointUri(String base, String path) {
        return endpointUri(base, path, null);
    }

    /** 在查询异步任务时剥离已配置的生成接口路径，切换到同一 API 根下的状态接口。 */
    private URI endpointUri(String base, String path, String operationPath) {
        URI configured = URI.create(base);
        String currentPath = configured.getPath() == null ? "" : configured.getPath().replaceAll("/+$", "");
        String requiredPath = path.startsWith("/") ? path : "/" + path;
        String configuredOperationPath = operationPath == null || operationPath.isBlank() ? "" : operationPath.startsWith("/") ? operationPath : "/" + operationPath;
        if (!configuredOperationPath.isBlank() && currentPath.endsWith(configuredOperationPath)
            && !requiredPath.equals(configuredOperationPath) && !requiredPath.startsWith(configuredOperationPath + "/")) {
            currentPath = currentPath.substring(0, currentPath.length() - configuredOperationPath.length());
        }
        int overlap = pathOverlap(currentPath, requiredPath);
        String targetPath = currentPath + requiredPath.substring(overlap);
        try {
            return new URI(configured.getScheme(), configured.getRawAuthority(), targetPath,
                configured.getRawQuery(), null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid provider endpoint", exception);
        }
    }

    /** 计算已配置地址尾部与协议路径前缀的最大重叠长度，支持轮询地址只追加任务 ID。 */
    private int pathOverlap(String currentPath, String requiredPath) {
        int maximum = Math.min(currentPath.length(), requiredPath.length());
        for (int length = maximum; length > 0; length--) {
            if (currentPath.endsWith(requiredPath.substring(0, length))) return length;
        }
        return 0;
    }

    /** 将平台输入转换为协议层可扩展的媒体字段，避免服务直接拼装供应商 JSON。 */
    private List<Map<String, Object>> inputPayload(List<MediaInput> inputs) {
        if (inputs == null) return List.of();
        return inputs.stream().map(input -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", input.kind());
            value.put("name", input.fileName());
            value.put("content_type", input.contentType());
            value.put("data", input.dataUrl());
            value.put("role", input.role());
            return value;
        }).toList();
    }

    /** 从任务元数据恢复轮询所需的请求，不把上传内容再次保存或复制进数据库。 */
    private GenerateRequest storedRequest(Map<String, Object> task) {
        String type = (String) task.get("generationType");
        String mode = task.get("generationMode") == null ? defaultMode(type) : (String) task.get("generationMode");
        return new GenerateRequest(type, ((Number) task.get("modelId")).longValue(), (String) task.get("prompt"),
            task.get("conversationId") == null ? null : ((Number) task.get("conversationId")).longValue(), mode,
            List.of(), Map.of());
    }

    /** 响应使用替换而非累加语义保存 Token，错误状态优先于媒体字段。 */
    private void apply(Map<String, Object> task, JsonNode response) {
        if (response == null || !response.isObject()) throw new IllegalArgumentException("Empty response");
        var tokens = tokens(response.path("usage"));
        usage.updateTokens((String) task.get("usageId"), tokens);
        String status = firstText(response, "status", "task_status", "state").toUpperCase(Locale.ROOT);
        if (FAILURE.contains(status) || response.hasNonNull("error")) { fail(task, "PROVIDER_FAILED"); return; }
        boolean video = "video_model".equals(task.get("generationType"));
        List<Map<String, Object>> outputs = outputs(response, video);
        if (!outputs.isEmpty() && (!video || SUCCESS.contains(status) || status.isEmpty())) {
            backupOutputs(outputs, task);
            int updated = jdbc.update("UPDATE ai_media_generation_task SET output_json=?,status='SUCCEEDED',progress=100,finished_at=NOW(6),updated_at=NOW(6) WHERE id=? AND status='RUNNING'",
                json(Map.of("outputs", outputs, "errors", outputErrors(response), "incomplete", response.path("incomplete").asBoolean())), task.get("id"));
            if (updated > 0) usage.finish((String) task.get("usageId"), new LlmUsageService.Completion("SUCCESS", (String) task.get("providerCode"),
                (String) task.get("modelName"), true, tokens.inputTokens(), tokens.outputTokens(), tokens.totalTokens(), null, null, Instant.now()));
            return;
        }
        String remoteId = firstText(response, "id", "task_id", "taskId", "name");
        if (remoteId.startsWith("operations/")) remoteId = remoteId.substring("operations/".length());
        if (remoteId.isBlank()) remoteId = (String) task.get("providerTaskId");
        if (!video || SUCCESS.contains(status) || !status.isEmpty() && !PENDING.contains(status)
            || remoteId == null || !remoteId.matches("[A-Za-z0-9_-]{1,200}")) throw new IllegalArgumentException("Invalid generation response");
        int progress = Math.max(0, Math.min(99, response.path("progress").asInt(0)));
        jdbc.update("UPDATE ai_media_generation_task SET provider_task_id=?,progress=?,updated_at=NOW(6) WHERE id=? AND status='RUNNING'", remoteId, progress, task.get("id"));
    }

    /** 兼容方舟任务状态字段的历史命名，空值表示响应未携带状态。 */
    /** 为成功结果创建 OSS 备份；备份异常只记录在输出项，不影响已完成的生成任务。 */
    private void backupOutputs(List<Map<String, Object>> outputs, Map<String, Object> task) {
        if (oss == null || !oss.enabled()) return;
        String taskId = String.valueOf(task.get("id"));
        boolean video = "video_model".equals(task.get("generationType"));
        for (Map<String, Object> output : outputs) {
            try {
                String value = String.valueOf(output.get("value"));
                String contentType = outputMimeType(output, video);
                String backup;
                if ("base64".equals(output.get("kind"))) {
                    backup = oss.uploadDataUrl("data:" + contentType + ";base64," + value, contentType, "output", taskId);
                } else {
                    OssMediaStorageService.Downloaded downloaded = oss.download(value, contentType);
                    backup = oss.upload(downloaded.bytes(), downloaded.contentType(), "output", taskId);
                }
                if (backup != null) output.put("ossUrl", backup);
                else output.put("ossBackupError", "OSS_BACKUP_UNAVAILABLE");
            } catch (Exception exception) { output.put("ossBackupError", "OSS_BACKUP_FAILED"); }
        }
    }

    /** 根据输出字段确定备份 MIME，避免把视频当作 PNG 保存。 */
    private String outputMimeType(Map<String, Object> output, boolean video) {
        String declared = String.valueOf(output.getOrDefault("mimeType", output.getOrDefault("content_type", output.getOrDefault("mediaType", "")))).toLowerCase(Locale.ROOT);
        if (declared.contains("/")) return declared;
        if (declared.contains("video") || video) return "video/mp4";
        return "image/png";
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) if (node.path(field).isTextual()) return node.path(field).asText();
        return "";
    }

    /** 只提取安全 HTTP(S) 地址或图片 Base64，拒绝脚本链接和未知内嵌格式。 */
    private List<Map<String, Object>> outputs(JsonNode response, boolean video) {
        List<Map<String, Object>> result = new ArrayList<>();
        collectOutputs(response, video, result, new HashSet<>());
        return result;
    }

    /** 递归读取方舟 content、result、data 等响应容器，兼容对象和数组两种视频结果形态。 */
    private void collectOutputs(JsonNode node, boolean video, List<Map<String, Object>> result, Set<JsonNode> visited) {
        if (node == null || node.isNull() || !visited.add(node)) return;
        if (node.isArray()) { node.forEach(item -> collectOutputs(item, video, result, visited)); return; }
        if (!node.isObject()) return;
        for (String field : List.of("url", "output_url", "video_url", "last_frame_url", "uri")) {
            JsonNode value = node.path(field);
            if (!value.isTextual()) continue;
            URI uri = URI.create(value.asText());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("Invalid media URL");
            Map<String, Object> output = new LinkedHashMap<>(Map.of("kind", "url", "value", value.asText(), "mediaType", field.equals("last_frame_url") || !video ? "image" : "video"));
            copyOutputMetadata(node, output);
            if (!result.contains(output)) result.add(output);
        }
        if (!video && node.path("b64_json").isTextual()) {
            String encoded = node.path("b64_json").asText();
            try {
                if (encoded.isBlank() || encoded.length() > 20 * 1024 * 1024 || Base64.getDecoder().decode(encoded).length == 0)
                    throw new IllegalArgumentException("Invalid image data");
            } catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid image data", exception); }
            Map<String, Object> output = new LinkedHashMap<>(Map.of("kind", "base64", "value", encoded, "mediaType", "image"));
            copyOutputMetadata(node, output);
            if (!result.contains(output)) result.add(output);
        }
        collectInlineData(node.get("inline_data"), video, result);
        collectInlineData(node.get("inlineData"), video, result);
        for (String child : List.of("data", "content", "result", "video", "output")) collectOutputs(node.get(child), video, result, visited);
    }

    /** 读取 Gemini inline_data 和 OpenAI 兼容网关的内嵌媒体字段。 */
    private void collectInlineData(JsonNode node, boolean video, List<Map<String, Object>> result) {
        if (node == null || !node.isObject()) return;
        String encoded = firstText(node, "data", "bytesBase64Encoded", "b64_json");
        if (encoded.isBlank() || video && !node.path("mime_type").asText("").startsWith("video/")) return;
        try {
            if (Base64.getDecoder().decode(encoded).length == 0) return;
        } catch (IllegalArgumentException ignored) { return; }
        Map<String, Object> output = new LinkedHashMap<>(Map.of("kind", "base64", "value", encoded,
            "mediaType", video ? "video" : "image"));
        String mime = firstNonBlank(node.path("mime_type").asText(null), node.path("mimeType").asText(null));
        if (!mime.isBlank()) output.put("mimeType", mime);
        if (!result.contains(output)) result.add(output);
    }

    /** 保留图层位置、层级、名称和格式，支持生成页还原结果语义。 */
    private void copyOutputMetadata(JsonNode node, Map<String, Object> output) {
        for (String field : List.of("name", "description", "z_index", "bounding_box", "size", "output_format", "mimeType", "content_type"))
            if (node.hasNonNull(field)) output.put(field, mapper.convertValue(node.get(field), Object.class));
    }
    /** 提取逐图失败的稳定错误码，不暴露供应商响应正文。 */
    private List<Map<String, String>> outputErrors(JsonNode response) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (response.path("data").isArray()) for (JsonNode node : response.path("data"))
            if (node.hasNonNull("error")) errors.add(Map.of("code", node.path("error").path("code").asText("IMAGE_FAILED")));
        return errors;
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

    /** 保存稳定错误码或有限供应商详情；凭据、提示词和完整响应正文均不进入任务记录。 */
    private void fail(Map<String, Object> task, String errorMessage) {
        int updated = jdbc.update("UPDATE ai_media_generation_task SET status='FAILED',error_message=?,finished_at=NOW(6),updated_at=NOW(6) WHERE id=? AND status='RUNNING'", errorMessage, task.get("id"));
        if (updated > 0) usage.finish((String) task.get("usageId"), new LlmUsageService.Completion("FAILED", (String) task.get("providerCode"),
            (String) task.get("modelName"), false, null, null, null, "MediaGenerationException", stableFailureCode(errorMessage), Instant.now()));
    }

    /** 供应商详情使用稳定前缀，避免用量记录把可变的供应商正文当作错误码。 */
    private String stableFailureCode(String errorMessage) {
        int separator = errorMessage == null ? -1 : errorMessage.indexOf('|');
        return separator > 0 ? errorMessage.substring(0, separator) : errorMessage;
    }

    /** 将供应商错误详情封装为运行时异常，供创建和轮询流程分别处理。 */
    private static final class ProviderRequestException extends RuntimeException {
        private final int status;
        private final String code;
        private final String detail;

        private ProviderRequestException(int status, String code, String detail) {
            super(code + ": " + detail);
            this.status = status;
            this.code = code;
            this.detail = detail;
        }

        /** 返回任务和前端共用的结构化错误文本。 */
        private String failureMessage() { return "PROVIDER_REQUEST_FAILED|HTTP_" + status + "|" + code + "|" + detail; }
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
            statement.setString(7, request.prompt() == null ? "" : request.prompt().trim()); statement.setString(8, json(storageRequest(request))); statement.setString(9, usageId);
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
                    t.output_json outputJson,t.request_json requestJson,t.usage_id usageId,t.status,t.progress,t.error_message errorMessage,
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
        try {
            Object request = result.remove("requestJson");
            if (request != null) {
                JsonNode node = mapper.readTree(request.toString());
                result.put("generationMode", node.path("generationMode").asText(defaultMode((String) result.get("generationType"))));
                result.put("inputCount", node.path("inputs").isArray() ? node.path("inputs").size() : 0);
                result.put("options", node.path("options"));
            } else result.put("generationMode", defaultMode((String) result.get("generationType")));
        } catch (Exception exception) { throw new IllegalStateException("Invalid stored media request", exception); }
        return result;
    }

    /** 在任何调用及用量登记之前验证会话属于当前用户。 */
    private void requireConversation(Long owner, Long id) {
        if (id == null) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_conversation WHERE id=? AND owner_id=?", Integer.class, id, owner);
        if (count == null || count == 0) throw new BusinessException(404, "chat.notFound");
    }

    /** 验证两类生成的请求边界和可透传参数，禁止调用路径、认证等控制参数。 */
    private void validate(GenerateRequest request) { validate(request, null); }

    /** 按供应商画像选择输入上限，通用适配器继续保持历史边界。 */
    private void validate(GenerateRequest request, LlmManagementService.WorkerCandidate candidate) {
        String requestedType = request == null ? "" : request.modelType();
        MediaGenerationAgentStrategy strategy = candidate == null ? null : resolveStrategy(candidate);
        boolean generationType = modelTypeRegistry.supports(requestedType, LlmOperation.IMAGE_GENERATION)
            || modelTypeRegistry.supports(requestedType, LlmOperation.VIDEO_GENERATION);
        String mode = request == null ? "" : normalizedMode(request);
        boolean hasInputs = request != null && request.inputs() != null && !request.inputs().isEmpty();
        String prompt = request == null ? null : request.prompt();
        if (request == null || !generationType
            || request.modelId() == null || request.modelId() <= 0
            || ((prompt == null || prompt.isBlank()) && !hasInputs) || prompt != null && prompt.length() > 100000
            || request.conversationId() != null && request.conversationId() <= 0) throw new BusinessException("llm.generationRequestInvalid");
        if (!validMode(request.modelType(), mode, candidate)) throw new BusinessException("llm.generationRequestInvalid");
        validateInputs(request.inputs(), strategy != null && strategy.supportsRemoteInputs());
        Map<String, Object> options = request.options() == null ? Map.of() : request.options();
        Set<String> allowed = Set.of("size", "quality", "style", "n", "response_format", "sequential_image_generation",
            "sequential_image_generation_options", "stream", "tools", "background", "output_format", "watermark", "seed",
            "optimize_prompt_options", "layer_decomposition", "duration", "aspect_ratio", "resolution", "fps",
            "callback_url", "return_last_frame", "generate_audio", "draft", "safety_identifier", "service_tier", "execution_expires_after",
            "ratio", "frames", "camera_fixed", "omni_reference_task_type");
        if (!allowed.containsAll(options.keySet()) || options.values().stream().anyMatch(value -> value == null || !safeOption(value, 0)))
            throw new BusinessException("llm.generationRequestInvalid");
        for (String field : List.of("n", "duration", "fps")) if (options.containsKey(field)) {
            JsonNode number = mapper.valueToTree(options.get(field));
            int max = field.equals("n") ? 15 : field.equals("duration") ? 60 : 240;
            if (!number.isIntegralNumber() || !number.canConvertToInt() || (number.intValue() < 1 && !(strategy != null && strategy.allowsSpecialDuration() && field.equals("duration") && number.intValue() == -1)) || number.intValue() > max)
                throw new BusinessException("llm.generationRequestInvalid");
        }
    }

    /** 兼容旧请求并为新请求提供明确的生成模式。 */
    private String normalizedMode(GenerateRequest request) {
        return request.generationMode() == null || request.generationMode().isBlank()
            ? defaultMode(request.modelType()) : request.generationMode().trim().toUpperCase(Locale.ROOT);
    }

    /** 接受新的多模态模式，并兼容历史图片或视频生成模式。 */
    private boolean validMode(String modelType, String mode, LlmManagementService.WorkerCandidate candidate) {
        if ("MULTIMODAL".equals(mode)) return true;
        if (candidate != null) {
            MediaGenerationAgentStrategy strategy = resolveStrategy(candidate);
            return strategy.supportsMode(modelType, candidate.model(), profile(candidate), mode);
        }
        if ("image_model".equals(modelType)) return Set.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE", "IMAGE_TEXT_TO_IMAGE",
            "MULTI_IMAGE_FUSION", "TEXT_TO_GROUP", "IMAGE_TO_GROUP", "MULTI_IMAGE_TO_GROUP",
            "LAYER_DECOMPOSITION", "INTERACTIVE_EDIT", "TRANSPARENT_IMAGE").contains(mode);
        if (!"video_model".equals(modelType)) return false;
        if (Set.of("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO", "VIDEO_TO_VIDEO").contains(mode)) return true;
        return false;
    }

    /** 校验文本、图片和视频输入的数量、Data URL 格式和解码后大小，拒绝远程 URL 造成的 SSRF。 */
    private void validateInputs(List<MediaInput> inputs, boolean ark) {
        List<MediaInput> values = inputs == null ? List.of() : inputs;
        if (values.size() > (ark ? 50 : MAX_INPUTS) || values.stream().anyMatch(Objects::isNull))
            throw new BusinessException("llm.generationRequestInvalid");
        for (MediaInput input : values) validateInput(input, ark);
    }

    /** 验证单个上传对象的名称、MIME 类型、Data URL 和实际 Base64 大小。 */
    private void validateInput(MediaInput input, boolean ark) {
        if (input == null || input.kind() == null || input.fileName() == null || input.fileName().isBlank()
            || input.fileName().length() > 200 || input.fileName().contains("/") || input.fileName().contains("\\")
            || input.contentType() == null || input.dataUrl() == null || input.role() == null || input.role().length() > 64) throw new BusinessException("llm.generationRequestInvalid");
        Set<String> allowed = switch (input.kind()) {
            case "TEXT" -> Set.of("text/plain", "text/markdown");
            case "IMAGE" -> Set.of("image/png", "image/jpeg", "image/webp", "image/bmp", "image/tiff", "image/gif", "image/heic", "image/heif");
            case "VIDEO" -> Set.of("video/mp4", "video/webm", "video/quicktime", "video/x-m4v");
            case "AUDIO" -> Set.of("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav");
            case "TASK" -> Set.of("text/plain");
            default -> Set.of();
        };
        if (!allowed.contains(input.contentType().toLowerCase(Locale.ROOT))) throw new BusinessException("llm.generationRequestInvalid");
        if (ark && "TASK".equals(input.kind()) && input.dataUrl().matches("[A-Za-z0-9_-]{1,200}")) return;
        String prefix = "data:" + input.contentType().toLowerCase(Locale.ROOT) + ";base64,";
        boolean remote = input.dataUrl().startsWith("http://") || input.dataUrl().startsWith("https://");
        if (remote) {
            if (!ark || Set.of("TEXT", "TASK").contains(input.kind())) throw new BusinessException("llm.generationRequestInvalid");
            try {
                URI uri = URI.create(input.dataUrl());
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (Exception exception) { throw new BusinessException("llm.generationRequestInvalid"); }
            return;
        }
        if (!input.dataUrl().startsWith(prefix)) throw new BusinessException("llm.generationRequestInvalid");
        String encoded = input.dataUrl().substring(prefix.length());
        int maxBytes = switch (input.kind()) {
            case "TEXT" -> MAX_TEXT_BYTES;
            case "IMAGE" -> ark ? ARK_MAX_IMAGE_BYTES : MAX_IMAGE_BYTES;
            case "AUDIO" -> ark ? ARK_MAX_AUDIO_BYTES : MAX_VIDEO_BYTES;
            default -> ark ? ARK_MAX_VIDEO_BYTES : MAX_VIDEO_BYTES;
        };
        if (encoded.isBlank() || encoded.length() > ((maxBytes + 2) / 3) * 4 || !encoded.matches("[A-Za-z0-9+/]+=*"))
            throw new BusinessException("llm.generationRequestInvalid");
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length == 0 || decoded.length > maxBytes) throw new BusinessException("llm.generationRequestInvalid");
        } catch (IllegalArgumentException exception) { throw new BusinessException("llm.generationRequestInvalid"); }
    }

    /** 递归限制供应商参数结构，允许方舟的对象、数组和布尔字段但拒绝超长注入内容。 */
    private boolean safeOption(Object value, int depth) {
        if (depth > 4 || value == null) return false;
        if (value instanceof String text) return text.length() <= 10000;
        if (value instanceof Number || value instanceof Boolean) return true;
        if (value instanceof Map<?, ?> map) return map.size() <= 32 && map.entrySet().stream()
            .allMatch(entry -> entry.getKey() instanceof String key && key.length() <= 80 && safeOption(entry.getValue(), depth + 1));
        if (value instanceof Collection<?> collection) return collection.size() <= 50 && collection.stream().allMatch(item -> safeOption(item, depth + 1));
        return false;
    }

    /** 任务只保存输入元数据，避免数据库持久化大段 Base64；供应商调用使用当前请求内容。 */
    private Map<String, Object> storageRequest(GenerateRequest request) {
        List<Map<String, Object>> metadata = (request.inputs() == null ? List.<MediaInput>of() : request.inputs()).stream().map(input -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", input.kind()); value.put("name", input.fileName()); value.put("contentType", input.contentType());
            value.put("role", input.role());
            value.put("bytes", input.dataUrl() == null ? 0 : Math.max(0, (input.dataUrl().length() - input.dataUrl().indexOf(',') - 1) * 3 / 4));
            return value;
        }).toList();
        return Map.of("generationMode", normalizedMode(request), "inputs", metadata, "options", request.options() == null ? Map.of() : request.options());
    }

    /** 返回历史请求的默认模式。 */
    private String defaultMode(String modelType) { return "video_model".equals(modelType) ? "TEXT_TO_VIDEO" : "TEXT_TO_IMAGE"; }

    /** 校验模型声明了请求的图片或视频生成能力，防止通过参数篡改互相调用。 */
    private LlmManagementService.WorkerCandidate candidate(Long modelId, String type) {
        var route = management.resolveModel(modelId, type, false, null);
        if (route == null || route.candidates() == null || route.candidates().size() != 1) throw new BusinessException("llm.modelTypeUnsupported");
        var candidate = route.candidates().get(0);
        boolean supported = modelTypeRegistry.supports(type, LlmOperation.IMAGE_GENERATION)
            || modelTypeRegistry.supports(type, LlmOperation.VIDEO_GENERATION);
        if (!supported || candidate == null || candidate.supportedModelTypes() == null || !candidate.supportedModelTypes().contains(type) || candidate.apiKeys() == null || candidate.apiKeys().isEmpty())
            throw new BusinessException("llm.modelTypeUnsupported");
        return candidate;
    }

    /** 序列化任务输出，失败时不泄露请求正文。 */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize media task", exception); }
    }

    public record GenerateRequest(String modelType, Long modelId, String prompt, Long conversationId,
                                  String generationMode, List<MediaInput> inputs, Map<String, Object> options) {
        /** 兼容原有纯 prompt 调用方。 */
        public GenerateRequest(String modelType, Long modelId, String prompt, Long conversationId, Map<String, Object> options) {
            this(modelType, modelId, prompt, conversationId, null, List.of(), options);
        }
    }

    /** 统一媒体输入；前端只提交受控 Data URL，不接受任意远程地址。 */
    public record MediaInput(String kind, String fileName, String contentType, String dataUrl, String role) {
        /** 兼容历史四字段请求，默认没有供应商角色。 */
        public MediaInput(String kind, String fileName, String contentType, String dataUrl) { this(kind, fileName, contentType, dataUrl, ""); }
    }
    public record ModelOption(Long id, String name, String modelName, String modelType, String providerCode, String providerName,
                              String mediaProtocol, Map<String, Object> capabilities) {
        /** 兼容仅关注模型基本信息的旧构造方。 */
        public ModelOption(Long id, String name, String modelName, String modelType, String providerCode, String providerName) {
            this(id, name, modelName, modelType, providerCode, providerName, "GENERIC", Map.of());
        }
    }
}
