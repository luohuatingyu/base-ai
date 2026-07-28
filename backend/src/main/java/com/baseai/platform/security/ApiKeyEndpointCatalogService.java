package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ApiKeyEndpointCatalogService implements ApplicationRunner {
    private static final Pattern PATH_PARAMETER_PATTERN = Pattern.compile("\\{([^}/]+)}");
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;

    public ApiKeyEndpointCatalogService(ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        this.handlerMappingProvider = handlerMappingProvider;
    }

    /** 应用启动后校验 API Key 接口代码全局唯一。 */
    @Override
    public void run(ApplicationArguments arguments) {
        catalogByCode();
    }

    /** 返回按分组、名称和请求路径排序的 API Key 接口目录。 */
    public List<EndpointView> catalog() {
        return catalogByCode().values().stream()
            .sorted(Comparator.comparing(EndpointView::groupKey).thenComparing(EndpointView::nameKey)
                .thenComparing(EndpointView::path).thenComparing(EndpointView::method))
            .toList();
    }

    /** 判断接口代码是否存在于代码声明的开放目录。 */
    public boolean contains(String endpointCode) {
        return catalogByCode().containsKey(endpointCode);
    }

    /** 扫描 Spring MVC 映射并构造唯一接口目录。 */
    private Map<String, EndpointView> catalogByCode() {
        Map<String, EndpointView> endpoints = new LinkedHashMap<>();
        RequestMappingHandlerMapping handlerMapping = handlerMappingProvider.getObject();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            ApiKeyEndpoint annotation = resolveAnnotation(handler);
            if (annotation == null) return;
            EndpointView view = toView(mapping, handler, annotation);
            EndpointView existing = endpoints.putIfAbsent(view.code(), view);
            if (existing != null) throw new IllegalStateException("API Key 接口代码重复: " + view.code());
        });
        return endpoints;
    }

    /** 读取方法或控制器类上的 API Key 开放声明。 */
    public ApiKeyEndpoint resolveAnnotation(HandlerMethod handler) {
        ApiKeyEndpoint annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), ApiKeyEndpoint.class);
        return annotation != null ? annotation : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), ApiKeyEndpoint.class);
    }

    /** 将 MVC 映射转换为稳定的页面展示模型。 */
    private EndpointView toView(RequestMappingInfo mapping, HandlerMethod handler, ApiKeyEndpoint annotation) {
        Set<String> patterns = mapping.getPatternValues();
        Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
        if (patterns.size() != 1 || methods.size() != 1) {
            throw new BusinessException("apiKey.endpointMappingInvalid", annotation.code());
        }
        RequiredPermission permission = handler.getMethodAnnotation(RequiredPermission.class);
        if (permission == null) permission = handler.getBeanType().getAnnotation(RequiredPermission.class);
        if (permission == null) throw new IllegalStateException("API Key 接口必须声明 RequiredPermission: " + annotation.code());
        String path = patterns.iterator().next();
        validateDocumentation(annotation, path);
        return new EndpointView(annotation.code(), annotation.nameKey(), annotation.groupKey(), annotation.descriptionKey(),
            annotation.risk().name(), methods.iterator().next().name(), path, permission.value(),
            toFields(annotation.pathParameters()), toFields(annotation.requestFields()),
            toFields(annotation.responseFields()), annotation.requestExample(), annotation.responseExample());
    }

    /** 校验接口路径参数和文档字段是否完整一致。 */
    private void validateDocumentation(ApiKeyEndpoint annotation, String path) {
        if (annotation.descriptionKey().isBlank() || annotation.responseFields().length == 0
            || annotation.responseExample().isBlank()) {
            throw new IllegalStateException("API Key 接口文档不完整: " + annotation.code());
        }
        Set<String> declaredParameters = java.util.Arrays.stream(annotation.pathParameters())
            .map(ApiKeyField::name).collect(java.util.stream.Collectors.toSet());
        Set<String> pathParameters = new java.util.LinkedHashSet<>();
        Matcher matcher = PATH_PARAMETER_PATTERN.matcher(path);
        while (matcher.find()) pathParameters.add(matcher.group(1));
        if (!pathParameters.equals(declaredParameters)) {
            throw new IllegalStateException("API Key 接口路径参数文档不匹配: " + annotation.code());
        }
        if (annotation.requestFields().length > 0 && annotation.requestExample().isBlank()) {
            throw new IllegalStateException("API Key 接口请求示例缺失: " + annotation.code());
        }
    }

    /** 将字段注解转换为稳定的页面展示模型。 */
    private List<FieldView> toFields(ApiKeyField[] fields) {
        return java.util.Arrays.stream(fields)
            .map(field -> new FieldView(field.name(), field.descriptionKey(), field.type(), field.required(),
                field.defaultValue(), field.example()))
            .toList();
    }

    public record FieldView(String name, String descriptionKey, String type, boolean required,
                            String defaultValue, String example) {}

    public record EndpointView(String code, String nameKey, String groupKey, String descriptionKey, String risk,
                               String method, String path, String permission, List<FieldView> pathParameters,
                               List<FieldView> requestFields, List<FieldView> responseFields,
                               String requestExample, String responseExample) {}
}
