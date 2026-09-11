package com.baseai.platform.datasync;

import com.baseai.platform.deployment.ServerModels;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖远程同步 Agent 的固定镜像、任务载荷和状态协议。 */
class DataSyncAgentClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** 表查询和执行必须携带所选服务器，但任务状态不得回传凭据。 */
    @Test
    void sendsManagedTargetAndReadsSanitizedJobStatus() throws Exception {
        AtomicReference<String> queryBody = new AtomicReference<>();
        AtomicReference<String> executeBody = new AtomicReference<>();
        String jobId = "0123456789abcdef0123456789abcdef";
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/data-sync/query", exchange -> respond(exchange, queryBody, """
            {"status":"SUCCEEDED","tables":[{"schema":"public","name":"orders","type":"TABLE"}]}
            """));
        agent.createContext("/data-sync/execute", exchange -> respond(exchange, executeBody,
            "{\"status\":\"RUNNING\",\"jobId\":\"" + jobId + "\"}"));
        agent.createContext("/data-sync/jobs/" + jobId, exchange -> respond(exchange, null,
            "{\"status\":\"SUCCEEDED\",\"jobId\":\"" + jobId
                + "\",\"result\":{\"status\":\"SUCCESS\",\"completedTables\":1}}"));
        agent.start();
        try {
            DataSyncAgentClient client = new DataSyncAgentClient(objectMapper,
                "http://127.0.0.1:" + agent.getAddress().getPort(),
                "internal-token-with-24-characters", "base-ai-backend:abcdef");
            ServerModels.DataSyncExecutionTarget server = server();
            WorkflowConnectionService.StoredConnection source = connection(1L, false);
            WorkflowConnectionService.StoredConnection target = connection(2L, true);

            List<DataSyncModels.TableView> tables = client.tables(server, source, "public");
            client.start(server, jobId, source, target, "APPEND", List.of(mapping()));
            DataSyncAgentClient.AgentJob job = client.job(jobId);

            assertEquals("orders", tables.get(0).name());
            assertEquals("SUCCEEDED", job.status());
            assertTrue(queryBody.get().contains("\"workerImage\":\"base-ai-backend:abcdef\""));
            assertTrue(queryBody.get().contains("\"host\":\"sync.example.com\""));
            assertTrue(executeBody.get().contains("\"password\":\"db-secret\""));
            assertFalse(job.result().toString().contains("db-secret"));
        } finally {
            agent.stop(0);
        }
    }

    /** 写入固定 JSON 响应并可选保存请求体。 */
    private void respond(com.sun.net.httpserver.HttpExchange exchange, AtomicReference<String> body, String response)
        throws java.io.IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body != null) body.set(request);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 创建带完整 SSH 凭据的内部执行目标。 */
    private ServerModels.DataSyncExecutionTarget server() {
        return new ServerModels.DataSyncExecutionTarget(9L, "sync", "SSH", "sync.example.com", 22,
            "deploy", "PASSWORD", "", "ssh-secret", "", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 7L);
    }

    /** 创建仅用于 Agent 协议的连接配置。 */
    private WorkflowConnectionService.StoredConnection connection(Long id, boolean allowWrite) throws Exception {
        JsonNode config = objectMapper.readTree("{\"url\":\"jdbc:mysql://database/orders\",\"username\":\"sync\",\"password\":\"db-secret\",\"allowWrite\":" + allowWrite + "}");
        return new WorkflowConnectionService.StoredConnection(id, "DB" + id, "Database", "MYSQL", config,
            7L, true, null, null);
    }

    /** 创建同名表映射。 */
    private DataSyncModels.TableMapping mapping() {
        return new DataSyncModels.TableMapping("", "orders", "", "orders", List.of());
    }
}
