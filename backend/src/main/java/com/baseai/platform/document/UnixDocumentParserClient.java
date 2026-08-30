package com.baseai.platform.document;

import com.baseai.platform.config.PlatformProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** 只通过私有 Unix Socket 调用隔离文档解析容器。 */
@Component
public class UnixDocumentParserClient implements DocumentParser {
    private final Path socketPath;
    private final int maximumDocumentBytes;
    private final int maximumCharacters;
    private final int maximumResponseBytes;

    /** 从类型化平台配置创建有限客户端。 */
    public UnixDocumentParserClient(PlatformProperties properties) {
        PlatformProperties.DocumentParser config = properties.getDocumentParser();
        socketPath = Path.of(config.getSocketPath()).toAbsolutePath().normalize();
        maximumDocumentBytes = bounded(config.getMaxDocumentBytes(), 1, 20 * 1024 * 1024);
        maximumCharacters = bounded(config.getMaxCharacters(), 1, 2_000_000);
        maximumResponseBytes = maximumCharacters * 4;
    }

    /** 校验请求后通过单次 Unix Socket 连接完成解析。 */
    @Override
    public Result parse(byte[] content, String fileName, int requestedCharacters) {
        if (content == null || content.length == 0) throw new ParseException(Reason.EMPTY);
        if (content.length > maximumDocumentBytes) throw new ParseException(Reason.TOO_LARGE);
        if (requestedCharacters < 1 || requestedCharacters > maximumCharacters) {
            throw new ParseException(Reason.TOO_LARGE);
        }
        String safeName = safeFileName(fileName);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)));
            DocumentParserProtocol.writeRequest(output, safeName, requestedCharacters, content);
            output.flush();
            DataInputStream input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel)));
            return DocumentParserProtocol.readResponse(input, maximumResponseBytes);
        } catch (ParseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ParseException(Reason.UNAVAILABLE, exception);
        }
    }

    /** 去除路径和控制字符，并限制协议中的 UTF-8 文件名长度。 */
    private String safeFileName(String value) {
        String normalized = value == null ? "document" : value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
            .replaceAll("[\\p{Cntrl}]", "_").trim();
        if (normalized.isEmpty()) normalized = "document";
        while (normalized.getBytes(StandardCharsets.UTF_8).length > 512) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** 把配置值约束在容器协议的硬上限内。 */
    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
