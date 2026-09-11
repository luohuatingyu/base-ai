package com.baseai.platform.datasync;

import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** 在一次性容器中执行不依赖平台数据库和认证上下文的数据同步任务。 */
public final class DataSyncRemoteWorker {
    private static final int MAX_INPUT_BYTES = 1024 * 1024;

    private DataSyncRemoteWorker() { }

    /** 读取单次任务、执行固定动作并通过 NDJSON 输出有界结果。 */
    public static void main(String[] args) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        try {
            WorkerCommand command = objectMapper.readValue(readInput(), WorkerCommand.class);
            DataSyncService engine = DataSyncService.workerEngine(objectMapper);
            execute(objectMapper, engine, command);
        } catch (Exception exception) {
            write(objectMapper, new WorkerResponse("FAILED", List.of(), null, 0, 0, 0, List.of(), safe(exception)));
            System.exit(1);
        }
    }

    /** 只允许 TABLES、PREVIEW 和 RUN 三种远程动作。 */
    private static void execute(ObjectMapper objectMapper, DataSyncService engine, WorkerCommand command) throws Exception {
        if (command == null || command.source() == null) throw new IllegalArgumentException("dataSync.planInvalid");
        String action = text(command.action()).toUpperCase(Locale.ROOT);
        WorkflowConnectionService.StoredConnection source = connection(objectMapper, 1L, command.source());
        if ("TABLES".equals(action)) {
            List<DataSyncModels.TableView> tables = engine.workerTables(source, command.schema());
            write(objectMapper, new WorkerResponse("SUCCEEDED", tables, null, 0, 0, 0, List.of(), ""));
            return;
        }
        if (command.target() == null) throw new IllegalArgumentException("dataSync.planInvalid");
        if (!Boolean.TRUE.equals(command.target().allowWrite())) throw new IllegalArgumentException("dataSync.targetReadOnly");
        WorkflowConnectionService.StoredConnection target = connection(objectMapper, 2L, command.target());
        if ("PREVIEW".equals(action)) {
            DataSyncModels.PreviewView preview = engine.workerPreview(source, target, command.strategy(), command.tables());
            write(objectMapper, new WorkerResponse("SUCCEEDED", List.of(), preview, 0, 0, 0, List.of(), ""));
            return;
        }
        if (!"RUN".equals(action)) throw new IllegalArgumentException("dataSync.planInvalid");
        engine.workerRun(source, target, command.strategy(), command.tables(), progress ->
            write(objectMapper, new WorkerResponse(progress.status(), List.of(), null, progress.completedTables(),
                progress.readRows(), progress.writtenRows(), progress.tables(), progress.error())));
    }

    /** 将受限连接输入转换为既有 JDBC 引擎配置。 */
    private static WorkflowConnectionService.StoredConnection connection(ObjectMapper objectMapper, Long id,
                                                                           ConnectionInput input) {
        String type = text(input.type()).toUpperCase(Locale.ROOT);
        if (!List.of("MYSQL", "POSTGRESQL").contains(type)) throw new IllegalArgumentException("dataSync.connectionInvalid");
        ObjectNode config = objectMapper.createObjectNode();
        config.put("url", text(input.url()));
        config.put("username", input.username() == null ? "" : input.username());
        config.put("password", input.password() == null ? "" : input.password());
        config.put("allowWrite", Boolean.TRUE.equals(input.allowWrite()));
        return new WorkflowConnectionService.StoredConnection(id, "REMOTE_" + id, "Remote", type, config,
            0L, true, null, null);
    }

    /** 限制标准输入大小，避免内部接口被异常载荷耗尽内存。 */
    private static byte[] readInput() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = System.in.read(buffer)) >= 0) {
            total += count;
            if (total > MAX_INPUT_BYTES) throw new IOException("dataSync.planInvalid");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /** 每个进度快照输出为独立 JSON 行，便于 Agent 增量读取。 */
    private static synchronized void write(ObjectMapper objectMapper, WorkerResponse response) {
        try {
            System.out.println(objectMapper.writeValueAsString(response));
            System.out.flush();
        } catch (Exception ignored) {
            System.out.println("{\"status\":\"FAILED\",\"error\":\"dataSync.executionFailed\"}");
            System.out.flush();
        }
    }

    /** 将异常转换为稳定且不含凭据的错误文本。 */
    private static String safe(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return "dataSync.executionFailed";
        String normalized = value.replaceAll("(?i)(password|secret|token)(?:=|\\\"\\s*:\\s*\\\")[^\\s,}\\\"]+", "$1=******");
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    /** 统一空文本处理。 */
    private static String text(String value) { return value == null ? "" : value.trim(); }

    record WorkerCommand(String action, ConnectionInput source, ConnectionInput target, String strategy,
                         String schema, List<DataSyncModels.TableMapping> tables) { }

    record ConnectionInput(String type, String url, String username, String password, Boolean allowWrite) { }

    record WorkerResponse(String status, List<DataSyncModels.TableView> tables, DataSyncModels.PreviewView preview,
                          int completedTables, long readRows, long writtenRows,
                          List<DataSyncService.WorkerTableResult> tableResults, String error) { }
}
