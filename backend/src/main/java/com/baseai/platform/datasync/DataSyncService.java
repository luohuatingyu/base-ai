package com.baseai.platform.datasync;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.deployment.ServerManagementService;
import com.baseai.platform.deployment.ServerModels;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceIgnored;
import com.baseai.platform.trace.TraceSnapshot;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 负责同步计划管理、数据库预检和批量数据复制。 */
@Service
public class DataSyncService {
    private static final Set<String> DATABASE_TYPES = Set.of("MYSQL", "POSTGRESQL");
    private static final Set<String> STRATEGIES = Set.of("UPSERT", "FULL_REPLACE", "APPEND");
    private static final int MAX_TABLES = 100;
    private static final int BATCH_SIZE = 500;
    private static final String MASKED_LOCK_PREFIX = "data-sync:lock:";

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowConnectionService connectionService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final ThreadPoolTaskExecutor executor;
    private final TaskTraceService taskTraceService;
    private final DataSyncAgentClient agentClient;
    private final ServerManagementService serverService;
    private final ConcurrentHashMap<Long, FutureTask<Void>> running = new ConcurrentHashMap<>();

    /** 注入平台数据库、连接配置、缓存锁和同步线程池。 */
    @Autowired
    public DataSyncService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                           WorkflowConnectionService connectionService, ObjectMapper objectMapper,
                           StringRedisTemplate redisTemplate,
                           @Qualifier("dataSyncTaskExecutor") ThreadPoolTaskExecutor executor,
                           TaskTraceService taskTraceService, DataSyncAgentClient agentClient,
                           ServerManagementService serverService) {
        this.jdbcTemplate = jdbcTemplate;
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.executor = executor;
        this.taskTraceService = taskTraceService;
        this.agentClient = agentClient;
        this.serverService = serverService;
    }

    /** 为纯 JDBC 单元测试和一次性远程 Worker 创建不访问平台资源的引擎。 */
    DataSyncService(JdbcTemplate jdbcTemplate, WorkflowConnectionService connectionService, ObjectMapper objectMapper,
                    StringRedisTemplate redisTemplate, ThreadPoolTaskExecutor executor, TaskTraceService taskTraceService) {
        this(jdbcTemplate, connectionService, objectMapper, redisTemplate, executor, taskTraceService, null, null);
    }

    /** 创建供独立进程复用的纯 JDBC 引擎。 */
    static DataSyncService workerEngine(ObjectMapper objectMapper) {
        return new DataSyncService(null, null, objectMapper, null, null, null, null, null);
    }

    /** 查询当前用户可见的同步计划。 */
    public List<DataSyncModels.PlanView> plans() {
        AuthUser user = AuthContext.require();
        String sql = "SELECT p.*,s.name AS server_name,(SELECT r.id FROM data_sync_run r WHERE r.plan_id=p.id ORDER BY r.id DESC LIMIT 1) AS last_run_id,(SELECT r.status FROM data_sync_run r WHERE r.plan_id=p.id ORDER BY r.id DESC LIMIT 1) AS last_run_status FROM data_sync_plan p LEFT JOIN managed_server s ON s.id=p.server_id WHERE p.voided=false ORDER BY p.id DESC";
        if (!user.roles().contains("ADMIN")) sql = "SELECT p.*,s.name AS server_name,(SELECT r.id FROM data_sync_run r WHERE r.plan_id=p.id ORDER BY r.id DESC LIMIT 1) AS last_run_id,(SELECT r.status FROM data_sync_run r WHERE r.plan_id=p.id ORDER BY r.id DESC LIMIT 1) AS last_run_status FROM data_sync_plan p LEFT JOIN managed_server s ON s.id=p.server_id WHERE p.voided=false AND p.owner_user_id=? ORDER BY p.id DESC";
        return user.roles().contains("ADMIN")
            ? jdbcTemplate.query(sql, (rs, row) -> mapPlan(rs))
            : jdbcTemplate.query(sql, (rs, row) -> mapPlan(rs), user.id());
    }

    /** 查询可用于数据同步的数据库连接，响应只包含脱敏元数据。 */
    public List<DataSyncModels.ConnectionOption> connections() {
        return connectionService.connectionOptions().stream()
            .filter(connection -> DATABASE_TYPES.contains(connection.connectionType()))
            .map(connection -> new DataSyncModels.ConnectionOption(connection.id(), connection.code(), connection.name(), connection.connectionType()))
            .toList();
    }

    /** 查询当前用户拥有的数据同步执行服务器。 */
    public List<ServerModels.DataSyncServerOption> servers() { return serverService.dataSyncServers(); }

    /** 创建同步计划并在保存前完成连接、策略和表名校验。 */
    @Transactional
    public DataSyncModels.PlanView create(DataSyncModels.PlanCommand command) {
        validateCommand(command);
        Long ownerId = AuthContext.require().id();
        requireConnection(command.sourceConnectionId(), ownerId, false);
        requireConnection(command.targetConnectionId(), ownerId, true);
        serverService.requireDataSyncTarget(command.serverId(), ownerId);
        String tables = json(command.tables());
        jdbcTemplate.update("""
            INSERT INTO data_sync_plan(name,owner_user_id,source_connection_id,target_connection_id,server_id,strategy,schedule_cron,enabled,tables_json)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, text(command.name()), ownerId, command.sourceConnectionId(), command.targetConnectionId(), command.serverId(),
            strategy(command.strategy()), blankToNull(command.scheduleCron()), !Boolean.FALSE.equals(command.enabled()), tables);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return plan(id);
    }

    /** 更新当前用户拥有的同步计划。 */
    @Transactional
    public DataSyncModels.PlanView update(Long id, DataSyncModels.PlanCommand command) {
        validateCommand(command);
        DataSyncModels.PlanView existing = requirePlan(id);
        requireOwner(existing.ownerUserId());
        requireConnection(command.sourceConnectionId(), existing.ownerUserId(), false);
        requireConnection(command.targetConnectionId(), existing.ownerUserId(), true);
        serverService.requireDataSyncTarget(command.serverId(), existing.ownerUserId());
        jdbcTemplate.update("""
            UPDATE data_sync_plan SET name=?,source_connection_id=?,target_connection_id=?,server_id=?,strategy=?,schedule_cron=?,enabled=?,tables_json=?,updated_at=NOW()
            WHERE id=?
            """, text(command.name()), command.sourceConnectionId(), command.targetConnectionId(), command.serverId(), strategy(command.strategy()),
            blankToNull(command.scheduleCron()), !Boolean.FALSE.equals(command.enabled()), json(command.tables()), id);
        return plan(id);
    }

    /** 删除未运行的同步计划。 */
    @Transactional
    public void delete(Long id) {
        DataSyncModels.PlanView plan = requirePlan(id);
        requireOwner(plan.ownerUserId());
        if (running.containsKey(id) || hasActiveRun(id)) throw new BusinessException("dataSync.running");
        jdbcTemplate.update("UPDATE data_sync_plan SET voided=true,enabled=false,updated_at=NOW() WHERE id=?", id);
    }

    /** 查询数据库中的可选表，只返回元数据，不返回行内容。 */
    public List<DataSyncModels.TableView> tables(Long connectionId, String schema, Long serverId) {
        if (schema != null && !schema.isBlank() && !validIdentifier(schema)) throw new BusinessException("dataSync.tableNameInvalid");
        Long ownerId = AuthContext.require().id();
        WorkflowConnectionService.StoredConnection connection = requireConnection(connectionId, ownerId, false);
        ServerModels.DataSyncExecutionTarget server = serverService.requireDataSyncTarget(serverId, ownerId);
        return agentClient.tables(server, connection, schema);
    }

    /** 对选定表执行源端和目标端结构预检。 */
    public DataSyncModels.PreviewView preview(DataSyncModels.PreviewCommand command) {
        if (command == null || command.sourceConnectionId() == null || command.targetConnectionId() == null) {
            throw new BusinessException("dataSync.planInvalid");
        }
        if (command.tables() == null || command.tables().isEmpty()) throw new BusinessException("dataSync.tablesRequired");
        if (command.sourceConnectionId().equals(command.targetConnectionId())) throw new BusinessException("dataSync.sameConnection");
        Long ownerId = AuthContext.require().id();
        WorkflowConnectionService.StoredConnection source = requireConnection(command.sourceConnectionId(), ownerId, false);
        WorkflowConnectionService.StoredConnection target = requireConnection(command.targetConnectionId(), ownerId, true);
        ServerModels.DataSyncExecutionTarget server = serverService.requireDataSyncTarget(command.serverId(), ownerId);
        validateTables(command.tables());
        String selectedStrategy = command.strategy() == null || command.strategy().isBlank()
            ? "UPSERT" : strategy(command.strategy());
        return agentClient.preview(server, source, target, selectedStrategy, command.tables());
    }

    /** 异步启动一个同步运行并返回可追踪的运行记录。 */
    @TraceIgnored
    public DataSyncModels.RunView run(Long planId) {
        DataSyncModels.PlanView plan = requirePlan(planId);
        requireOwner(plan.ownerUserId());
        serverService.requireDataSyncTarget(plan.serverId(), plan.ownerUserId());
        if (running.containsKey(planId) || hasActiveRun(planId)) throw new BusinessException("dataSync.running");
        String traceId = taskTraceService.create(null, plan.ownerUserId(), "DATA_SYNC", "MANUAL", "POST",
            "/api/data-sync/plans/" + planId + "/run", new TraceSnapshot("{}", "{}"));
        try {
            Long runId = createRunRecord(plan, traceId);
            submit(runId, plan, traceId);
            return runDetail(runId);
        } catch (RuntimeException exception) {
            taskTraceService.markFailed(traceId, exception.getMessage());
            throw exception;
        }
    }

    /** 取消当前用户拥有的运行任务。 */
    public DataSyncModels.RunView cancel(Long runId) {
        DataSyncModels.RunView run = requireRun(runId);
        requireOwner(run.ownerUserId());
        if (!Set.of("RUNNING", "CANCEL_REQUESTED").contains(run.status())) throw new BusinessException("dataSync.notRunning");
        jdbcTemplate.update("UPDATE data_sync_run SET status='CANCEL_REQUESTED' WHERE id=? AND status='RUNNING'", runId);
        String jobId = jdbcTemplate.queryForObject("SELECT agent_job_id FROM data_sync_run WHERE id=?", String.class, runId);
        if (jobId != null && !jobId.isBlank()) {
            try { agentClient.cancel(jobId); } catch (RuntimeException ignored) { }
        }
        return runDetail(runId);
    }

    /** 对已结束的失败或取消运行重新启动其原计划。 */
    public DataSyncModels.RunView retry(Long runId) {
        DataSyncModels.RunView previous = requireRun(runId);
        requireOwner(previous.ownerUserId());
        if (Set.of("RUNNING", "CANCEL_REQUESTED").contains(previous.status())) throw new BusinessException("dataSync.running");
        return run(previous.planId());
    }

    /** 查询运行明细和逐表统计。 */
    public DataSyncModels.RunView runDetail(Long runId) {
        DataSyncModels.RunView run = requireRun(runId);
        requireOwner(run.ownerUserId());
        return runView(runId);
    }

    /** 每分钟触发到期的 Cron 计划，并通过数据库字段避免重复调度。 */
    @Scheduled(fixedDelay = 60000)
    public void schedule() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM data_sync_plan WHERE voided=false AND enabled=true AND schedule_cron IS NOT NULL", Long.class);
        for (Long id : ids) {
            try {
                DataSyncModels.PlanView plan = requirePlan(id);
                if (plan.scheduleCron() == null || plan.scheduleCron().isBlank() || running.containsKey(id)) continue;
                if (!due(plan)) continue;
                int updated = jdbcTemplate.update("UPDATE data_sync_plan SET last_scheduled_at=NOW() WHERE id=? AND (last_scheduled_at IS NULL OR last_scheduled_at<?)",
                    id, LocalDateTime.now().minusMinutes(1));
                if (updated > 0) runAsScheduler(plan);
            } catch (RuntimeException ignored) {
                // 单个计划错误不能阻断其他计划的调度。
            }
        }
    }

    /** 每两秒将远程 Agent 进度对账到平台运行记录，支持 Backend 重启恢复。 */
    @Scheduled(fixedDelay = 2000)
    public void reconcileRemoteRuns() {
        List<PendingRemoteRun> pending = jdbcTemplate.query("""
            SELECT id,agent_job_id,status FROM data_sync_run
            WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND agent_job_id IS NOT NULL
            ORDER BY id LIMIT 50
            """, (rs, row) -> new PendingRemoteRun(rs.getLong("id"), rs.getString("agent_job_id"), rs.getString("status")));
        for (PendingRemoteRun run : pending) {
            try {
                if ("CANCEL_REQUESTED".equals(run.status())) agentClient.cancel(run.jobId());
                applyAgentJob(run.id(), agentClient.job(run.jobId()));
            } catch (BusinessException exception) {
                if (!"dataSync.agentUnavailable".equals(exception.getMessageKey())) {
                    finishFailed(run.id(), traceId(run.id()), exception.getMessageKey());
                }
            } catch (RuntimeException ignored) {
                // 网络短暂不可用时保留运行态，等待下一轮继续对账。
            }
        }
    }

    /** 在独立线程中向所选服务器提交一次性同步 Worker。 */
    private void execute(Long runId, DataSyncModels.PlanView plan, String traceId) {
        try {
            WorkflowConnectionService.StoredConnection source = requireConnectionForOwner(plan.sourceConnectionId(), plan.ownerUserId(), false);
            WorkflowConnectionService.StoredConnection target = requireConnectionForOwner(plan.targetConnectionId(), plan.ownerUserId(), true);
            ServerModels.DataSyncExecutionTarget server = serverService.requireDataSyncTarget(plan.serverId(), plan.ownerUserId());
            String jobId = jdbcTemplate.queryForObject("SELECT agent_job_id FROM data_sync_run WHERE id=?", String.class, runId);
            agentClient.start(server, jobId, source, target, plan.strategy(), plan.tables());
        } catch (Exception exception) {
            finishFailed(runId, traceId, safeError(exception));
        } finally {
            running.remove(plan.id());
        }
    }

    /** 复制一张表并以批量 PreparedStatement 写入目标端。 */
    TableResult copyTable(Connection sourceJdbc, Connection targetJdbc,
                          WorkflowConnectionService.StoredConnection source,
                          WorkflowConnectionService.StoredConnection target,
                          DataSyncModels.TableMapping mapping, String strategy) throws SQLException {
        return copyTable(sourceJdbc, targetJdbc, source, target, mapping, strategy, () -> false);
    }

    /** 复制单表时周期检查持久化取消标记，确保多实例部署也能停止后续批次。 */
    private TableResult copyTable(Connection sourceJdbc, Connection targetJdbc,
                                  WorkflowConnectionService.StoredConnection source,
                                  WorkflowConnectionService.StoredConnection target,
                                  DataSyncModels.TableMapping mapping, String strategy,
                                  BooleanSupplier cancelled) throws SQLException {
        List<DataSyncModels.ColumnView> sourceColumns = columns(sourceJdbc, source, mapping.sourceSchema(), mapping.sourceTable());
        if (sourceColumns.isEmpty()) throw new BusinessException("dataSync.sourceTableNotFound");
        List<DataSyncModels.ColumnView> targetColumns = columns(targetJdbc, target, mapping.targetSchema(), mapping.targetTable());
        validateCompatibility(strategy, mapping, sourceColumns, targetColumns, target);
        if (targetColumns.isEmpty()) {
            createTable(targetJdbc, target, mapping, selectedSourceColumns(mapping, sourceColumns));
            targetColumns = columns(targetJdbc, target, mapping.targetSchema(), mapping.targetTable());
        }
        List<String> columns = selectedColumns(mapping, sourceColumns, targetColumns);
        Map<String, Integer> sourceIndexes = sourceIndexes(sourceColumns);
        List<String> primaryKeys = primaryKeys(targetColumns);
        String targetName = quoteTable(target, mapping.targetSchema(), mapping.targetTable());
        String sourceName = quoteTable(source, mapping.sourceSchema(), mapping.sourceTable());
        String sql = insertSql(target, targetName, columns, primaryKeys, strategy);
        boolean previousTargetAutoCommit = targetJdbc.getAutoCommit();
        boolean previousSourceAutoCommit = sourceJdbc.getAutoCommit();
        boolean sourceStreamingTransaction = "POSTGRESQL".equals(source.connectionType()) && previousSourceAutoCommit;
        if (sourceStreamingTransaction) sourceJdbc.setAutoCommit(false);
        targetJdbc.setAutoCommit(false);
        try {
            ensureNotCancelled(cancelled);
            if ("FULL_REPLACE".equals(strategy)) {
                try (var statement = targetJdbc.createStatement()) { statement.executeUpdate("DELETE FROM " + targetName); }
            }
            long read = 0;
            long written = 0;
            try (PreparedStatement insert = targetJdbc.prepareStatement(sql);
                 PreparedStatement select = sourceJdbc.prepareStatement("SELECT " + columnList(source, columns) + " FROM " + sourceName,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                select.setFetchSize(BATCH_SIZE);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        for (int index = 0; index < columns.size(); index++) insert.setObject(index + 1, rows.getObject(sourceIndexes.get(columns.get(index))));
                        insert.addBatch();
                        read++;
                        if (read % BATCH_SIZE == 0) {
                            ensureNotCancelled(cancelled);
                            int[] result = insert.executeBatch();
                            written += countWrites(result);
                        }
                    }
                    ensureNotCancelled(cancelled);
                    if (read % BATCH_SIZE != 0) written += countWrites(insert.executeBatch());
                }
            }
            targetJdbc.commit();
            return new TableResult(read, written);
        } catch (SQLException | RuntimeException exception) {
            try { targetJdbc.rollback(); } catch (SQLException ignored) { }
            throw exception;
        } finally {
            if (sourceStreamingTransaction) {
                try { sourceJdbc.rollback(); } catch (SQLException ignored) { }
                sourceJdbc.setAutoCommit(previousSourceAutoCommit);
            }
            targetJdbc.setAutoCommit(previousTargetAutoCommit);
        }
    }

    /** 在写入任何表前完成全部表结构预检，避免后续表不兼容造成可预防的部分同步。 */
    private void preflightRun(Long runId, DataSyncModels.PlanView plan, Connection sourceJdbc,
                              Connection targetJdbc, WorkflowConnectionService.StoredConnection source,
                              WorkflowConnectionService.StoredConnection target,
                              List<Long> tableRunIds) throws SQLException {
        if (tableRunIds.size() != plan.tables().size()) throw new BusinessException("dataSync.planInvalid");
        for (int index = 0; index < plan.tables().size(); index++) {
            ensureNotCancelled(runId);
            DataSyncModels.TableMapping mapping = plan.tables().get(index);
            try {
                List<DataSyncModels.ColumnView> sourceColumns = columns(sourceJdbc, source,
                    mapping.sourceSchema(), mapping.sourceTable());
                if (sourceColumns.isEmpty()) throw new BusinessException("dataSync.sourceTableNotFound");
                List<DataSyncModels.ColumnView> targetColumns = columns(targetJdbc, target,
                    mapping.targetSchema(), mapping.targetTable());
                validateCompatibility(plan.strategy(), mapping, sourceColumns, targetColumns, target);
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException | SQLException exception) {
                markTableFailed(tableRunIds.get(index), exception.getMessage());
                throw exception;
            }
        }
    }

    /** 校验所选字段、常见类型和 UPSERT 主键，缺失目标表时只允许可映射类型。 */
    private void validateCompatibility(String strategy, DataSyncModels.TableMapping mapping,
                                       List<DataSyncModels.ColumnView> sourceColumns,
                                       List<DataSyncModels.ColumnView> targetColumns,
                                       WorkflowConnectionService.StoredConnection target) {
        List<DataSyncModels.ColumnView> selectedSource = selectedSourceColumns(mapping, sourceColumns);
        List<String> keyColumns;
        if (targetColumns.isEmpty()) {
            selectedSource.forEach(column -> typeSql(target, column));
            keyColumns = primaryKeys(sourceColumns);
        } else {
            Map<String, DataSyncModels.ColumnView> targetByName = columnsByName(targetColumns);
            for (DataSyncModels.ColumnView sourceColumn : selectedSource) {
                DataSyncModels.ColumnView targetColumn = targetByName.get(sourceColumn.name());
                if (targetColumn == null) throw new BusinessException("dataSync.columnMismatch");
                if (!compatibleType(sourceColumn, targetColumn)) throw new BusinessException("dataSync.columnTypeMismatch");
            }
            keyColumns = primaryKeys(targetColumns);
        }
        if ("UPSERT".equals(strategy)) {
            Set<String> selectedNames = selectedSource.stream().map(column -> column.name().toLowerCase(Locale.ROOT))
                .collect(HashSet::new, Set::add, Set::addAll);
            if (keyColumns.isEmpty() || !keyColumns.stream().map(value -> value.toLowerCase(Locale.ROOT)).allMatch(selectedNames::contains)) {
                throw new BusinessException("dataSync.primaryKeyRequired");
            }
        }
    }

    /** 判断两端 JDBC 类型是否可在不静默截断的前提下复制。 */
    private boolean compatibleType(DataSyncModels.ColumnView source, DataSyncModels.ColumnView target) {
        String sourceFamily = typeFamily(source);
        String targetFamily = typeFamily(target);
        if ("UNSUPPORTED".equals(sourceFamily) || "UNSUPPORTED".equals(targetFamily)) return false;
        if ("INTEGER".equals(sourceFamily) && "INTEGER".equals(targetFamily)) {
            return integerRank(target.sqlType()) >= integerRank(source.sqlType());
        }
        if ("DECIMAL".equals(sourceFamily) && "DECIMAL".equals(targetFamily)) {
            return target.size() >= source.size() && target.decimalDigits() >= source.decimalDigits();
        }
        if ("FLOAT".equals(sourceFamily) && "FLOAT".equals(targetFamily)) {
            return floatingRank(target.sqlType()) >= floatingRank(source.sqlType());
        }
        if ("STRING".equals(sourceFamily) && "STRING".equals(targetFamily)) {
            return stringCapacity(target) >= stringCapacity(source);
        }
        if ("BINARY".equals(sourceFamily) && "BINARY".equals(targetFamily)) {
            return binaryCapacity(target) >= binaryCapacity(source);
        }
        if ("UUID".equals(sourceFamily) && "STRING".equals(targetFamily)) return stringCapacity(target) >= 36;
        return sourceFamily.equals(targetFamily);
    }

    /** 将数据库类型归并为同步器支持的安全类型族。 */
    private String typeFamily(DataSyncModels.ColumnView column) {
        String typeName = text(column.typeName()).toUpperCase(Locale.ROOT);
        if (typeName.contains("UNSIGNED")) return "UNSUPPORTED";
        if (Set.of("JSON", "JSONB").contains(typeName)) return "JSON";
        if ("UUID".equals(typeName)) return "UUID";
        return switch (column.sqlType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "INTEGER";
            case Types.DECIMAL, Types.NUMERIC -> "DECIMAL";
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> "FLOAT";
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> "STRING";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BINARY";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIME_WITH_TIMEZONE -> "TIME_TZ";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP_TZ";
            default -> "UNSUPPORTED";
        };
    }

    /** 返回整数类型容量等级。 */
    private int integerRank(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT -> 1;
            case Types.SMALLINT -> 2;
            case Types.INTEGER -> 3;
            case Types.BIGINT -> 4;
            default -> 0;
        };
    }

    /** 返回浮点类型容量等级。 */
    private int floatingRank(int sqlType) { return sqlType == Types.DOUBLE ? 2 : 1; }

    /** 返回文本类型可容纳的字符数。 */
    private long stringCapacity(DataSyncModels.ColumnView column) {
        return Set.of(Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR).contains(column.sqlType())
            || column.size() <= 0 ? Long.MAX_VALUE : column.size();
    }

    /** 返回二进制类型可容纳的字节数。 */
    private long binaryCapacity(DataSyncModels.ColumnView column) {
        return Set.of(Types.BLOB, Types.LONGVARBINARY).contains(column.sqlType()) || column.size() <= 0
            ? Long.MAX_VALUE : column.size();
    }

    /** 根据数据库方言生成安全的批量插入语句。 */
    private String insertSql(WorkflowConnectionService.StoredConnection target, String table, List<String> columns,
                             List<String> primaryKeys, String strategy) {
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String base = "INSERT INTO " + table + " (" + columnList(target, columns) + ") VALUES (" + placeholders + ")";
        if (!"UPSERT".equals(strategy) || primaryKeys.isEmpty()) return base;
        Set<String> normalizedKeys = primaryKeys.stream().map(key -> key.toLowerCase(Locale.ROOT))
            .collect(HashSet::new, Set::add, Set::addAll);
        List<String> updates = columns.stream().filter(column -> !normalizedKeys.contains(column.toLowerCase(Locale.ROOT)))
            .map(column -> quote(target, column) + "=EXCLUDED." + quote(target, column)).toList();
        if ("MYSQL".equals(target.connectionType())) {
            updates = columns.stream().filter(column -> !normalizedKeys.contains(column.toLowerCase(Locale.ROOT)))
                .map(column -> quote(target, column) + "=VALUES(" + quote(target, column) + ")").toList();
            return updates.isEmpty() ? base + " ON DUPLICATE KEY UPDATE " + quote(target, columns.get(0)) + "=" + quote(target, columns.get(0))
                : base + " ON DUPLICATE KEY UPDATE " + String.join(",", updates);
        }
        String keys = primaryKeys.stream().map(key -> quote(target, key)).reduce((a, b) -> a + "," + b).orElse("");
        return updates.isEmpty() ? base + " ON CONFLICT (" + keys + ") DO NOTHING" : base + " ON CONFLICT (" + keys + ") DO UPDATE SET " + String.join(",", updates);
    }

    /** 创建缺失目标表并保留源端主键。 */
    private void createTable(Connection targetJdbc, WorkflowConnectionService.StoredConnection target,
                             DataSyncModels.TableMapping mapping, List<DataSyncModels.ColumnView> columns) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(quoteTable(target, mapping.targetSchema(), mapping.targetTable())).append(" (");
        List<String> definitions = new ArrayList<>();
        for (DataSyncModels.ColumnView column : columns) definitions.add(quote(target, column.name()) + " " + typeSql(target, column));
        List<String> keys = columns.stream().filter(column -> column.primaryKeyOrdinal() > 0).sorted((a, b) -> Integer.compare(a.primaryKeyOrdinal(), b.primaryKeyOrdinal())).map(column -> quote(target, column.name())).toList();
        if (!keys.isEmpty()) definitions.add("PRIMARY KEY (" + String.join(",", keys) + ")");
        sql.append(String.join(",", definitions)).append(")");
        try (Statement statement = targetJdbc.createStatement()) { statement.executeUpdate(sql.toString()); }
    }

    /** 映射常用 JDBC 类型，无法安全映射的专有类型直接拒绝。 */
    private String typeSql(WorkflowConnectionService.StoredConnection target, DataSyncModels.ColumnView column) {
        String typeName = text(column.typeName()).toUpperCase(Locale.ROOT);
        if (Set.of("JSON", "JSONB").contains(typeName)) return "MYSQL".equals(target.connectionType()) ? "JSON" : "JSONB";
        if ("UUID".equals(typeName)) return "MYSQL".equals(target.connectionType()) ? "VARCHAR(36)" : "UUID";
        return switch (column.sqlType()) {
            case Types.BIGINT -> "BIGINT";
            case Types.INTEGER -> "INTEGER";
            case Types.SMALLINT -> "SMALLINT";
            case Types.TINYINT -> "MYSQL".equals(target.connectionType()) ? "TINYINT" : "SMALLINT";
            case Types.DECIMAL, Types.NUMERIC -> decimalType(target, column);
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIME_WITH_TIMEZONE -> timezoneType(target, "TIME WITH TIME ZONE");
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> timezoneType(target, "TIMESTAMP WITH TIME ZONE");
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "MYSQL".equals(target.connectionType()) ? "LONGBLOB" : "BYTEA";
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> column.size() > 4000 ? "TEXT" : "VARCHAR(" + Math.max(1, column.size()) + ")";
            case Types.FLOAT, Types.REAL -> "REAL";
            case Types.DOUBLE -> "DOUBLE PRECISION";
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> "TEXT";
            default -> throw new BusinessException("dataSync.unsupportedColumnType");
        };
    }

    /** 保留十进制精度，并在 MySQL 目标会发生缩窄时提前拒绝。 */
    private String decimalType(WorkflowConnectionService.StoredConnection target, DataSyncModels.ColumnView column) {
        int precision = column.size();
        int scale = column.decimalDigits();
        if (precision <= 0) {
            if ("MYSQL".equals(target.connectionType())) throw new BusinessException("dataSync.unsupportedColumnType");
            return "DECIMAL";
        }
        if (scale < 0 || scale > precision
            || "MYSQL".equals(target.connectionType()) && (precision > 65 || scale > 30)) {
            throw new BusinessException("dataSync.unsupportedColumnType");
        }
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    /** 时区类型只在 PostgreSQL 目标端保持语义，MySQL 目标端拒绝静默丢失时区。 */
    private String timezoneType(WorkflowConnectionService.StoredConnection target, String type) {
        if (!"POSTGRESQL".equals(target.connectionType())) throw new BusinessException("dataSync.unsupportedColumnType");
        return type;
    }

    /** 读取表列、主键和基础类型元数据。 */
    private List<DataSyncModels.ColumnView> columns(Connection connection, WorkflowConnectionService.StoredConnection config,
                                                    String schema, String table) throws SQLException {
        if (!validIdentifier(table)) return List.of();
        Map<String, Integer> primaryKeys = new HashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet keys = metadata.getPrimaryKeys(catalog(connection, config), schemaOrNull(schema, config), table)) {
            while (keys.next()) primaryKeys.put(keys.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), keys.getInt("KEY_SEQ"));
        }
        List<DataSyncModels.ColumnView> result = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(catalog(connection, config), schemaOrNull(schema, config), table, "%")) {
            while (rows.next()) result.add(new DataSyncModels.ColumnView(rows.getString("COLUMN_NAME"), rows.getInt("DATA_TYPE"), rows.getString("TYPE_NAME"), rows.getInt("COLUMN_SIZE"), rows.getInt("DECIMAL_DIGITS"), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, primaryKeys.getOrDefault(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), 0)));
        }
        return result;
    }

    /** 计算源表行数供预检展示。 */
    private long count(Connection connection, WorkflowConnectionService.StoredConnection config, String schema, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + quoteTable(config, schema, table))) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    /** 生成预检警告但不泄露任何行内容。 */
    private List<String> compatibilityWarnings(String strategy, DataSyncModels.TableMapping mapping,
                                               List<DataSyncModels.ColumnView> source,
                                               List<DataSyncModels.ColumnView> target) {
        List<String> warnings = new ArrayList<>();
        if (source.isEmpty()) return List.of("sourceTableNotFound");
        List<DataSyncModels.ColumnView> selected;
        try {
            selected = selectedSourceColumns(mapping, source);
        } catch (BusinessException exception) {
            return List.of("columnMismatch");
        }
        if (target.isEmpty()) {
            warnings.add("targetTableWillBeCreated");
            for (DataSyncModels.ColumnView column : selected) {
                if ("UNSUPPORTED".equals(typeFamily(column))) warnings.add("unsupportedColumnType:" + column.name());
            }
        } else {
            Map<String, DataSyncModels.ColumnView> targetByName = columnsByName(target);
            for (DataSyncModels.ColumnView column : selected) {
                DataSyncModels.ColumnView targetColumn = targetByName.get(column.name());
                if (targetColumn == null) warnings.add("missingTargetColumn:" + column.name());
                else if (!compatibleType(column, targetColumn)) warnings.add("columnTypeMismatch:" + column.name());
            }
        }
        List<String> keyColumns = primaryKeys(target.isEmpty() ? source : target);
        Set<String> selectedNames = selected.stream().map(column -> column.name().toLowerCase(Locale.ROOT))
            .collect(HashSet::new, Set::add, Set::addAll);
        if ("UPSERT".equals(strategy) && (keyColumns.isEmpty()
            || !keyColumns.stream().map(value -> value.toLowerCase(Locale.ROOT)).allMatch(selectedNames::contains))) {
            warnings.add("primaryKeyRequired");
        }
        return warnings;
    }

    /** 只允许从元数据中选出的合法标识符。 */
    private void validateTables(List<DataSyncModels.TableMapping> tables) {
        if (tables == null || tables.isEmpty() || tables.size() > MAX_TABLES) throw new BusinessException("dataSync.tablesInvalid");
        Set<String> seen = new HashSet<>();
        for (DataSyncModels.TableMapping table : tables) {
            if (table == null || !validIdentifier(table.sourceTable()) || !validIdentifier(table.targetTable())) throw new BusinessException("dataSync.tableNameInvalid");
            if (table.sourceSchema() != null && !table.sourceSchema().isBlank() && !validIdentifier(table.sourceSchema())) throw new BusinessException("dataSync.tableNameInvalid");
            if (table.targetSchema() != null && !table.targetSchema().isBlank() && !validIdentifier(table.targetSchema())) throw new BusinessException("dataSync.tableNameInvalid");
            if (table.columns() != null && (table.columns().size() > 500
                || table.columns().stream().anyMatch(column -> !validIdentifier(column))
                || table.columns().stream().map(column -> column.toLowerCase(Locale.ROOT)).distinct().count() != table.columns().size())) {
                throw new BusinessException("dataSync.columnsInvalid");
            }
            String key = qualified(table.sourceSchema(), table.sourceTable()) + "->" + qualified(table.targetSchema(), table.targetTable());
            if (!seen.add(key)) throw new BusinessException("dataSync.duplicateTable");
        }
    }

    /** 校验同步计划字段和危险策略确认条件。 */
    private void validateCommand(DataSyncModels.PlanCommand command) {
        if (command == null || text(command.name()).isBlank() || text(command.name()).length() > 120
            || command.sourceConnectionId() == null || command.targetConnectionId() == null) {
            throw new BusinessException("dataSync.planInvalid");
        }
        if (command.serverId() == null) throw new BusinessException("dataSync.serverRequired");
        if (command.sourceConnectionId().equals(command.targetConnectionId())) throw new BusinessException("dataSync.sameConnection");
        strategy(command.strategy());
        validateTables(command.tables());
        if (command.scheduleCron() != null && command.scheduleCron().length() > 120) throw new BusinessException("dataSync.scheduleInvalid");
        if (command.scheduleCron() != null && !command.scheduleCron().isBlank()) {
            try { CronExpression.parse(command.scheduleCron().trim()); } catch (IllegalArgumentException exception) { throw new BusinessException("dataSync.scheduleInvalid"); }
        }
        if ("FULL_REPLACE".equals(strategy(command.strategy())) && !Boolean.TRUE.equals(command.confirmDestructive())) throw new BusinessException("dataSync.destructiveConfirmationRequired");
    }

    /** 获取受管连接并检查所有者、类型和目标写权限。 */
    private WorkflowConnectionService.StoredConnection requireConnection(Long id, Long ownerId, boolean write) {
        WorkflowConnectionService.StoredConnection connection = connectionService.resolved(id, DATABASE_TYPES);
        if (!connection.ownerUserId().equals(ownerId)) throw BusinessException.forbidden("dataSync.connectionForbidden");
        if (write && !connection.config().path("allowWrite").asBoolean(false)) throw new BusinessException("dataSync.targetReadOnly");
        return connection;
    }

    /** 异步线程按计划所有者读取连接，避免依赖请求线程中的认证上下文。 */
    private WorkflowConnectionService.StoredConnection requireConnectionForOwner(Long id, Long ownerId, boolean write) {
        WorkflowConnectionService.StoredConnection connection = connectionService.resolved(id, DATABASE_TYPES);
        if (!connection.ownerUserId().equals(ownerId)) throw new BusinessException("dataSync.connectionForbidden");
        if (write && !connection.config().path("allowWrite").asBoolean(false)) throw new BusinessException("dataSync.targetReadOnly");
        return connection;
    }

    /** 打开一次性 JDBC 连接，避免把外部数据库连接放入平台连接池。 */
    private Connection open(WorkflowConnectionService.StoredConnection connection) throws SQLException {
        String url = connection.config().path("url").asText("").trim();
        String username = connection.config().path("username").asText("");
        String password = connection.config().path("password").asText("");
        boolean validUrl = "MYSQL".equals(connection.connectionType()) && url.startsWith("jdbc:mysql:")
            || "POSTGRESQL".equals(connection.connectionType()) && url.startsWith("jdbc:postgresql:");
        if (!validUrl) throw new BusinessException("dataSync.connectionInvalid");
        return DriverManager.getConnection(url, username, password);
    }

    /** 将数据库标识符按方言引用，阻断表名注入。 */
    private String quote(WorkflowConnectionService.StoredConnection connection, String value) {
        if (!validIdentifier(value)) throw new BusinessException("dataSync.tableNameInvalid");
        return "MYSQL".equals(connection.connectionType()) ? "`" + value + "`" : "\"" + value + "\"";
    }

    /** 生成带可选 schema 的安全表名。 */
    private String quoteTable(WorkflowConnectionService.StoredConnection connection, String schema, String table) {
        return schema == null || schema.isBlank() ? quote(connection, table) : quote(connection, schema) + "." + quote(connection, table);
    }

    /** 生成带方言引用的列清单。 */
    private String columnList(WorkflowConnectionService.StoredConnection connection, List<String> columns) { return columns.stream().map(column -> quote(connection, column)).reduce((a, b) -> a + "," + b).orElse(""); }
    /** 返回用户选择且在两端精确同名的列，禁止静默忽略配置错误。 */
    private List<String> selectedColumns(DataSyncModels.TableMapping mapping, List<DataSyncModels.ColumnView> source, List<DataSyncModels.ColumnView> target) {
        List<String> selected = selectedSourceColumns(mapping, source).stream().map(DataSyncModels.ColumnView::name).toList();
        Set<String> targetNames = target.stream().map(DataSyncModels.ColumnView::name)
            .collect(HashSet::new, Set::add, Set::addAll);
        if (selected.stream().anyMatch(name -> !targetNames.contains(name))) throw new BusinessException("dataSync.columnMismatch");
        return selected;
    }
    /** 将用户列配置解析为源端真实列，空配置表示选择全部列。 */
    private List<DataSyncModels.ColumnView> selectedSourceColumns(DataSyncModels.TableMapping mapping,
                                                                  List<DataSyncModels.ColumnView> source) {
        Map<String, DataSyncModels.ColumnView> sourceByName = columnsByName(source);
        List<String> requested = mapping.columns() == null || mapping.columns().isEmpty()
            ? source.stream().map(DataSyncModels.ColumnView::name).toList() : mapping.columns();
        List<DataSyncModels.ColumnView> selected = requested.stream().map(sourceByName::get).toList();
        if (selected.isEmpty() || selected.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException("dataSync.columnMismatch");
        }
        return selected;
    }
    /** 按精确列名构建元数据索引。 */
    private Map<String, DataSyncModels.ColumnView> columnsByName(List<DataSyncModels.ColumnView> columns) {
        Map<String, DataSyncModels.ColumnView> result = new HashMap<>();
        for (DataSyncModels.ColumnView column : columns) result.put(column.name(), column);
        return result;
    }
    /** 将列名映射为 ResultSet 下标。 */
    private Map<String, Integer> sourceIndexes(List<DataSyncModels.ColumnView> columns) { Map<String, Integer> result = new HashMap<>(); for (int i = 0; i < columns.size(); i++) result.put(columns.get(i).name(), i + 1); return result; }
    /** 按主键顺序找出目标主键列。 */
    private List<String> primaryKeys(List<DataSyncModels.ColumnView> columns) { return columns.stream().filter(column -> column.primaryKeyOrdinal() > 0).sorted((left, right) -> Integer.compare(left.primaryKeyOrdinal(), right.primaryKeyOrdinal())).map(DataSyncModels.ColumnView::name).toList(); }
    /** 统计批量执行结果，兼容驱动返回 SUCCESS_NO_INFO。 */
    private long countWrites(int[] result) { long count = 0; for (int item : result) if (item >= 0) count += item; else count++; return count; }

    /** 纯 JDBC Worker 查询数据库表元数据。 */
    List<DataSyncModels.TableView> workerTables(WorkflowConnectionService.StoredConnection connection, String schema) {
        if (schema != null && !schema.isBlank() && !validIdentifier(schema)) throw new BusinessException("dataSync.tableNameInvalid");
        try (Connection jdbc = open(connection)) {
            DatabaseMetaData metadata = jdbc.getMetaData();
            List<DataSyncModels.TableView> result = new ArrayList<>();
            try (ResultSet rows = metadata.getTables(catalog(jdbc, connection), schemaOrNull(schema, connection), "%", new String[]{"TABLE"})) {
                while (rows.next() && result.size() < 500) {
                    String name = rows.getString("TABLE_NAME");
                    if (validIdentifier(name)) result.add(new DataSyncModels.TableView(rows.getString("TABLE_SCHEM"), name, rows.getString("TABLE_TYPE")));
                }
            }
            return result;
        } catch (SQLException exception) {
            throw new BusinessException("dataSync.connectionFailed");
        }
    }

    /** 纯 JDBC Worker 对源目标表执行结构和行数预检。 */
    DataSyncModels.PreviewView workerPreview(WorkflowConnectionService.StoredConnection source,
                                             WorkflowConnectionService.StoredConnection target,
                                             String selectedStrategy,
                                             List<DataSyncModels.TableMapping> tables) {
        validateTables(tables);
        String normalizedStrategy = strategy(selectedStrategy);
        try (Connection sourceJdbc = open(source); Connection targetJdbc = open(target)) {
            return workerPreview(sourceJdbc, targetJdbc, source, target, normalizedStrategy, tables);
        } catch (SQLException exception) {
            throw new BusinessException("dataSync.connectionFailed");
        }
    }

    /** 使用调用方提供的 JDBC 连接执行 Worker 预检，便于隔离数据库测试。 */
    DataSyncModels.PreviewView workerPreview(Connection sourceJdbc, Connection targetJdbc,
                                             WorkflowConnectionService.StoredConnection source,
                                             WorkflowConnectionService.StoredConnection target,
                                             String selectedStrategy,
                                             List<DataSyncModels.TableMapping> tables) throws SQLException {
        List<DataSyncModels.TablePreview> result = new ArrayList<>();
        for (DataSyncModels.TableMapping mapping : tables) {
            List<DataSyncModels.ColumnView> sourceColumns = columns(sourceJdbc, source, mapping.sourceSchema(), mapping.sourceTable());
            List<DataSyncModels.ColumnView> targetColumns = columns(targetJdbc, target, mapping.targetSchema(), mapping.targetTable());
            boolean sourceExists = !sourceColumns.isEmpty();
            boolean targetExists = !targetColumns.isEmpty();
            List<String> warnings = compatibilityWarnings(selectedStrategy, mapping, sourceColumns, targetColumns);
            long rows = sourceExists ? count(sourceJdbc, source, mapping.sourceSchema(), mapping.sourceTable()) : 0;
            result.add(new DataSyncModels.TablePreview(mapping, sourceExists, targetExists, rows,
                sourceColumns, targetColumns, warnings));
        }
        return new DataSyncModels.PreviewView(result);
    }

    /** 纯 JDBC Worker 预检全部表后逐表复制并输出累计进度。 */
    void workerRun(WorkflowConnectionService.StoredConnection source,
                   WorkflowConnectionService.StoredConnection target, String selectedStrategy,
                   List<DataSyncModels.TableMapping> tables, Consumer<WorkerProgress> progress) {
        validateTables(tables);
        String normalizedStrategy = strategy(selectedStrategy);
        progress.accept(new WorkerProgress("RUNNING", 0, 0, 0, List.of(), ""));
        try (Connection sourceJdbc = open(source); Connection targetJdbc = open(target)) {
            workerRun(sourceJdbc, targetJdbc, source, target, normalizedStrategy, tables, progress);
        } catch (Exception exception) {
            progress.accept(new WorkerProgress("FAILED", 0, 0, 0, List.of(), safeError(exception)));
        }
    }

    /** 使用调用方提供的 JDBC 连接执行 Worker 复制，便于隔离数据库测试。 */
    void workerRun(Connection sourceJdbc, Connection targetJdbc,
                   WorkflowConnectionService.StoredConnection source,
                   WorkflowConnectionService.StoredConnection target, String normalizedStrategy,
                   List<DataSyncModels.TableMapping> tables, Consumer<WorkerProgress> progress) {
        List<WorkerTableResult> results = new ArrayList<>();
        long readRows = 0;
        long writtenRows = 0;
        try {
            for (DataSyncModels.TableMapping mapping : tables) {
                List<DataSyncModels.ColumnView> sourceColumns = columns(sourceJdbc, source,
                    mapping.sourceSchema(), mapping.sourceTable());
                if (sourceColumns.isEmpty()) throw new BusinessException("dataSync.sourceTableNotFound");
                List<DataSyncModels.ColumnView> targetColumns = columns(targetJdbc, target,
                    mapping.targetSchema(), mapping.targetTable());
                validateCompatibility(normalizedStrategy, mapping, sourceColumns, targetColumns, target);
            }
            for (DataSyncModels.TableMapping mapping : tables) {
                try {
                    TableResult result = copyTable(sourceJdbc, targetJdbc, source, target, mapping, normalizedStrategy);
                    readRows += result.readRows();
                    writtenRows += result.writtenRows();
                    results.add(new WorkerTableResult(qualified(mapping.sourceSchema(), mapping.sourceTable()),
                        qualified(mapping.targetSchema(), mapping.targetTable()), "SUCCESS", result.readRows(),
                        result.writtenRows(), ""));
                    progress.accept(new WorkerProgress("RUNNING", results.size(), readRows, writtenRows,
                        List.copyOf(results), ""));
                } catch (Exception exception) {
                    results.add(new WorkerTableResult(qualified(mapping.sourceSchema(), mapping.sourceTable()),
                        qualified(mapping.targetSchema(), mapping.targetTable()), "FAILED", 0, 0,
                        safeError(exception)));
                    progress.accept(new WorkerProgress("FAILED", results.size() - 1, readRows, writtenRows,
                        List.copyOf(results), safeError(exception)));
                    return;
                }
            }
            progress.accept(new WorkerProgress("SUCCESS", results.size(), readRows, writtenRows,
                List.copyOf(results), ""));
        } catch (Exception exception) {
            progress.accept(new WorkerProgress("FAILED", results.size(), readRows, writtenRows,
                List.copyOf(results), safeError(exception)));
        }
    }

    /** 读取计划表对应的运行表 ID。 */
    private List<Long> tableRunIds(Long runId) { return jdbcTemplate.queryForList("SELECT id FROM data_sync_run_table WHERE run_id=? ORDER BY id", Long.class, runId); }

    /** 将 Agent 返回的累计进度和终态写回平台任务记录。 */
    private void applyAgentJob(Long runId, DataSyncAgentClient.AgentJob job) {
        JsonNode result = job.result();
        if (result != null) applyRemoteProgress(runId, result);
        if ("SUCCEEDED".equals(job.status())) {
            if (result == null || !"SUCCESS".equals(result.path("status").asText())) {
                finishFailed(runId, traceId(runId), result == null ? "dataSync.agentInvalidResponse"
                    : result.path("error").asText("dataSync.executionFailed"));
                return;
            }
            jdbcTemplate.update("UPDATE data_sync_run SET status='SUCCESS',active_slot=NULL,finished_at=NOW() WHERE id=?", runId);
            taskTraceService.markSuccess(traceId(runId));
        } else if ("FAILED".equals(job.status())) {
            String error = result == null ? job.error() : result.path("error").asText(job.error());
            finishFailed(runId, traceId(runId), error);
        } else if ("CANCELLED".equals(job.status())) {
            finishCancelled(runId, traceId(runId));
        }
    }

    /** 按计划表顺序应用远程 Worker 的逐表快照。 */
    private void applyRemoteProgress(Long runId, JsonNode result) {
        int completed = Math.max(0, result.path("completedTables").asInt(0));
        long readRows = Math.max(0, result.path("readRows").asLong(0));
        long writtenRows = Math.max(0, result.path("writtenRows").asLong(0));
        jdbcTemplate.update("UPDATE data_sync_run SET completed_tables=?,read_rows=?,written_rows=? WHERE id=?",
            completed, readRows, writtenRows, runId);
        List<Long> ids = tableRunIds(runId);
        JsonNode tables = result.path("tableResults");
        if (!tables.isArray() || tables.size() > ids.size()) return;
        for (int index = 0; index < tables.size(); index++) {
            JsonNode table = tables.get(index);
            String status = table.path("status").asText();
            if (!Set.of("SUCCESS", "FAILED").contains(status)) continue;
            jdbcTemplate.update("""
                UPDATE data_sync_run_table SET status=?,read_rows=?,inserted_rows=?,error_message=?,
                started_at=COALESCE(started_at,NOW()),finished_at=NOW() WHERE id=?
                """, status, Math.max(0, table.path("readRows").asLong()),
                Math.max(0, table.path("writtenRows").asLong()),
                blankToNull(truncate(table.path("error").asText(), 1000)), ids.get(index));
        }
    }

    /** 查询运行记录对应的任务追踪编号。 */
    private String traceId(Long runId) {
        return jdbcTemplate.queryForObject("SELECT trace_id FROM data_sync_run WHERE id=?", String.class, runId);
    }
    /** 查询计划是否仍存在持久化运行任务，覆盖多实例和进程重启场景。 */
    private boolean hasActiveRun(Long planId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM data_sync_run WHERE plan_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')", Integer.class, planId);
        return count != null && count > 0;
    }
    /** 查询运行记录中的取消标记。 */
    private boolean cancellationRequested(Long runId) {
        try {
            String status = jdbcTemplate.queryForObject("SELECT status FROM data_sync_run WHERE id=?", String.class, runId);
            return "CANCEL_REQUESTED".equals(status) || "CANCELLED".equals(status);
        } catch (RuntimeException exception) {
            return false;
        }
    }
    /** 在逐表边界检查持久化取消状态。 */
    private void ensureNotCancelled(Long runId) {
        if (Thread.currentThread().isInterrupted() || cancellationRequested(runId)) throw new CancellationException("cancelled");
    }
    /** 在批次边界检查调用方提供的取消状态。 */
    private void ensureNotCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("cancelled");
    }
    /** 记录表开始执行。 */
    private void markTableStarted(Long id) { jdbcTemplate.update("UPDATE data_sync_run_table SET status='RUNNING',started_at=NOW() WHERE id=?", id); }
    /** 记录表执行成功。 */
    private void markTableSuccess(Long id, TableResult result) { jdbcTemplate.update("UPDATE data_sync_run_table SET status='SUCCESS',read_rows=?,inserted_rows=?,finished_at=NOW() WHERE id=?", result.readRows(), result.writtenRows(), id); }
    /** 记录表执行失败。 */
    private void markTableFailed(Long id, String message) { jdbcTemplate.update("UPDATE data_sync_run_table SET status='FAILED',error_message=?,finished_at=NOW() WHERE id=?", truncate(message, 1000), id); }
    /** 记录当前表因用户取消而停止。 */
    private void markTableCancelled(Long id) { jdbcTemplate.update("UPDATE data_sync_run_table SET status='CANCELLED',error_message='cancelled',finished_at=NOW() WHERE id=?", id); }
    /** 记录运行失败并同步任务追踪状态。 */
    private void finishFailed(Long runId, String traceId, String message) { jdbcTemplate.update("UPDATE data_sync_run SET status='FAILED',active_slot=NULL,error_message=?,finished_at=NOW() WHERE id=?", truncate(message, 1000), runId); taskTraceService.markFailed(traceId, message); }
    /** 记录运行取消并同步任务追踪状态。 */
    private void finishCancelled(Long runId, String traceId) { jdbcTemplate.update("UPDATE data_sync_run SET status='CANCELLED',active_slot=NULL,error_message='cancelled',finished_at=NOW() WHERE id=?", runId); taskTraceService.completeCancellation(traceId); }
    /** 创建运行及逐表记录，并使用 JDBC 生成键避免跨连接读取 LAST_INSERT_ID。 */
    private Long createRunRecord(DataSyncModels.PlanView plan, String traceId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String jobId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO data_sync_run(plan_id,trace_id,owner_user_id,server_id,agent_job_id,status,active_slot,total_tables)
                VALUES (?,?,?,?,?,'RUNNING',1,?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, plan.id());
            statement.setString(2, traceId);
            statement.setLong(3, plan.ownerUserId());
            if (plan.serverId() == null) statement.setNull(4, Types.BIGINT); else statement.setLong(4, plan.serverId());
            statement.setString(5, jobId);
            statement.setInt(6, plan.tables().size());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new BusinessException("dataSync.executionFailed");
        Long runId = key.longValue();
        try {
            for (DataSyncModels.TableMapping table : plan.tables()) {
                jdbcTemplate.update("INSERT INTO data_sync_run_table(run_id,source_table,target_table,status) VALUES (?,?,?,'PENDING')",
                    runId, qualified(table.sourceSchema(), table.sourceTable()), qualified(table.targetSchema(), table.targetTable()));
            }
            return runId;
        } catch (RuntimeException exception) {
            jdbcTemplate.update("UPDATE data_sync_run SET status='FAILED',error_message=?,finished_at=NOW() WHERE id=?",
                truncate(exception.getMessage(), 1000), runId);
            throw exception;
        }
    }
    /** 定时任务使用计划所有者启动运行。 */
    private void runAsScheduler(DataSyncModels.PlanView plan) { if (!running.containsKey(plan.id())) { try { runInternal(plan); } catch (RuntimeException ignored) { } } }
    /** 调度运行的内部入口，绕开当前请求认证上下文。 */
    private void runInternal(DataSyncModels.PlanView plan) {
        String traceId = taskTraceService.create(null, plan.ownerUserId(), "DATA_SYNC", "SCHEDULE", "INTERNAL",
            "scheduler", new TraceSnapshot("{}", "{}"));
        try {
            Long runId = createRunRecord(plan, traceId);
            submit(runId, plan, traceId);
        } catch (RuntimeException exception) {
            taskTraceService.markFailed(traceId, safeError(exception));
            throw exception;
        }
    }
    /** 在线程开始前登记 Future，避免快速失败后遗留错误的运行中状态。 */
    private void submit(Long runId, DataSyncModels.PlanView plan, String traceId) { FutureTask<Void> task = new FutureTask<>(() -> { execute(runId, plan, traceId); return null; }); if (running.putIfAbsent(plan.id(), task) != null) { finishFailed(runId, traceId, "dataSync.running"); throw new BusinessException("dataSync.running"); } try { executor.execute(task); } catch (RuntimeException exception) { running.remove(plan.id(), task); finishFailed(runId, traceId, exception.getMessage()); throw exception; } }
    /** 判断计划在最近一分钟内是否到期，非法 Cron 会被忽略并留在错误日志中。 */
    private boolean due(DataSyncModels.PlanView plan) { if (plan.scheduleCron() == null || plan.scheduleCron().isBlank()) return false; try { CronExpression cron = CronExpression.parse(plan.scheduleCron()); LocalDateTime base = plan.lastRunAt() == null ? LocalDateTime.now().minusMinutes(1) : plan.lastRunAt(); LocalDateTime next = cron.next(base); return next != null && !next.isAfter(LocalDateTime.now()); } catch (IllegalArgumentException exception) { return false; } }
    /** 映射计划数据库记录。 */
    private DataSyncModels.PlanView mapPlan(java.sql.ResultSet rs) throws SQLException { Number lastRunId = (Number) rs.getObject("last_run_id"); Number serverId = (Number) rs.getObject("server_id"); return new DataSyncModels.PlanView(rs.getLong("id"), rs.getString("name"), rs.getLong("owner_user_id"), rs.getLong("source_connection_id"), rs.getLong("target_connection_id"), serverId == null ? null : serverId.longValue(), rs.getString("server_name"), rs.getString("strategy"), rs.getString("schedule_cron"), rs.getBoolean("enabled"), parseTables(rs.getString("tables_json")), timestamp(rs, "last_scheduled_at"), lastRunId == null ? null : lastRunId.longValue(), rs.getString("last_run_status"), timestamp(rs, "created_at"), timestamp(rs, "updated_at")); }
    /** 读取计划表列表。 */
    private DataSyncModels.PlanView plan(Long id) { return jdbcTemplate.query("SELECT p.*,s.name AS server_name,(SELECT r.id FROM data_sync_run r WHERE r.plan_id=p.id ORDER BY r.id DESC LIMIT 1) AS last_run_id,(SELECT r.status FROM data_sync_run r WHERE r.plan_id=p.id ORDER BY r.id DESC LIMIT 1) AS last_run_status FROM data_sync_plan p LEFT JOIN managed_server s ON s.id=p.server_id WHERE p.id=? AND p.voided=false", (rs, row) -> mapPlan(rs), id).stream().findFirst().orElseThrow(() -> BusinessException.notFound("dataSync.planNotFound")); }
    /** 获取并校验计划。 */
    private DataSyncModels.PlanView requirePlan(Long id) { if (id == null) throw BusinessException.notFound("dataSync.planNotFound"); return plan(id); }
    /** 获取运行记录并转换为内部视图。 */
    private DataSyncModels.RunView requireRun(Long id) { List<DataSyncModels.RunView> runs = jdbcTemplate.query("SELECT r.*,p.owner_user_id AS plan_owner,s.name AS server_name FROM data_sync_run r JOIN data_sync_plan p ON p.id=r.plan_id LEFT JOIN managed_server s ON s.id=r.server_id WHERE r.id=?", (rs, row) -> { Number serverId = (Number) rs.getObject("server_id"); return new DataSyncModels.RunView(rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("plan_owner"), serverId == null ? null : serverId.longValue(), rs.getString("server_name"), rs.getString("trace_id"), rs.getString("status"), rs.getInt("total_tables"), rs.getInt("completed_tables"), rs.getLong("read_rows"), rs.getLong("written_rows"), rs.getString("error_message"), timestamp(rs, "started_at"), timestamp(rs, "finished_at"), List.of()); }, id); if (runs.isEmpty()) throw BusinessException.notFound("dataSync.runNotFound"); return runs.get(0); }
    /** 查询运行逐表统计。 */
    private DataSyncModels.RunView runView(Long id) { DataSyncModels.RunView base = requireRun(id); List<DataSyncModels.RunTableView> tables = jdbcTemplate.query("SELECT * FROM data_sync_run_table WHERE run_id=? ORDER BY id", (rs, row) -> new DataSyncModels.RunTableView(rs.getLong("id"), rs.getString("source_table"), rs.getString("target_table"), rs.getString("status"), rs.getLong("read_rows"), rs.getLong("inserted_rows"), rs.getLong("updated_rows"), rs.getString("error_message"), timestamp(rs, "started_at"), timestamp(rs, "finished_at")), id); return new DataSyncModels.RunView(base.id(), base.planId(), base.ownerUserId(), base.serverId(), base.serverName(), base.traceId(), base.status(), base.totalTables(), base.completedTables(), base.readRows(), base.writtenRows(), base.errorMessage(), base.startedAt(), base.finishedAt(), tables); }
    /** 校验计划所有者，管理员仍需使用管理员拥有的资源。 */
    private void requireOwner(Long ownerId) { AuthUser user = AuthContext.require(); if (!user.roles().contains("ADMIN") && !user.id().equals(ownerId)) throw BusinessException.forbidden("dataSync.accessForbidden"); }
    /** 反序列化表配置。 */
    private List<DataSyncModels.TableMapping> parseTables(String value) { try { return objectMapper.readValue(value, new TypeReference<>() { }); } catch (Exception exception) { throw new BusinessException("dataSync.planInvalid"); } }
    /** 序列化表配置。 */
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new BusinessException("dataSync.planInvalid"); } }
    /** 返回数据库默认 catalog。 */
    private String catalog(Connection connection, WorkflowConnectionService.StoredConnection config) throws SQLException { return "MYSQL".equals(config.connectionType()) ? connection.getCatalog() : null; }
    /** 返回数据库默认 schema。 */
    private String schemaOrNull(String schema, WorkflowConnectionService.StoredConnection config) { if (schema != null && !schema.isBlank()) return schema; return "POSTGRESQL".equals(config.connectionType()) ? "public" : null; }
    /** 标识符只允许字母、数字和下划线，且首字符不能是数字。 */
    private boolean validIdentifier(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_$]{0,127}"); }
    /** 拼接展示用限定名称。 */
    private String qualified(String schema, String table) { return schema == null || schema.isBlank() ? text(table) : text(schema) + "." + text(table); }
    /** 规范文本。 */
    private String text(String value) { return value == null ? "" : value.trim(); }
    /** 空文本转为 SQL NULL。 */
    private String blankToNull(String value) { String normalized = text(value); return normalized.isBlank() ? null : normalized; }
    /** 校验策略。 */
    private String strategy(String value) { String normalized = text(value).toUpperCase(Locale.ROOT); if (!STRATEGIES.contains(normalized)) throw new BusinessException("dataSync.strategyInvalid"); return normalized; }
    /** 截断外部数据库错误。 */
    private String truncate(String value, int max) { String normalized = text(value); return normalized.length() <= max ? normalized : normalized.substring(0, max); }
    /** 截断并避免向接口回传凭据内容。 */
    private String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "dataSync.executionFailed";
        return truncate(message.replaceAll("(?i)(password|secret|token)(?:=|\\\"\\s*:\\s*\\\")[^\\s,}\"]+", "$1=******"), 1000);
    }
    /** 转换 SQL 时间。 */
    private LocalDateTime timestamp(java.sql.ResultSet rs, String column) throws SQLException { java.sql.Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime(); }
    record TableResult(long readRows, long writtenRows) { }
    record WorkerTableResult(String sourceTable, String targetTable, String status,
                             long readRows, long writtenRows, String error) { }
    record WorkerProgress(String status, int completedTables, long readRows, long writtenRows,
                          List<WorkerTableResult> tables, String error) { }
    private record PendingRemoteRun(Long id, String jobId, String status) { }
}
