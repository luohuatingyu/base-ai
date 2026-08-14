package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPluginWorkerClientTest {
    private HttpServer server;

    /** 停止用例创建的本地 Worker，避免测试结束后遗留监听端口。 */
    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** Worker 请求必须使用独立超时，不能继续复用较短的市场请求超时。 */
    @Test
    void appliesDedicatedPluginWorkerTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/packages/inspect", exchange -> {
            try {
                Thread.sleep(1_500);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
                // 客户端按预期超时后可能提前关闭连接。
            } finally {
                exchange.close();
            }
        });
        server.start();
        PlatformProperties properties = new PlatformProperties();
        String workerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.getWorkflow().setDifyPluginWorkerUrl(workerUrl);
        properties.getWorkflow().setN8nPluginWorkerUrl(workerUrl);
        properties.getWorkflow().setDifyPluginWorkerInternalToken("d".repeat(24));
        properties.getWorkflow().setN8nPluginWorkerInternalToken("n".repeat(24));
        properties.getWorkflow().setMarketplaceTimeoutSeconds(30);
        properties.getWorkflow().setPluginWorkerTimeoutSeconds(1);
        WorkflowPluginWorkerClient client = new WorkflowPluginWorkerClient(new ObjectMapper(), properties, lifecycle());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> client.inspect("DIFY", "vendor/plugin", "1.0.0", new byte[]{1}, "a".repeat(64)));

        assertEquals("workflow.pluginWorkerUnavailable", exception.getMessageKey());
    }

    /** Worker 结果必须携带来源对应的 ABI 版本，旧版本不得进入持久化缓存。 */
    @Test
    void validatesWorkerHostAbiVersion() throws Exception {
        String fingerprint = "a".repeat(64);
        int[] requestCount = {0};
        int[] versions = {5, 6, 5};
        List<String> tokens = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/packages/inspect", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = ("""
                {"fingerprint":"%s","runtimeLanguage":"node","hostAbiVersion":%d,"licenseName":"MIT",
                 "licenseUrl":"https://spdx.org/licenses/MIT.html","externalServices":[{"name":"API","domain":"api.example.com"}],"components":[
                  {"externalId":"action","name":"Action","componentType":"ACTION","localization":{"name":{"en-US":"Action"}},"schema":[],
                   "credentialSchema":[],"sourcePath":"node.js","compatibilityStatus":"SUPPORTED",
                   "compatibilityReason":""}]}
                """).formatted(fingerprint, versions[requestCount[0]++]).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PlatformProperties properties = new PlatformProperties();
        String workerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.getWorkflow().setDifyPluginWorkerUrl(workerUrl);
        properties.getWorkflow().setN8nPluginWorkerUrl(workerUrl);
        properties.getWorkflow().setDifyPluginWorkerInternalToken("d".repeat(24));
        properties.getWorkflow().setN8nPluginWorkerInternalToken("n".repeat(24));
        WorkflowPluginWorkerClient client = new WorkflowPluginWorkerClient(new ObjectMapper(), properties, lifecycle());

        WorkflowPluginWorkerClient.WorkerPackage n8n = client.inspect("N8N", "pkg", "1", new byte[]{1}, fingerprint);
        assertEquals(5, n8n.hostAbiVersion());
        assertEquals("n".repeat(24), tokens.get(0));
        assertEquals("MIT", n8n.licenseName());
        assertEquals("api.example.com", n8n.externalServices().get(0).domain());
        WorkflowPluginWorkerClient.WorkerPackage dify = client.inspect("DIFY", "pkg", "1", new byte[]{1}, fingerprint);
        assertEquals(6, dify.hostAbiVersion());
        assertEquals("d".repeat(24), tokens.get(1));
        assertEquals("Action", dify.components().get(0).localization().path("name").path("en-US").asText());
        BusinessException outdated = assertThrows(BusinessException.class,
            () -> client.inspect("DIFY", "pkg", "1", new byte[]{1}, fingerprint));
        assertEquals("workflow.pluginWorkerResponseInvalid", outdated.getMessageKey());
    }

    /** 创建允许测试请求进入本地 HTTP Worker 的适配器生命周期替身。 */
    @SuppressWarnings("unchecked")
    private WorkflowAdapterLifecycleService lifecycle() {
        WorkflowAdapterLifecycleService lifecycle = mock(WorkflowAdapterLifecycleService.class);
        when(lifecycle.withEnabled(anyString(), any(Supplier.class)))
            .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());
        return lifecycle;
    }
}
