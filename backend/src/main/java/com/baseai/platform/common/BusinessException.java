package com.baseai.platform.common;

public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessException(String message) {
        this(400, "BUSINESS_ERROR", message);
    }

    public BusinessException(int status, String message) {
        this(status, "BUSINESS_ERROR", message);
    }

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public static BusinessException unauthorized(String message) { return new BusinessException(401, "UNAUTHORIZED", message); }
    public static BusinessException forbidden(String message) { return new BusinessException(403, "FORBIDDEN", message); }
    public static BusinessException notFound(String message) { return new BusinessException(404, "NOT_FOUND", message); }
}
