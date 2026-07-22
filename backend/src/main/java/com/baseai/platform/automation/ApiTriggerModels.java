package com.baseai.platform.automation;

import java.time.LocalDateTime;

public final class ApiTriggerModels {
    private ApiTriggerModels() {}

    public record Command(
        String name, String description, String httpMethod, String url, String headers, String queryParams,
        String requestBody, String contentType, String cronExpression, Integer timeoutSeconds, Boolean enabled,
        Boolean authEnabled, String authUrl, String authMethod, String authBody, String authContentType,
        String authTokenPath, String authTokenHeader, String authTokenPrefix
    ) {}

    public record View(
        Long id, String name, String description, String httpMethod, String url, String headers, String queryParams,
        String requestBody, String contentType, String cronExpression, Integer timeoutSeconds, Boolean enabled,
        Boolean voided, Boolean authEnabled, String authUrl, String authMethod, String authBody,
        String authContentType, String authTokenPath, String authTokenHeader, String authTokenPrefix,
        Long ownerUserId, LocalDateTime lastTriggerAt, String lastStatus, String lastResult,
        LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record ExecutionResult(int httpStatus, long durationMs, String responseBody) {}
    public record LogView(Long id, Long configId, String traceId, String triggerType, String status,
                          Integer httpStatus, Long durationMs, String responseSummary,
                          String errorMessage, LocalDateTime triggeredAt) {}
}
