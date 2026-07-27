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

@Service
public class ApiKeyEndpointCatalogService implements ApplicationRunner {
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
        return new EndpointView(annotation.code(), annotation.nameKey(), annotation.groupKey(), annotation.risk().name(),
            methods.iterator().next().name(), patterns.iterator().next(), permission.value());
    }

    public record EndpointView(String code, String nameKey, String groupKey, String risk, String method, String path, String permission) {}
}
