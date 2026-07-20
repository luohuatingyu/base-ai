package com.baseai.platform.common;

public record ApiResponse<T>(boolean success, String code, String message, T data) {
    /** 构建统一成功响应。 */
    public static <T> ApiResponse<T> success(T data) { return new ApiResponse<>(true, "SUCCESS", "success", data); }
    /** 构建统一失败响应。 */
    public static <T> ApiResponse<T> failure(String code, String message) { return new ApiResponse<>(false, code, message, null); }
}
