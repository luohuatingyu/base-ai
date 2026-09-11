package com.baseai.platform.datasync;

import java.time.LocalDateTime;
import java.util.List;

/** 数据同步接口使用的请求和响应模型。 */
public final class DataSyncModels {
    private DataSyncModels() { }

    public record TableMapping(String sourceSchema, String sourceTable, String targetSchema,
                               String targetTable, List<String> columns) { }

    public record PlanCommand(String name, Long sourceConnectionId, Long targetConnectionId, Long serverId,
                              String strategy, String scheduleCron, Boolean enabled,
                              Boolean confirmDestructive, List<TableMapping> tables) { }

    public record PlanView(Long id, String name, Long ownerUserId, Long sourceConnectionId,
                           Long targetConnectionId, Long serverId, String serverName,
                           String strategy, String scheduleCron,
                           boolean enabled, List<TableMapping> tables, LocalDateTime lastRunAt,
                           Long lastRunId, String lastRunStatus, LocalDateTime createdAt,
                           LocalDateTime updatedAt) { }

    public record TableView(String schema, String name, String type) { }

    public record ConnectionOption(Long id, String code, String name, String connectionType) { }

    public record ColumnView(String name, int sqlType, String typeName, int size, int decimalDigits,
                             boolean nullable, int primaryKeyOrdinal) { }

    public record PreviewCommand(Long sourceConnectionId, Long targetConnectionId, Long serverId,
                                 String strategy, List<TableMapping> tables) { }

    public record TablePreview(TableMapping mapping, boolean sourceExists, boolean targetExists,
                                long sourceRows, List<ColumnView> sourceColumns,
                                List<ColumnView> targetColumns, List<String> warnings) { }

    public record PreviewView(List<TablePreview> tables) { }

    public record RunView(Long id, Long planId, Long ownerUserId, Long serverId, String serverName,
                          String traceId, String status, int totalTables,
                          int completedTables, long readRows, long writtenRows, String errorMessage,
                          LocalDateTime startedAt, LocalDateTime finishedAt,
                          List<RunTableView> tables) { }

    public record RunTableView(Long id, String sourceTable, String targetTable, String status,
                               long readRows, long insertedRows, long updatedRows,
                               String errorMessage, LocalDateTime startedAt,
                               LocalDateTime finishedAt) { }
}
