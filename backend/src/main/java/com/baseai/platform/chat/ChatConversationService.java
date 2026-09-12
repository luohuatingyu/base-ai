package com.baseai.platform.chat;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.trace.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.*;

/** 以短事务管理会话和生成租约，不在模型调用期间持有数据库事务。 */
@Service
public class ChatConversationService {
    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    /** 注入存储与事务管理器。 */
    public ChatConversationService(ChatConversationRepository conversations, ChatMessageRepository messages,
                                   ObjectMapper mapper, PlatformTransactionManager manager) {
        this.conversations = conversations;
        this.messages = messages;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 创建仅属于当前用户的空会话。 */
    public Summary create(Long owner) {
        return transaction.execute(status -> {
            ChatConversation conversation = new ChatConversation();
            conversation.ownerId = owner;
            return summary(conversations.saveAndFlush(conversation));
        });
    }

    /** 返回分页摘要，避免列表加载图片和消息正文。 */
    public Map<String, Object> list(Long owner, int page) {
        if (page < 0 || page > 100000) throw new BusinessException("chat.invalidInput");
        var result = conversations.findByOwnerIdOrderByUpdatedAtDescIdDesc(owner, PageRequest.of(page, 20));
        return Map.of("items", result.getContent().stream().map(this::summary).toList(),
            "total", result.getTotalElements(), "page", page);
    }

    /** 读取完整历史，并恢复进程异常留下的过期生成租约。 */
    public Detail detail(Long owner, Long id) {
        return transaction.execute(status -> {
            ChatConversation conversation = owned(owner, id);
            recover(conversation);
            return new Detail(summary(conversation), parse(conversation.settingsJson),
                messages.findByConversationIdOrderByIdAsc(id).stream().map(this::view).toList());
        });
    }

    /** 删除空闲会话及关联消息，生成时拒绝删除。 */
    public void delete(Long owner, Long id) {
        transaction.executeWithoutResult(status -> {
            ChatConversation conversation = owned(owner, id);
            recover(conversation);
            if (conversation.activeMessageId != null) throw new BusinessException(409, "chat.busy");
            messages.deleteByConversationId(id);
            messages.flush();
            conversations.delete(conversation);
        });
    }

    /** 原子防重并登记用户消息和助手占位，服务器组装可信上下文。 */
    public Turn begin(Long owner, Long id, SendRequest request) {
        validate(request);
        return transaction.execute(status -> {
            ChatConversation conversation = owned(owner, id);
            recover(conversation);
            if (conversation.activeMessageId != null) throw new BusinessException(409, "chat.busy");
            if (messages.existsByConversationIdAndRequestId(id, request.requestId()))
                throw new BusinessException(409, "chat.duplicate");
            List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(id);
            if (history.size() >= 200) throw new BusinessException(400, "chat.limit");
            Set<String> completed = new HashSet<>();
            history.stream().filter(message -> "assistant".equals(message.role) && "COMPLETED".equals(message.status))
                .forEach(message -> completed.add(message.requestId));
            List<AiChatClient.Message> context = new ArrayList<>();
            if (request.settings().systemPrompt() != null && !request.settings().systemPrompt().isBlank())
                context.add(new AiChatClient.Message("system", request.settings().systemPrompt()));
            history.stream().filter(message -> completed.contains(message.requestId)).forEach(message ->
                context.add(new AiChatClient.Message(message.role, parse(message.contentJson))));
            context.add(new AiChatClient.Message("user", request.content()));
            if (context.size() > 100 || json(context).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
                throw new BusinessException(413, "chat.limit");
            ChatMessage user = message(id, request.requestId(), "user", request.content(), "COMPLETED");
            messages.save(user);
            ChatMessage assistant = messages.saveAndFlush(message(id, request.requestId(), "assistant", mapper.valueToTree(""), "GENERATING"));
            conversation.activeMessageId = assistant.id;
            conversation.leaseUntil = Instant.now().plusSeconds(45);
            conversation.updatedAt = Instant.now();
            conversation.settingsJson = json(request.settings());
            if (conversation.title.isEmpty()) {
                String title = request.content().isTextual() ? request.content().asText() :
                    request.content().findValuesAsText("text").stream().findFirst().orElse("…");
                conversation.title = title.strip().substring(0, Math.min(title.strip().length(), 100));
            }
            return new Turn(owner, id, assistant.id, List.copyOf(context), request.settings());
        });
    }

    /** 周期保存部分内容并刷新租约，终态同时释放会话。 */
    public void save(Turn turn, String content, String state, JsonNode metadata) {
        transaction.executeWithoutResult(status -> {
            ChatConversation conversation = owned(turn.owner(), turn.conversationId());
            if (!Objects.equals(conversation.activeMessageId, turn.messageId())) return;
            ChatMessage assistant = messages.findById(turn.messageId()).orElseThrow();
            assistant.contentJson = json(content);
            assistant.status = state;
            if (metadata != null) assistant.metadataJson = json(metadata);
            conversation.updatedAt = Instant.now();
            if ("GENERATING".equals(state)) conversation.leaseUntil = Instant.now().plusSeconds(45);
            else {
                conversation.activeMessageId = null;
                conversation.leaseUntil = null;
            }
        });
    }

    /** 校验输入和多模态 Data URL，限制持久数据及上游上下文大小。 */
    private void validate(SendRequest request) {
        if (request == null || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{1,64}")
            || request.settings() == null || request.content() == null) throw new BusinessException("chat.invalidInput");
        Settings settings = request.settings();
        if (settings.systemPrompt() != null && settings.systemPrompt().length() > 100000
            || settings.modelType() == null || !settings.modelType().matches("[A-Za-z0-9_-]{1,64}")
            || settings.featureCode() != null && settings.featureCode().length() > 100
            || settings.modelId() != null && settings.modelId() <= 0
            || settings.thinkingLevel() != null && !Set.of("LOW", "MEDIUM", "HIGH", "EXTRA_HIGH", "MAX", "ULTRA").contains(settings.thinkingLevel()))
            throw new BusinessException("chat.invalidInput");
        JsonNode content = request.content();
        if (content.isTextual()) { validateText(content.asText()); return; }
        if (!content.isArray() || content.isEmpty() || content.size() > 5) throw new BusinessException("chat.invalidInput");
        int images = 0;
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) validateText(part.path("text").asText());
            else if ("image_url".equals(part.path("type").asText()) && "vision_model".equals(settings.modelType())) {
                String url = part.path("image_url").path("url").asText();
                if (++images > 4 || url.length() > 14 * 1024 * 1024
                    || !url.startsWith("data:image/") || !url.substring(0, Math.min(url.length(), 40)).matches("data:image/(png|jpeg|webp);base64,.*"))
                    throw new BusinessException("chat.invalidInput");
                try {
                    int bytes = Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1)).length;
                    if (bytes == 0 || bytes > 10 * 1024 * 1024) throw new BusinessException("chat.invalidInput");
                }
                catch (IllegalArgumentException exception) { throw new BusinessException("chat.invalidInput"); }
            } else throw new BusinessException("chat.invalidInput");
        }
    }

    /** 空白和超长文本不能进入历史。 */
    private void validateText(String text) {
        if (text.isBlank() || text.length() > 100000) throw new BusinessException("chat.invalidInput");
    }

    /** 按所有者锁定，不区分不存在与越权，避免泄漏资源存在性。 */
    private ChatConversation owned(Long owner, Long id) {
        return conversations.lockOwned(id, owner).orElseThrow(() -> new BusinessException(404, "chat.notFound"));
    }

    /** 过期租约保留部分结果并标记中断。 */
    private void recover(ChatConversation conversation) {
        if (conversation.activeMessageId != null && (conversation.leaseUntil == null || conversation.leaseUntil.isBefore(Instant.now()))) {
            messages.findById(conversation.activeMessageId).ifPresent(message -> message.status = "INTERRUPTED");
            conversation.activeMessageId = null;
            conversation.leaseUntil = null;
        }
    }

    /** 构建消息并关联当前任务追踪。 */
    private ChatMessage message(Long id, String requestId, String role, JsonNode content, String status) {
        ChatMessage message = new ChatMessage();
        message.conversationId = id; message.requestId = requestId; message.role = role;
        message.contentJson = json(content); message.status = status;
        message.traceId = TraceContextHolder.currentTraceId().orElse(null);
        return message;
    }

    /** 隔离实体存储格式与前端消息协议。 */
    private MessageView view(ChatMessage message) {
        return new MessageView(message.id, message.role, parse(message.contentJson), message.status,
            message.metadataJson == null ? null : parse(message.metadataJson), message.traceId);
    }

    /** 列表摘要不暴露所属用户编号及内部租约。 */
    private Summary summary(ChatConversation conversation) {
        return new Summary(conversation.id, conversation.title, conversation.updatedAt,
            conversation.activeMessageId != null && conversation.leaseUntil != null && conversation.leaseUntil.isAfter(Instant.now()));
    }

    /** 统一序列化数据库 JSON。 */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize chat", exception); }
    }

    /** 读取数据库 JSON，损坏时阻止继续构造上下文。 */
    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored chat", exception); }
    }

    public record Settings(String modelType, String featureCode, Long modelId, Boolean enableThinking,
                           String thinkingLevel, String systemPrompt) {}
    public record SendRequest(String requestId, JsonNode content, Settings settings) {}
    public record Summary(Long id, String title, Instant updatedAt, boolean generating) {}
    public record MessageView(Long id, String role, JsonNode content, String status, JsonNode metadata, String traceId) {}
    public record Detail(Summary conversation, JsonNode settings, List<MessageView> messages) {}
    public record Turn(Long owner, Long conversationId, Long messageId, List<AiChatClient.Message> context, Settings settings) {}
}
