package com.baseai.platform.common;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {
    private final MessageSource messageSource;

    /** 注入统一消息资源。 */
    public ApiResponseAdvice(MessageSource messageSource) { this.messageSource = messageSource; }

    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converterType) { return true; }

    /** 包装外部业务 API，公开健康检查和内部服务协议保持原结构。 */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String path = request.getURI().getPath();
        if (path.startsWith("/api/open/") || path.startsWith("/api/internal/") || body instanceof ApiResponse<?>) return body;
        String message = messageSource.getMessage("common.success", null, LocaleContextHolder.getLocale());
        return ApiResponse.success(body, message);
    }
}
