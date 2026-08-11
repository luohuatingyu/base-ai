package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        properties.getWorkflow().setPluginWorkerInternalToken("x".repeat(24));
        properties.getWorkflow().setMarketplaceTimeoutSeconds(30);
        properties.getWorkflow().setPluginWorkerTimeoutSeconds(1);
        WorkflowPluginWorkerClient client = new WorkflowPluginWorkerClient(new ObjectMapper(), properties);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> client.inspect("DIFY", "vendor/plugin", "1.0.0", new byte[]{1}, "a".repeat(64)));

        assertEquals("workflow.pluginWorkerUnavailable", exception.getMessageKey());
    }

    /** Worker 结果必须携带来源对应的 ABI 版本，旧版本不得进入持久化缓存。 */
    @Test
    void validatesWorkerHostAbiVersion() throws Exception {
        String fingerprint = "a".repeat(64);
        int[] requestCount = {0};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/packages/inspect", exchange -> {
            byte[] body = ("""
                {"fingerprint":"%s","runtimeLanguage":"node","hostAbiVersion":%d,"components":[
                  {"externalId":"action","name":"Action","componentType":"ACTION","schema":[],
                   "credentialSchema":[],"sourcePath":"node.js","compatibilityStatus":"SUPPORTED",
                   "compatibilityReason":""}]}
                """).formatted(fingerprint, requestCount[0]++ == 0 ? 3 : 2).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PlatformProperties properties = new PlatformProperties();
        String workerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.getWorkflow().setDifyPluginWorkerUrl(workerUrl);
        properties.getWorkflow().setN8nPluginWorkerUrl(workerUrl);
        properties.getWorkflow().setPluginWorkerInternalToken("x".repeat(24));
        WorkflowPluginWorkerClient client = new WorkflowPluginWorkerClient(new ObjectMapper(), properties);

        assertEquals(3, client.inspect("N8N", "pkg", "1", new byte[]{1}, fingerprint).hostAbiVersion());
        BusinessException outdated = assertThrows(BusinessException.class,
            () -> client.inspect("DIFY", "pkg", "1", new byte[]{1}, fingerprint));
        assertEquals("workflow.pluginWorkerResponseInvalid", outdated.getMessageKey());
    }
}
