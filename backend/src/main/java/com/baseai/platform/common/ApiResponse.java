package com.baseai.platform.common;

public record ApiResponse<T>(boolean success, int code, String message, T data) {
    /** 构建统一成功响应。 */
    public static <T> ApiResponse<T> success(T data, String message) { return new ApiResponse<>(true, 200, message, data); }
    /** 构建统一失败响应。 */
    public static <T> ApiResponse<T> failure(int code, String message) { return new ApiResponse<>(false, code, message, null); }
}
