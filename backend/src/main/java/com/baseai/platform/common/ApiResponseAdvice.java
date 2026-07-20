package com.baseai.platform.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converterType) { return true; }

    /** 包装外部业务 API，公开健康检查和内部服务协议保持原结构。 */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String path = request.getURI().getPath();
        if (path.startsWith("/api/open/") || path.startsWith("/api/internal/") || body instanceof ApiResponse<?>) return body;
        return ApiResponse.success(body);
    }
}
