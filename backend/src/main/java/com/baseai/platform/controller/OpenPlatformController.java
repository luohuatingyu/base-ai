package com.baseai.platform.controller;

import com.baseai.platform.security.ApiKeyEndpointCatalogService;
import com.baseai.platform.trace.TraceIgnored;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@TraceIgnored
@RestController
@RequestMapping("/api/open/platform/endpoints")
public class OpenPlatformController {
    private final ApiKeyEndpointCatalogService endpointCatalog;

    public OpenPlatformController(ApiKeyEndpointCatalogService endpointCatalog) {
        this.endpointCatalog = endpointCatalog;
    }

    /** 返回与 API Key 配置目录一致且不含内部权限编码的公开接口文档。 */
    @GetMapping
    public List<PublicEndpointView> endpoints() {
        return endpointCatalog.catalog().stream().map(PublicEndpointView::from).toList();
    }

    public record PublicEndpointView(
        String code, String nameKey, String groupKey, String descriptionKey, String risk, String method, String path,
        List<ApiKeyEndpointCatalogService.FieldView> pathParameters,
        List<ApiKeyEndpointCatalogService.FieldView> requestFields,
        List<ApiKeyEndpointCatalogService.FieldView> responseFields,
        String requestExample, String responseExample
    ) {
        /** 从内部目录模型生成公开文档模型并移除 RBAC 权限编码。 */
        private static PublicEndpointView from(ApiKeyEndpointCatalogService.EndpointView endpoint) {
            return new PublicEndpointView(endpoint.code(), endpoint.nameKey(), endpoint.groupKey(),
                endpoint.descriptionKey(), endpoint.risk(), endpoint.method(), endpoint.path(),
                endpoint.pathParameters(), endpoint.requestFields(), endpoint.responseFields(),
                endpoint.requestExample(), endpoint.responseExample());
        }
    }
}
