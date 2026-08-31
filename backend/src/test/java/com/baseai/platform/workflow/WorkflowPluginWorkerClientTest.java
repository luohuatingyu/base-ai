package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.InternalRequestSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
        int[] versions = {6, 7, 6};
        List<Boolean> signatures = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/packages/inspect", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String secret = requestCount[0] == 0 ? "n".repeat(24) : "d".repeat(24);
            signatures.add(InternalRequestSigner.verify(secret, exchange.getRequestMethod(), "/packages/inspect",
                requestBody, exchange.getRequestHeaders().getFirst(InternalRequestSigner.TIMESTAMP),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.NONCE),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.TARGET),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.CONTENT_SHA256),
                exchange.getRequestHeaders().getFirst(InternalRequestSigner.SIGNATURE), Instant.now(), 60));
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
        assertEquals(6, n8n.hostAbiVersion());
        assertEquals(true, signatures.get(0));
        assertEquals("MIT", n8n.licenseName());
        assertEquals("api.example.com", n8n.externalServices().get(0).domain());
        WorkflowPluginWorkerClient.WorkerPackage dify = client.inspect("DIFY", "pkg", "1", new byte[]{1}, fingerprint);
        assertEquals(7, dify.hostAbiVersion());
        assertEquals(true, signatures.get(1));
        assertEquals("Action", dify.components().get(0).localization().path("name").path("en-US").asText());
        BusinessException outdated = assertThrows(BusinessException.class,
            () -> client.inspect("DIFY", "pkg", "1", new byte[]{1}, fingerprint));
        assertEquals("workflow.pluginWorkerResponseInvalid", outdated.getMessageKey());
    }

    /** 带网关路径的 Worker 根地址必须保留前缀，同时签名最终 Worker 路径。 */
    @Test
    void preservesGatewayPrefixAndSignsRewrittenTarget() throws Exception {
        String fingerprint = "a".repeat(64);
        AtomicReference<String> signedTarget = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/workers/n8n/packages/inspect", exchange -> {
            signedTarget.set(exchange.getRequestHeaders().getFirst(InternalRequestSigner.TARGET));
            byte[] body = ("{\"fingerprint\":\"%s\",\"runtimeLanguage\":\"node\",\"hostAbiVersion\":6,"
                + "\"components\":[{\"externalId\":\"action\",\"name\":\"Action\",\"componentType\":\"ACTION\","
                + "\"schema\":[],\"credentialSchema\":[],\"sourcePath\":\"node.js\","
                + "\"compatibilityStatus\":\"SUPPORTED\",\"compatibilityReason\":\"\"}]}"
                ).formatted(fingerprint).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PlatformProperties properties = new PlatformProperties();
        String gateway = "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/workers/n8n";
        properties.getWorkflow().setN8nPluginWorkerUrl(gateway);
        properties.getWorkflow().setDifyPluginWorkerUrl(gateway);
        properties.getWorkflow().setN8nPluginWorkerInternalToken("n".repeat(24));
        properties.getWorkflow().setDifyPluginWorkerInternalToken("d".repeat(24));

        new WorkflowPluginWorkerClient(new ObjectMapper(), properties, lifecycle())
            .inspect("N8N", "pkg", "1", new byte[]{1}, fingerprint);

        assertEquals("/packages/inspect", signedTarget.get());
    }

    /** 插件调用必须把数据库批准域名作为独立数组发送，不能回退为全局代理权限。 */
    @Test
    void sendsOnlyApprovedPluginDomainsForInvocation() throws Exception {
        AtomicReference<String> requestJson = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invocations", exchange -> {
            requestJson.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"success\":true,\"output\":{\"ok\":true}}".getBytes(StandardCharsets.UTF_8);
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
        ObjectMapper mapper = new ObjectMapper();
        WorkflowPluginWorkerClient client = new WorkflowPluginWorkerClient(mapper, properties, lifecycle());

        client.invoke("N8N", "a".repeat(64), "action", "invoke", mapper.createObjectNode(),
            mapper.createObjectNode(), mapper.nullNode(), mapper.createObjectNode(), null,
            List.of("api.example.com", "api.example.com"));

        assertEquals("[\"api.example.com\"]", mapper.readTree(requestJson.get()).path("allowedDomains").toString());
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
