package com.baseai.platform.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.trace.TraceContextHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

/**
 * AI聊天客户端服务
 * <p>
 * 负责与Python Worker服务进行通信，调用大语言模型进行对话。
 * 支持模型路由管理，可根据功能特性码选择合适的模型，并提供完整的调用链路追踪功能。
 * </p>
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>调用Python Worker的LLM接口进行对话</li>
 *   <li>支持通过功能特性码进行模型路由选择</li>
 *   <li>提供调用链路追踪和状态记录</li>
 *   <li>处理模型调用异常并转换为业务异常</li>
 * </ul>
 *
 * @author baseai
 * @version 1.0
 * @since 1.0
 */
@Service
public class AiChatClient {
    /** Python Worker服务的REST客户端 */
    private final RestClient restClient;

    /** 任务追踪服务，用于记录调用链路 */
    private final TaskTraceService taskTraceService;

    /** LLM管理服务，用于模型路由解析 */
    private final LlmManagementService llmManagementService;

    /**
     * 构造函数，通过依赖注入初始化所需服务
     *
     * @param restClient Python Worker服务的REST客户端，使用pythonWorkerRestClient限定符注入
     * @param taskTraceService 任务追踪服务实例
     * @param llmManagementService LLM管理服务实例
     */
    public AiChatClient(@Qualifier("pythonWorkerRestClient") RestClient restClient, TaskTraceService taskTraceService,
                        LlmManagementService llmManagementService) {
        this.restClient = restClient;
        this.taskTraceService = taskTraceService;
        this.llmManagementService = llmManagementService;
    }

    /**
     * 调用大语言模型进行对话
     * <p>
     * 该方法通过Python Worker服务调用LLM模型，支持模型管理路由功能。
     * 优先使用通过功能特性码配置的模型路由，如果未配置则回退到Worker的默认模型池。
     * 整个调用过程会被完整追踪，包括生成Python侧的追踪ID并记录调用状态。
     * </p>
     *
     * <p>调用流程：</p>
     * <ol>
     *   <li>设置追踪检查点</li>
     *   <li>标准化功能特性码</li>
     *   <li>解析模型路由配置</li>
     *   <li>生成Python追踪ID并注册追踪记录</li>
     *   <li>发送HTTP请求到Python Worker</li>
     *   <li>处理响应并更新追踪状态</li>
     * </ol>
     *
     * @param featureCode 功能特性码，用于模型路由选择。如果为空或空白，默认使用"chat"
     * @param modelType 模型类型，指定要使用的模型类别。如果为空或空白，默认使用"text_model"
     * @param messages 对话消息列表，包含角色和内容
     * @param temperature 温度参数，控制模型输出的随机性。如果为null，默认使用0.0（确定性输出）
     * @param enableThinking 是否启用思考模式。如果为null，使用路由配置的默认值
     * @param thinkingLevel 思考级别（LOW/MEDIUM/HIGH/EXTRA_HIGH/MAX/ULTRA）。仅在enableThinking为true时有效
     * @return ChatResult 对话结果，包含生成的内容、使用的模型名称和token统计信息
     * @throws BusinessException 当模型服务返回空响应或调用失败时抛出业务异常
     */
    public ChatResult chat(String featureCode, String modelType, List<Message> messages, Double temperature,
                          Boolean enableThinking, String thinkingLevel, Long modelId) {
        // 设置追踪检查点，记录当前执行位置
        TraceContextHolder.checkpoint();

        // 标准化功能特性码：如果为空或空白则使用默认值"chat"
        String normalizedFeature = featureCode == null || featureCode.isBlank() ? "chat" : featureCode.trim();
        String normalizedModelType = modelType == null || modelType.isBlank() ? "text_model" : modelType;

        // 解析路由配置：指定了 modelId 走单模型直连，否则走按类型筛选后的能力路由
        LlmManagementService.WorkerRoute route = modelId==null
            ?llmManagementService.resolveActive(normalizedFeature,normalizedModelType)
            :llmManagementService.resolveModel(modelId,normalizedModelType,Boolean.TRUE.equals(enableThinking),thinkingLevel);

        // 获取当前追踪ID作为父追踪ID
        String parentTraceId = TraceContextHolder.currentTraceId().orElse(null);

        // 生成Python侧的追踪ID（UUID去掉横线）
        String pythonTraceId = UUID.randomUUID().toString().replace("-", "");

        // 注册Python调用追踪记录，关联父追踪ID
        taskTraceService.registerPython(parentTraceId, pythonTraceId, "/llm/chat");

        // 确定最终的思考模式开关：优先使用请求参数，其次使用路由配置
        Boolean finalEnableThinking = enableThinking != null ? enableThinking : route.enableThinking();

        try {
            // 构造请求并发送到Python Worker的/llm/chat接口
            ChatResult result = restClient.post().uri("/llm/chat")
                .header("X-Python-Trace-Id", pythonTraceId)  // 传递Python追踪ID用于链路追踪
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatRequest(normalizedFeature, normalizedModelType,
                    messages, temperature == null ? 0D : temperature, route.candidates(), finalEnableThinking,
                    thinkingLevel, route.routeConfigured())).retrieve().body(ChatResult.class);

            // 验证响应不为空
            if (result == null) throw new BusinessException("模型服务返回空响应");

            // 更新追踪记录为成功状态
            taskTraceService.updatePython(pythonTraceId, "SUCCESS", null, null);

            // 设置追踪检查点，记录返回前的位置
            TraceContextHolder.checkpoint();

            return result;
        } catch (RestClientException exception) {
            // 根据线程中断状态判断是取消还是失败
            taskTraceService.updatePython(pythonTraceId, Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED", null, exception.getMessage());

            // 将REST客户端异常转换为业务异常，返回502状态码
            throw new BusinessException(502, "模型服务调用失败");
        }
    }

    /**
     * 对话消息记录
     * <p>
     * 表示LLM对话中的一条消息，包含角色（如user、assistant、system）和消息内容。
     * </p>
     *
     * @param role 消息角色，如"user"（用户）、"assistant"（助手）、"system"（系统）
     * @param content 消息内容文本
     */
    public record Message(String role, String content) {}

    /**
     * LLM对话请求对象
     * <p>
     * 封装发送给Python Worker的完整请求参数，包括功能特性、模型配置、对话历史等。
     * </p>
     *
     * @param featureCode 功能特性码，用于模型路由
     * @param modelType 模型类型，如"text_model"
     * @param messages 对话消息列表
     * @param temperature 温度参数，控制输出随机性
     * @param candidates 候选模型列表，由模型管理服务提供
     * @param enableThinking 是否启用思维链（chain-of-thought）功能
     * @param thinkingLevel 思考级别（LOW/MEDIUM/HIGH/EXTRA_HIGH/MAX/ULTRA）
     * @param routeConfigured 路由是否已配置
     */
    public record ChatRequest(String featureCode, @JsonProperty("model_type") String modelType, List<Message> messages, double temperature,
                              List<LlmManagementService.WorkerCandidate> candidates, Boolean enableThinking, String thinkingLevel, boolean routeConfigured) {}

    /**
     * LLM对话结果对象
     * <p>
     * 封装从Python Worker返回的对话结果，包括生成的内容和token使用统计。
     * </p>
     *
     * @param content 模型生成的对话内容
     * @param model 实际使用的模型名称
     * @param inputTokens 输入消息使用的token数量
     * @param outputTokens 输出内容使用的token数量
     * @param totalTokens 总token使用量（输入+输出）
     */
    public record ChatResult(String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
