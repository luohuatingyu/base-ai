package com.baseai.platform.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

public record ApiResponse<T>(boolean success, int code, String message, T data,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {
    /** 构建统一成功响应。 */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, 200, message, data, MDC.get("traceId"));
    }
    /** 构建统一失败响应。 */
    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(false, code, message, null, MDC.get("traceId"));
    }
}
