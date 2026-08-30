package com.baseai.platform.document;

import org.apache.tika.config.TikaTaskTimeout;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.SecureContentHandler;
import org.xml.sax.ContentHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 在无网络、低权限容器内运行 Apache Tika，并只通过 Unix Socket 接收有限请求。 */
public final class DocumentParserWorker {
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int MAX_METADATA_NAME_BYTES = 256;
    private static final int MAX_METADATA_VALUE_BYTES = 8192;
    private static final EmbeddedDocumentExtractor NO_EMBEDDED_DOCUMENTS = new EmbeddedDocumentExtractor() {
        /** 禁止解析压缩包或文档中的嵌入对象，缩小攻击面和资源放大风险。 */
        @Override
        public boolean shouldParseEmbedded(Metadata metadata) { return false; }

        /** 嵌入对象被明确忽略，不产生正文或副作用。 */
        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml) { }
    };

    private DocumentParserWorker() { }

    /** 读取受限环境变量并启动常驻 Unix Socket 服务。 */
    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        serve(config);
    }

    /** 创建有限线程池并持续接受 Backend 的本地套接字连接。 */
    static void serve(Config config) throws Exception {
        Path socket = config.socketPath().toAbsolutePath().normalize();
        Files.createDirectories(socket.getParent());
        if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(socket)) throw new IOException("parser socket must not be a symbolic link");
            Files.delete(socket);
        }
        ExecutorService connections = boundedPool(config.concurrency(), "document-parser-connection");
        ExecutorService parsers = boundedPool(config.concurrency(), "document-parser-task");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE
            ));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanup(socket, connections, parsers),
                "document-parser-shutdown"));
            while (true) {
                SocketChannel channel = server.accept();
                try {
                    connections.execute(() -> handle(channel, config, parsers));
                } catch (RuntimeException exception) {
                    channel.close();
                }
            }
        } finally {
            cleanup(socket, connections, parsers);
        }
    }

    /** 读取单个请求，在硬超时内返回有限结果，否则终止整个解析进程。 */
    private static void handle(SocketChannel channel, Config config, ExecutorService parsers) {
        boolean timedOut = false;
        try (channel;
             DataInputStream input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel)));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)))) {
            DocumentParserProtocol.Request request = DocumentParserProtocol.readRequest(
                input, config.maximumDocumentBytes(), config.maximumCharacters());
            Future<DocumentParser.Result> future = parsers.submit(() -> parse(request, config.timeoutMillis()));
            try {
                DocumentParser.Result result = future.get(config.timeoutMillis(), TimeUnit.MILLISECONDS);
                DocumentParserProtocol.writeSuccess(output, result, request.maximumCharacters() * 4);
            } catch (TimeoutException exception) {
                future.cancel(true);
                DocumentParserProtocol.writeFailure(output, DocumentParser.Reason.TIMEOUT);
                timedOut = true;
            } catch (ExecutionException exception) {
                DocumentParserProtocol.writeFailure(output, reason(exception.getCause()));
            }
            output.flush();
        } catch (Exception exception) {
            // 非法或中断连接只影响当前请求，且不向客户端泄漏解析器细节。
        } finally {
            if (timedOut) Runtime.getRuntime().halt(124);
        }
    }

    /** 使用安全内容处理器提取正文，并禁止所有嵌入对象解析。 */
    static DocumentParser.Result parse(DocumentParserProtocol.Request request, long timeoutMillis) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, request.fileName());
        BodyContentHandler body = new BodyContentHandler(request.maximumCharacters());
        try (TikaInputStream stream = TikaInputStream.get(request.content())) {
            SecureContentHandler secure = new SecureContentHandler(body, stream);
            secure.setOutputThreshold(Math.min(1_000_000L, request.maximumCharacters()));
            secure.setMaximumCompressionRatio(100L);
            secure.setMaximumDepth(100);
            secure.setMaximumPackageEntryDepth(20);
            ParseContext context = new ParseContext();
            context.set(EmbeddedDocumentExtractor.class, NO_EMBEDDED_DOCUMENTS);
            context.set(TikaTaskTimeout.class, new TikaTaskTimeout(timeoutMillis));
            new AutoDetectParser().parse(stream, secure, metadata, context);
            String text = body.toString().trim();
            if (text.isBlank()) throw new DocumentParser.ParseException(DocumentParser.Reason.EMPTY);
            return new DocumentParser.Result(text, limitedMetadata(metadata));
        } catch (DocumentParser.ParseException exception) {
            throw exception;
        } catch (Exception exception) {
            if (WriteLimitReachedException.isWriteLimitReached(exception)) {
                throw new DocumentParser.ParseException(DocumentParser.Reason.TOO_LARGE, exception);
            }
            throw new DocumentParser.ParseException(DocumentParser.Reason.INVALID, exception);
        }
    }

    /** 把内部异常压缩为跨进程协议允许的稳定分类。 */
    private static DocumentParser.Reason reason(Throwable cause) {
        if (cause instanceof DocumentParser.ParseException exception) return exception.reason();
        return DocumentParser.Reason.INVALID;
    }

    /** 仅保留有限、非空且 UTF-8 长度受控的元数据。 */
    private static Map<String, String> limitedMetadata(Metadata metadata) {
        Map<String, String> result = new LinkedHashMap<>();
        Arrays.stream(metadata.names()).sorted().limit(MAX_METADATA_ENTRIES).forEach(name -> {
            String safeName = truncateUtf8(name, MAX_METADATA_NAME_BYTES);
            String safeValue = truncateUtf8(metadata.get(name), MAX_METADATA_VALUE_BYTES);
            if (!safeName.isBlank()) result.putIfAbsent(safeName, safeValue);
        });
        return result;
    }

    /** 按 UTF-8 字节上限截断字符串，避免多字节字符越过协议边界。 */
    private static String truncateUtf8(String value, int maximumBytes) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "_");
        while (result.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        }
        return result;
    }

    /** 创建固定并发和有限等待队列，过载时快速拒绝新任务。 */
    private static ExecutorService boundedPool(int concurrency, String threadName) {
        return new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(concurrency * 2), runnable -> {
                Thread thread = new Thread(runnable, threadName);
                thread.setDaemon(false);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 停止工作线程并移除当前进程创建的套接字。 */
    private static void cleanup(Path socket, ExecutorService connections, ExecutorService parsers) {
        connections.shutdownNow();
        parsers.shutdownNow();
        try { Files.deleteIfExists(socket); } catch (IOException ignored) { }
    }

    /** 解析容器唯一允许的运行配置。 */
    record Config(Path socketPath, int maximumDocumentBytes, int maximumCharacters, long timeoutMillis,
                  int concurrency) {
        /** 从环境读取并夹紧所有资源配置。 */
        static Config fromEnvironment() {
            return new Config(
                Path.of(environment("DOCUMENT_PARSER_SOCKET", "/run/document-parser/parser.sock")),
                integer("DOCUMENT_PARSER_MAX_BYTES", 10 * 1024 * 1024, 1, 20 * 1024 * 1024),
                integer("DOCUMENT_PARSER_MAX_CHARACTERS", 2_000_000, 1, 2_000_000),
                integer("DOCUMENT_PARSER_TIMEOUT_SECONDS", 20, 1, 120) * 1000L,
                integer("DOCUMENT_PARSER_CONCURRENCY", 2, 1, 8)
            );
        }

        /** 返回非空环境值，否则使用安全默认值。 */
        private static String environment(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        /** 读取整数并约束到明确上下限。 */
        private static int integer(String name, int fallback, int minimum, int maximum) {
            try { return Math.max(minimum, Math.min(Integer.parseInt(environment(name, String.valueOf(fallback))), maximum)); }
            catch (NumberFormatException exception) { return fallback; }
        }
    }
}
