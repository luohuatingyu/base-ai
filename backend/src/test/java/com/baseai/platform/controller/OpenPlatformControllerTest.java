package com.baseai.platform.controller;

import com.baseai.platform.security.ApiKeyEndpointCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenPlatformControllerTest {
    /** 公开目录应保留文档字段并移除内部 RBAC 权限编码。 */
    @Test
    void returnsPublicDocumentationWithoutPermissionCode() {
        ApiKeyEndpointCatalogService catalog = mock(ApiKeyEndpointCatalogService.class);
        ApiKeyEndpointCatalogService.FieldView field = new ApiKeyEndpointCatalogService.FieldView(
            "id", "openPlatform.fields.triggerId", "integer", true, "", "1");
        when(catalog.catalog()).thenReturn(List.of(new ApiKeyEndpointCatalogService.EndpointView(
            "automation.api-trigger.execute", "name", "group", "description", "HIGH", "POST", "/api/{id}",
            "internal:permission", List.of(field), List.of(), List.of(field), "", "{}")));

        OpenPlatformController.PublicEndpointView endpoint = new OpenPlatformController(catalog).endpoints().get(0);

        assertEquals("automation.api-trigger.execute", endpoint.code());
        assertEquals("id", endpoint.pathParameters().get(0).name());
        assertFalse(java.util.Arrays.stream(endpoint.getClass().getRecordComponents())
            .anyMatch(component -> component.getName().equals("permission")));
    }
}
