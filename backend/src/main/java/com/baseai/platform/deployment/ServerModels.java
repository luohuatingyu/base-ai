package com.baseai.platform.deployment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 服务器管理和部署接口模型。 */
public final class ServerModels {
    private ServerModels() { }

    public record ServerCommand(String name, String mode, String host, Integer port, String username,
                                String authType, String privateKey, String password, String passphrase,
                                String hostKey, String workingDir, String composeFile, Boolean enabled) { }

    public record ServerView(Long id, String name, String mode, String host, Integer port,
                             String username, String authType, String hostKey, String workingDir,
                             String composeFile, boolean enabled, String lastTestStatus,
                             String lastTestError, LocalDateTime lastTestAt, Long ownerUserId,
                             LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record DeploymentCommand(String action, String revision) { }

    public record DeploymentView(Long id, Long serverId, String traceId, String action,
                                 String revision, String status, String outputSummary,
                                 String errorMessage, LocalDateTime startedAt,
                                 LocalDateTime finishedAt) { }

    public record HostResourceView(Integer cpuCores, Double cpuUsagePercent,
                                   Double load1, Double load5, Double load15,
                                   Long memoryTotalBytes, Long memoryUsedBytes,
                                   Double memoryUsagePercent, String diskPath,
                                   Long diskTotalBytes, Long diskUsedBytes,
                                   Double diskUsagePercent, Long uptimeSeconds) { }

    public record ContainerStatusView(String id, String name, String image,
                                      String state, String health, String status) { }

    public record ServerMonitorView(String status, Instant collectedAt,
                                    HostResourceView host,
                                    List<ContainerStatusView> containers,
                                    String containerError, String error) { }
}
