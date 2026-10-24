package com.baseai.platform.controller;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.AiChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 提供 AI 剧本生成接口，复用文本模型路由并返回结构化剧本正文。 */
@RestController
@RequestMapping("/api/ai/script")
@RequiredPermission("ai:script:invoke")
public class AiScriptController {
    private static final String SYSTEM_PROMPT = """
        你是影视编剧和分镜策划。请根据用户创意生成可直接用于 AI 视频制作的剧本。
        只返回一个合法 JSON 对象，不要 Markdown 代码围栏，不要额外解释。JSON 结构必须是：
        {"title":"剧本标题","logline":"一句话梗概","synopsis":"完整故事梗概","scenes":[{"title":"场景标题","sceneDescription":"画面主体、环境、动作、光线和构图","dialogue":"角色名：对白，没有对白时为空字符串","narration":"旁白，没有旁白时为空字符串","camera":"景别和镜头运动","durationMs":5000}]}
        scenes 至少包含用户要求的数量；durationMs 使用整数毫秒，范围 1000 到 60000。
        内容应具体、可拍摄，保持人物和场景前后一致，避免生成危险、违法或侵犯现实人物隐私的内容。
        """;

    private final AiChatClient client;

    /** 注入统一文本模型客户端。 */
    public AiScriptController(AiChatClient client) {
        this.client = client;
    }

    /** 根据创作约束调用文本模型，保留模型名称和 Token 统计便于页面反馈。 */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody ScriptRequest request) {
        if (request == null) throw new BusinessException("llm.generationRequestInvalid");
        String brief = required(request.brief(), 10000);
        int sceneCount = request.sceneCount() == null ? 6 : request.sceneCount();
        if (sceneCount < 1 || sceneCount > 24) throw new BusinessException("llm.generationRequestInvalid");
        String userPrompt = "请生成一个适合 AI 视频制作的剧本。\n"
            + "主题/创意：" + brief + "\n"
            + "类型：" + optional(request.genre(), 80, "未指定") + "\n"
            + "风格：" + optional(request.tone(), 120, "自然、电影感") + "\n"
            + "目标语言：" + optional(request.language(), 40, "简体中文") + "\n"
            + "目标时长：" + (request.durationSeconds() == null ? 60 : request.durationSeconds()) + " 秒\n"
            + "场景数量：" + sceneCount + " 个。";
        AiChatClient.ChatResult result = client.chat(
            "chat", "text_model", List.of(new AiChatClient.Message("system", SYSTEM_PROMPT),
                new AiChatClient.Message("user", userPrompt)), 0.4D, null, null, null,
            "AI_SCRIPT", AuthContext.require().id(), null);
        return Map.of("content", result.content(), "model", result.model(),
            "inputTokens", result.inputTokens(), "outputTokens", result.outputTokens(),
            "totalTokens", result.totalTokens());
    }

    /** 校验并截断用户输入，避免把超长内容发送给模型或写入追踪日志。 */
    private String required(String value, int max) {
        String normalized = optional(value, max, "");
        if (normalized.isBlank()) throw new BusinessException("llm.generationRequestInvalid");
        return normalized;
    }

    /** 统一处理可选文本字段，避免空值和超长输入改变提示词结构。 */
    private String optional(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().substring(0, Math.min(max, value.trim().length()));
    }

    /** AI 剧本生成请求，只接受创作约束，不允许前端提交系统提示词。 */
    public record ScriptRequest(String brief, String genre, String tone, String language,
                                Integer durationSeconds, Integer sceneCount) {}
}
