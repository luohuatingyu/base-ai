package com.baseai.platform.document;

import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnixDocumentParserClientTest {
    /** 客户端必须只通过 Unix Socket 发送原始文档并读取有限响应。 */
    @Test
    void parsesThroughUnixSocket() throws Exception {
        Path socket = Path.of(System.getProperty("java.io.tmpdir"), "base-ai-parser-" + UUID.randomUUID() + ".sock");
        PlatformProperties properties = new PlatformProperties();
        properties.getDocumentParser().setSocketPath(socket.toString());
        CompletableFuture<DocumentParserProtocol.Request> received = new CompletableFuture<>();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> respond(server, received));
            DocumentParser.Result result = new UnixDocumentParserClient(properties).parse(
                "hello".getBytes(StandardCharsets.UTF_8), "../note.txt", 100);
            assertEquals("parsed", result.text());
            assertEquals("text/plain", result.metadata().get("Content-Type"));
            assertEquals("note.txt", received.get(5, TimeUnit.SECONDS).fileName());
            responder.get(5, TimeUnit.SECONDS);
        } finally {
            Files.deleteIfExists(socket);
        }
    }

    /** 模拟解析 Worker 读取请求并返回稳定成功响应。 */
    private void respond(ServerSocketChannel server, CompletableFuture<DocumentParserProtocol.Request> received) {
        try (SocketChannel channel = server.accept();
             DataInputStream input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel)));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)))) {
            received.complete(DocumentParserProtocol.readRequest(input, 1024, 1000));
            DocumentParserProtocol.writeSuccess(output,
                new DocumentParser.Result("parsed", Map.of("Content-Type", "text/plain")), 1000);
            output.flush();
        } catch (Exception exception) {
            received.completeExceptionally(exception);
            throw new IllegalStateException(exception);
        }
    }
}
