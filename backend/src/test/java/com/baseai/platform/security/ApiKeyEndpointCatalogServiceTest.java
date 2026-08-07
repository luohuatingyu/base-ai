package com.baseai.platform.security;

import com.baseai.platform.automation.ApiTriggerController;
import com.baseai.platform.controller.AiChatController;
import com.baseai.platform.workflow.WorkflowModels;
import com.baseai.platform.workflow.WorkflowOpenController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyEndpointCatalogServiceTest {
    /** 实际 API Key 接口应生成包含完整文档且顺序稳定的同一目录。 */
    @Test
    void catalogsDocumentedApiKeyEndpoints() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/api/ai/chat"), handler(AiChatController.class, "chat", AiChatController.ChatRequest.class));
        handlers.put(mapping("/api/automation/api-triggers/{id}/trigger"),
            handler(ApiTriggerController.class, "trigger", Long.class));
        handlers.put(mapping("/api/workflows/{code}/runs"),
            handler(WorkflowOpenController.class, "execute", String.class, WorkflowModels.RunCommand.class));
        handlers.put(mapping("/api/workflows/runs/{runId}", RequestMethod.GET),
            handler(WorkflowOpenController.class, "result", String.class));

        List<ApiKeyEndpointCatalogService.EndpointView> endpoints = service(handlers).catalog();

        assertEquals(List.of("ai.chat.invoke", "automation.api-trigger.execute", "workflow.execute", "workflow.run.read"),
            endpoints.stream().map(ApiKeyEndpointCatalogService.EndpointView::code).toList());
        ApiKeyEndpointCatalogService.EndpointView chat = endpoints.get(0);
        assertEquals("openPlatform.endpointDescriptions.aiChatInvoke", chat.descriptionKey());
        assertTrue(chat.requestFields().stream().anyMatch(field -> field.name().equals("messages") && field.required()));
        assertTrue(chat.responseFields().stream().anyMatch(field -> field.name().equals("traceId")));
        assertFalse(chat.responseFields().stream().anyMatch(field -> field.name().equals("data.traceId")));
        assertEquals("Hello!", chat.responseFields().stream().filter(field -> field.name().equals("data.content"))
            .findFirst().orElseThrow().example());
        assertTrue(chat.responseExample().contains("\"traceId\": \"trace-id\""));
        assertFalse(java.util.Arrays.stream(AiChatController.ChatResponse.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("traceId")));
        ApiKeyEndpointCatalogService.EndpointView trigger = endpoints.get(1);
        assertEquals("id", trigger.pathParameters().get(0).name());
        assertEquals("automation:api-trigger:trigger", trigger.permission());
        ApiKeyEndpointCatalogService.EndpointView workflow = endpoints.get(2);
        assertEquals("code", workflow.pathParameters().get(0).name());
        assertEquals("workflow:canvas:execute", workflow.permission());
        assertTrue(workflow.requestFields().stream().anyMatch(field -> field.name().equals("inputs") && field.required()));
        assertEquals("workflow:canvas:logs", endpoints.get(3).permission());
    }

    /** 枚举元数据应与示例值一并公开给开放平台页面。 */
    @Test
    void catalogsExampleAndEnumValues() throws Exception {
        EnumFieldController controller = new EnumFieldController();
        Method method = EnumFieldController.class.getMethod("invoke");
        Map<RequestMappingInfo, HandlerMethod> handlers = Map.of(
            mapping("/api/example"), new HandlerMethod(controller, method));

        ApiKeyEndpointCatalogService.FieldView field = service(handlers).catalog().get(0).responseFields().get(0);

        assertEquals("READY", field.example());
        assertEquals(List.of("READY", "STOPPED"), field.enumValues());
    }

    /** 接口路径变量与文档声明不一致时必须拒绝生成目录。 */
    @Test
    void rejectsMismatchedPathDocumentation() throws Exception {
        InvalidPathController controller = new InvalidPathController();
        Method method = InvalidPathController.class.getMethod("invoke", Long.class);
        Map<RequestMappingInfo, HandlerMethod> handlers = Map.of(
            mapping("/api/example/{id}"), new HandlerMethod(controller, method));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service(handlers).catalog());

        assertTrue(error.getMessage().contains("路径参数文档不匹配"));
    }

    /** 响应字段或响应示例缺失时必须拒绝开放接口文档启动。 */
    @Test
    void rejectsIncompleteDocumentation() throws Exception {
        IncompleteController controller = new IncompleteController();
        Method method = IncompleteController.class.getMethod("invoke");
        Map<RequestMappingInfo, HandlerMethod> handlers = Map.of(
            mapping("/api/example"), new HandlerMethod(controller, method));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service(handlers).run(null));

        assertTrue(error.getMessage().contains("接口文档不完整"));
    }

    /** 构造仅包含给定控制器映射的目录服务。 */
    private ApiKeyEndpointCatalogService service(Map<RequestMappingInfo, HandlerMethod> handlers) {
        @SuppressWarnings("unchecked") ObjectProvider<RequestMappingHandlerMapping> provider = mock(ObjectProvider.class);
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(provider.getObject()).thenReturn(mapping);
        when(mapping.getHandlerMethods()).thenReturn(handlers);
        return new ApiKeyEndpointCatalogService(provider);
    }

    /** 构造单一路径和 POST 方法的 Spring MVC 映射。 */
    private static RequestMappingInfo mapping(String path) {
        return mapping(path, RequestMethod.POST);
    }

    /** 构造指定 HTTP 方法的 Spring MVC 映射。 */
    private static RequestMappingInfo mapping(String path, RequestMethod method) {
        return RequestMappingInfo.paths(path).methods(method).build();
    }

    /** 构造实际控制器方法处理器且不执行其依赖构造逻辑。 */
    private static HandlerMethod handler(Class<?> controllerType, String methodName, Class<?>... parameterTypes)
        throws Exception {
        return new HandlerMethod(mock(controllerType), controllerType.getMethod(methodName, parameterTypes));
    }

    static class InvalidPathController {
        /** 提供路径变量缺失文档声明的异常样例。 */
        @PostMapping
        @RequiredPermission("example:invoke")
        @ApiKeyEndpoint(code = "example.invalid-path", nameKey = "example.name", groupKey = "example.group",
            descriptionKey = "example.description",
            responseFields = {@ApiKeyField(name = "success", descriptionKey = "example.success", type = "boolean")},
            responseExample = "{}")
        public void invoke(Long id) {}
    }

    static class IncompleteController {
        /** 提供响应文档为空的异常样例。 */
        @PostMapping
        @RequiredPermission("example:invoke")
        @ApiKeyEndpoint(code = "example.incomplete", nameKey = "example.name", groupKey = "example.group",
            descriptionKey = "", responseFields = {}, responseExample = "")
        public void invoke() {}
    }

    static class EnumFieldController {
        /** 提供包含枚举值的开放接口文档样例。 */
        @PostMapping
        @RequiredPermission("example:invoke")
        @ApiKeyEndpoint(code = "example.enum-field", nameKey = "example.name", groupKey = "example.group",
            descriptionKey = "example.description",
            responseFields = {@ApiKeyField(name = "status", descriptionKey = "example.status", type = "string",
                example = "READY", enumValues = {"READY", "STOPPED"})},
            responseExample = "{\"status\":\"READY\"}")
        public void invoke() {}
    }
}
