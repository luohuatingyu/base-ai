package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 覆盖媒体生成入口的类型隔离、输入边界和模型目录脱敏行为。 */
class MediaGenerationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LlmManagementService management = mock(LlmManagementService.class);
    private final LlmUsageService usage = mock(LlmUsageService.class);
    private final MediaGenerationService service = new MediaGenerationService(jdbc, management, usage, new ObjectMapper());

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    /** 未登录和非法模型类型在建立用量记录或访问供应商前被拒绝。 */
    @Test
    void rejectsUnauthenticatedAndMixedTypeRequests() {
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "a cat", null, Map.of())));
        AuthContext.set(new AuthUser(1L, "user", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "text_model", 1L, "a cat", null, Map.of())));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "a cat", null, Map.of("duration", 5))));
    }

    /** 不接受超长提示词、非正数或未知控制参数，防止请求绕过供应商隔离。 */
    @Test
    void rejectsBoundaryAndUnknownOptions() {
        AuthContext.set(new AuthUser(1L, "user", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "video_model", 1L, " ", null, Map.of())));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "video_model", 1L, "video", null, Map.of("fps", 121))));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "video_model", 1L, "video", null, Map.of("endpoint", "/internal"))));
    }

    /** 生成模型目录按输出类型展开组合模型，不能把视觉模型误展示为生成模型。 */
    @Test
    void generationModelDirectoryIsTypeSpecific() {
        when(management.providers()).thenReturn(List.of(new LlmManagementService.ProviderView(
            2L, "provider", "Provider", "https://provider.example", "******", 4, "PROVIDER", 60,
            "reasoning_effort", true)));
        var image = new com.baseai.platform.domain.LlmModel();
        image.setProviderId(2L); image.setName("image"); image.setModelName("image-1");
        image.setEnabled(true); image.setSupportedModelTypes(List.of("image_model"));
        var mixed = new com.baseai.platform.domain.LlmModel();
        mixed.setProviderId(2L); mixed.setName("mixed"); mixed.setModelName("mixed");
        mixed.setEnabled(true); mixed.setSupportedModelTypes(List.of("image_model", "video_model"));
        var text = new com.baseai.platform.domain.LlmModel();
        text.setProviderId(2L); text.setName("text"); text.setModelName("deepseek-chat");
        text.setEnabled(true); text.setSupportedModelTypes(List.of("text_model"));
        when(management.models()).thenReturn(List.of(image, mixed, text));
        assertEquals(List.of("image:image_model", "mixed:image_model", "mixed:video_model"), service.models().stream()
            .map(item -> item.name() + ":" + item.modelType()).toList());
    }

    /** 模型类型勾选是平台配置权威，画像不匹配时仍保留图片和视频两类能力目录。 */
    @Test
    void arkDirectoryHonorsConfiguredOutputTypes() {
        when(management.providers()).thenReturn(List.of(new LlmManagementService.ProviderView(
            2L, "provider", "Provider", "https://provider.example", "******", 4, "PROVIDER", 60,
            "reasoning_effort", true)));
        var seedance = new com.baseai.platform.domain.LlmModel();
        seedance.setProviderId(2L); seedance.setName("seedance"); seedance.setModelName("doubao-seedance-2-0-mini-260615");
        seedance.setGenerationProfile("seedance-2.0-mini"); seedance.setEnabled(true);
        seedance.setSupportedModelTypes(List.of("image_model", "video_model"));
        when(management.models()).thenReturn(List.of(seedance));
        var entries = service.models().stream().filter(item -> "seedance".equals(item.name())).toList();
        assertEquals(List.of("image_model", "video_model"), entries.stream()
            .map(MediaGenerationService.ModelOption::modelType).toList());
        assertTrue(entries.stream().allMatch(item -> !item.capabilities().get("modes").toString().equals("[]")));
    }

    /** 图片模型支持无附件提示词，也支持文本、图片和视频输入组合。 */
    @Test
    void acceptsImageGenerationModesAndInputs() throws Exception {
        var image = imageInput();
        var text = textInput();
        var video = videoInput();
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "cat", null, "MULTIMODAL", List.of(), Map.of())));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, null, null, "MULTIMODAL", List.of(text, image, video), Map.of())));
    }

    /** 命中 OpenAI 策略后只接受该策略声明的模式，不能用旧方舟模式绕过能力目录。 */
    @Test
    void strategyControlsGenerationModes() throws Exception {
        var candidate = new LlmManagementService.WorkerCandidate("openai", "https://api.openai.com/v1",
            List.of("key"), "gpt-image-2", 1, "PROVIDER", 60, "reasoning_effort", "", List.of("image_model"), "");
        var image = imageInput();
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "cat", null,
            "IMAGE_TO_IMAGE", List.of(image), Map.of()), candidate));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "cat", null,
            "IMAGE_TEXT_TO_IMAGE", List.of(image), Map.of()), candidate));
    }

    /** 视频模型同样支持文本、图片和视频输入组合，输入类型不再由生成结果类型限制。 */
    @Test
    void acceptsVideoImageOrVideoInputs() throws Exception {
        var image = imageInput();
        var text = textInput();
        var video = videoInput();
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("video_model", 1L, "animate", null, "MULTIMODAL", List.of(image, text, video), Map.of())));
    }

    /** 上传内容必须使用受控 Data URL，并限制类型、名称和解码后大小。 */
    @Test
    void rejectsUnsafeGenerationInputs() throws Exception {
        var image = imageInput();
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "image", null, "IMAGE_TO_IMAGE", List.of(new MediaGenerationService.MediaInput("IMAGE", "../x.png", "image/png", image.dataUrl())), Map.of())));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "image", null, "IMAGE_TO_IMAGE", List.of(new MediaGenerationService.MediaInput("IMAGE", "x.png", "image/png", "https://example.com/x.png")), Map.of())));
    }

    /** 各参考媒体模式中的空元素均返回业务校验错误，不触发空指针异常。 */
    @Test
    void rejectsNullMediaElementsAsBusinessErrors() {
        for (String type : List.of("image_model", "video_model")) {
            assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(
                type, 1L, "reference", null, "MULTIMODAL", java.util.Collections.singletonList(null), Map.of())), type);
        }
    }

    /** 两类输出均接收全部非空输入组合，并保留旧纯文本和模式字段兼容性。 */
    @ParameterizedTest
    @ValueSource(strings = {"image_model", "video_model"})
    void acceptsAllInputCombinationsAndLegacyRequests(String type) {
        var all = List.of(textInput(), imageInput(), videoInput());
        for (int mask = 1; mask < 8; mask++) {
            var inputs = new java.util.ArrayList<MediaGenerationService.MediaInput>();
            for (int index = 0; index < 3; index++) if ((mask & (1 << index)) != 0) inputs.add(all.get(index));
            for (String prompt : new String[]{null, "", "instructions"})
                assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest(type, 1L, prompt, null,
                    "MULTIMODAL", inputs, Map.of())));
        }
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest(type, 1L, "legacy", null, Map.of())));
        String legacyMode = "image_model".equals(type) ? "IMAGE_TO_IMAGE" : "VIDEO_TO_VIDEO";
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest(type, 1L, "legacy", null,
            legacyMode, all, Map.of())));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest(type, 1L, "", null,
            "MULTIMODAL", java.util.Collections.nCopies(4, textInput()), Map.of())));
    }

    /** 空请求、越界数量、未知种类、MIME 错配和非法 Base64 均在供应商调用前拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"image_model", "video_model"})
    void rejectsInvalidCombinedInputs(String type) {
        for (String prompt : new String[]{null, "", " "})
            assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(type, 1L,
                prompt, null, "MULTIMODAL", List.of(), Map.of())));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(type, 1L,
            "", null, "MULTIMODAL", java.util.Collections.nCopies(5, textInput()), Map.of())));
        for (var invalid : List.of(
            new MediaGenerationService.MediaInput("UNKNOWN", "file.mp4", "video/mp4", videoInput().dataUrl()),
            new MediaGenerationService.MediaInput("TEXT", "file.txt", "video/mp4", videoInput().dataUrl()),
            new MediaGenerationService.MediaInput("TEXT", "file.txt", "text/html", "data:text/html;base64,YQ=="),
            new MediaGenerationService.MediaInput("TEXT", "file.txt", "text/plain", "data:text/plain;base64,"),
            new MediaGenerationService.MediaInput("TEXT", "file.txt", "text/plain", "data:text/plain;base64,A"),
            new MediaGenerationService.MediaInput("TEXT", "file.txt", "text/plain", "data:text/plain;base64,***"))) {
            assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(type, 1L,
                "", null, "MULTIMODAL", List.of(invalid), Map.of())));
        }
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(type, 1L,
            "prompt", null, "INVALID", List.of(), Map.of())));
    }

    /** 图片和视频模式不能互相接受对方的供应商任务模式。 */
    @Test
    void rejectsCrossMediaGenerationModes() {
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "prompt", null, "TEXT_TO_VIDEO", List.of(), Map.of())));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(
            "video_model", 1L, "prompt", null, "TEXT_TO_IMAGE", List.of(), Map.of())));
    }

    /** 图片和视频日志只保存可访问的素材 URL，绝不把 Data URL 或素材正文写入任务记录。 */
    @ParameterizedTest
    @ValueSource(strings = {"image_model", "video_model"})
    @SuppressWarnings("unchecked")
    void storesOnlyMaterialUrlsInGenerationLog(String type) throws Exception {
        var method = MediaGenerationService.class.getDeclaredMethod("storageRequest", MediaGenerationService.GenerateRequest.class);
        method.setAccessible(true);
        var request = new MediaGenerationService.GenerateRequest(type, 1L, "a detailed prompt", null, "MULTIMODAL", List.of(
            new MediaGenerationService.MediaInput("IMAGE", "remote.png", "image/png", "https://cdn.example/remote.png", "reference_image"),
            new MediaGenerationService.MediaInput("IMAGE", "inline.png", "image/png", "data:image/png;base64,c2VjcmV0", "reference_image")
        ), Map.of("size", "2K"));

        Map<String, Object> stored = (Map<String, Object>) method.invoke(service, request);

        assertEquals(List.of("https://cdn.example/remote.png"), stored.get("materialUrls"));
        assertEquals("MULTIMODAL", stored.get("generationMode"));
        assertTrue(stored.toString().contains("a detailed prompt") == false);
        assertTrue(!stored.toString().contains("c2VjcmV0"));
    }

    /** 历史任务接口返回提示词和素材 URL，但不泄露内部 request_json 或 Data URL。 */
    @Test
    @SuppressWarnings("unchecked")
    void exposesPromptAndMaterialUrlsInHistory() throws Exception {
        var method = MediaGenerationService.class.getDeclaredMethod("publicTask", Map.class);
        method.setAccessible(true);
        var task = new java.util.LinkedHashMap<String, Object>();
        task.put("id", 7L);
        task.put("generationType", "video_model");
        task.put("prompt", "camera moves through a rainy city");
        task.put("outputJson", "{\"outputs\":[]}");
        task.put("requestJson", "{\"generationMode\":\"IMAGE_TO_VIDEO\",\"inputCount\":1,\"materialUrls\":[\"https://cdn.example/frame.png\"],\"options\":{\"duration\":5}}");

        Map<String, Object> result = (Map<String, Object>) method.invoke(service, task);

        assertEquals("camera moves through a rainy city", result.get("prompt"));
        assertEquals("[\"https://cdn.example/frame.png\"]", result.get("materialUrls").toString());
        assertEquals(1, result.get("inputCount"));
        assertTrue(!result.containsKey("requestJson"));
    }

    /** Seedream 的融合、组图和图层模式仍属于图片生成，不能被类型隔离误伤。 */
    @Test
    void acceptsSeedreamImageModes() {
        var image = imageInput();
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "prompt", null, "MULTI_IMAGE_FUSION", List.of(image, image), Map.of())));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "prompt", null, "TEXT_TO_GROUP", List.of(), Map.of())));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "prompt", null, "LAYER_DECOMPOSITION", List.of(image), Map.of())));
    }

    /** 文本、图片和视频分别校验解码后最大字节数，覆盖临界值与超限值。 */
    @ParameterizedTest
    @CsvSource({"TEXT,text/plain,1048576", "IMAGE,image/png,10485760", "VIDEO,video/mp4,6291456"})
    void enforcesPerFileByteLimits(String kind, String contentType, int limit) {
        var atLimit = new MediaGenerationService.MediaInput(kind, "reference", contentType, dataUrl(contentType, limit));
        var tooLarge = new MediaGenerationService.MediaInput(kind, "reference", contentType, dataUrl(contentType, limit + 1));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L,
            "", null, "MULTIMODAL", List.of(atLimit), Map.of())));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("video_model", 1L,
            "", null, "MULTIMODAL", List.of(tooLarge), Map.of())));
    }

    /** 图片流同时兼容顶层和 data 嵌套事件，并保留完成标记。 */
    @Test
    void readsNestedImageStreamEvents() throws Exception {
        String stream = "data: {\"type\":\"image.completed\",\"data\":{\"b64_json\":\"YQ==\"}}\n\n"
            + "data: [DONE]\n\n";
        var result = service.readImageStream(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)));
        assertEquals("YQ==", result.path("data").get(0).path("b64_json").asText());
        assertFalse(result.path("incomplete").asBoolean(false));
    }

    /** 供应商 HTTP 错误保留状态、错误码和消息，但不得把完整响应中的密钥字段写入任务错误。 */
    @Test
    void preservesProviderHttpErrorDetailsWithoutSecrets() throws Exception {
        Method parser = MediaGenerationService.class.getDeclaredMethod("providerRequestException", int.class, String.class);
        parser.setAccessible(true);
        Object failure = parser.invoke(service, 404,
            "{\"error\":{\"code\":\"InvalidEndpointOrModel.NotFound\",\"message\":\"api_key=secret-key model|not found\\nplease check\",\"api_key\":\"secret-key\"}}");
        Method message = failure.getClass().getDeclaredMethod("failureMessage");
        message.setAccessible(true);
        String value = (String) message.invoke(failure);
        assertEquals("PROVIDER_REQUEST_FAILED|HTTP_404|InvalidEndpointOrModel.NotFound|api_key=<redacted> model not found please check", value);
        assertFalse(value.contains("secret-key"));
    }

    /** 用量记录只使用稳定错误码，任务接口仍可展示供应商的具体诊断信息。 */
    @Test
    void keepsStableFailureCodeForUsageRecords() throws Exception {
        Method method = MediaGenerationService.class.getDeclaredMethod("stableFailureCode", String.class);
        method.setAccessible(true);
        assertEquals("PROVIDER_REQUEST_FAILED", method.invoke(service,
            "PROVIDER_REQUEST_FAILED|HTTP_404|InvalidEndpointOrModel.NotFound|model unavailable"));
        assertEquals("PROVIDER_RESPONSE_INVALID", method.invoke(service, "PROVIDER_RESPONSE_INVALID"));
    }

    /** Seedance 完成响应的 content 对象或数组都必须提取视频 URL，不能把成功任务标记为无结果。 */
    @Test
    void extractsVideoUrlsFromArkContentShapes() throws Exception {
        Method method = MediaGenerationService.class.getDeclaredMethod("outputs", com.fasterxml.jackson.databind.JsonNode.class, boolean.class);
        method.setAccessible(true);
        var objectResponse = new ObjectMapper().readTree("{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/video.mp4\",\"last_frame_url\":\"https://cdn.example/frame.png\"}}");
        @SuppressWarnings("unchecked") List<Map<String, Object>> objectOutputs = (List<Map<String, Object>>) method.invoke(service, objectResponse, true);
        assertEquals(2, objectOutputs.size());
        assertEquals("video", objectOutputs.get(0).get("mediaType"));
        assertEquals("image", objectOutputs.get(1).get("mediaType"));

        var arrayResponse = new ObjectMapper().readTree("{\"status\":\"succeeded\",\"content\":[{\"video_url\":\"https://cdn.example/video.mp4\"}]}");
        @SuppressWarnings("unchecked") List<Map<String, Object>> arrayOutputs = (List<Map<String, Object>>) method.invoke(service, arrayResponse, true);
        assertEquals(1, arrayOutputs.size());
    }

    /** 供应商地址既可填写 API 根地址，也可填写完整媒体接口地址，最终请求路径不能重复。 */
    @Test
    void resolvesConfiguredMediaEndpointWithoutDuplicatingProtocolPath() throws Exception {
        Method method = MediaGenerationService.class.getDeclaredMethod("endpointUri", String.class, String.class);
        method.setAccessible(true);
        Method statusMethod = MediaGenerationService.class.getDeclaredMethod("endpointUri", String.class, String.class, String.class);
        statusMethod.setAccessible(true);
        assertEquals("https://ark.example/api/v3/images/generations",
            invokeEndpoint(method, "https://ark.example/api/v3", "/images/generations").toString());
        assertEquals("https://ark.example/api/v3/images/generations",
            invokeEndpoint(method, "https://ark.example/api/v3/images/generations", "/images/generations").toString());
        assertEquals("https://ark.example/api/v3/contents/generations/tasks/task-1",
            invokeEndpoint(statusMethod, "https://ark.example/api/v3/contents/generations/tasks", "/contents/generations/tasks/task-1", "/contents/generations/tasks").toString());
        assertEquals("https://ark.example/api/v3/contents/generations/tasks",
            invokeEndpoint(statusMethod, "https://ark.example/api/v3/contents/generations/tasks", "/contents/generations/tasks", "/contents/generations/tasks").toString());
        assertEquals("https://generic.example/v1/videos/task-1",
            invokeEndpoint(statusMethod, "https://generic.example/v1/videos/generations", "/videos/task-1", "/videos/generations").toString());
        assertEquals("https://ark.example/api/v3/images/generations?tenant=demo",
            invokeEndpoint(method, "https://ark.example/api/v3/images/generations?tenant=demo", "/images/generations").toString());
    }

    /** 通过反射只测试纯校验分支，避免为边界测试伪造数据库和供应商调用。 */
    private void validate(MediaGenerationService.GenerateRequest request) throws Exception {
        var method = MediaGenerationService.class.getDeclaredMethod("validate", MediaGenerationService.GenerateRequest.class);
        method.setAccessible(true);
        try { method.invoke(service, request); }
        catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    /** 通过反射调用带供应商候选项的校验分支，验证具体 Agent 的模式约束。 */
    private void validate(MediaGenerationService.GenerateRequest request, LlmManagementService.WorkerCandidate candidate) throws Exception {
        var method = MediaGenerationService.class.getDeclaredMethod("validate", MediaGenerationService.GenerateRequest.class, LlmManagementService.WorkerCandidate.class);
        method.setAccessible(true);
        try { method.invoke(service, request, candidate); }
        catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    /** 调用供应商地址解析方法并还原反射异常，保持 URL 断言可读。 */
    private URI invokeEndpoint(Method method, String base, String path) throws Exception {
        return invokeEndpoint(method, base, path, null);
    }

    /** 调用带生成接口路径的异步状态地址解析，验证完整供应商地址可切换到状态接口。 */
    private URI invokeEndpoint(Method method, String base, String path, String operationPath) throws Exception {
        try {
            return operationPath == null
                ? (URI) method.invoke(service, base, path)
                : (URI) method.invoke(service, base, path, operationPath);
        }
        catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    /** 返回最小合法 PNG Data URL。 */
    private MediaGenerationService.MediaInput imageInput() { return new MediaGenerationService.MediaInput("IMAGE", "reference.png", "image/png", dataUrl("image/png", 3)); }

    /** 返回最小合法文本 Data URL。 */
    private MediaGenerationService.MediaInput textInput() { return new MediaGenerationService.MediaInput("TEXT", "reference.txt", "text/plain", dataUrl("text/plain", 3)); }

    /** 返回最小合法视频 Data URL。 */
    private MediaGenerationService.MediaInput videoInput() { return new MediaGenerationService.MediaInput("VIDEO", "clip.mp4", "video/mp4", dataUrl("video/mp4", 3)); }

    /** 生成指定字节数的 Base64 Data URL，测试不依赖磁盘文件。 */
    private String dataUrl(String contentType, int bytes) {
        return "data:" + contentType + ";base64," + java.util.Base64.getEncoder().encodeToString(new byte[bytes]);
    }
}
