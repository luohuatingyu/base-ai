package com.baseai.platform.document;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Backend 与解析容器之间的定长、有限二进制协议。 */
final class DocumentParserProtocol {
    private static final int MAGIC = 0x42414944;
    private static final int VERSION = 1;
    private static final int SUCCESS = 0;
    private static final int FAILURE = 1;
    private static final int MAX_FILE_NAME_BYTES = 512;
    private static final int MAX_ERROR_BYTES = 64;
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int MAX_METADATA_NAME_BYTES = 256;
    private static final int MAX_METADATA_VALUE_BYTES = 8192;

    private DocumentParserProtocol() { }

    /** 写入带魔数和明确长度的解析请求。 */
    static void writeRequest(DataOutputStream output, String fileName, int maximumCharacters, byte[] content)
        throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(maximumCharacters);
        writeBytes(output, fileName.getBytes(StandardCharsets.UTF_8));
        writeBytes(output, content);
    }

    /** 读取请求并在分配内存前校验所有长度。 */
    static Request readRequest(DataInputStream input, int maximumDocumentBytes, int maximumCharacters)
        throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("protocol header invalid");
        int requestedCharacters = input.readInt();
        if (requestedCharacters < 1 || requestedCharacters > maximumCharacters) {
            throw new IOException("character limit invalid");
        }
        String fileName = new String(readBytes(input, MAX_FILE_NAME_BYTES), StandardCharsets.UTF_8);
        byte[] content = readBytes(input, maximumDocumentBytes);
        if (content.length == 0) throw new IOException("document empty");
        return new Request(fileName, requestedCharacters, content);
    }

    /** 写入成功结果，并逐项限制元数据大小。 */
    static void writeSuccess(DataOutputStream output, DocumentParser.Result result, int maximumTextBytes)
        throws IOException {
        byte[] text = result.text().getBytes(StandardCharsets.UTF_8);
        if (text.length == 0 || text.length > maximumTextBytes) throw new IOException("parsed text size invalid");
        if (result.metadata().size() > MAX_METADATA_ENTRIES) throw new IOException("metadata count invalid");
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeByte(SUCCESS);
        writeBytes(output, text);
        output.writeInt(result.metadata().size());
        for (Map.Entry<String, String> entry : result.metadata().entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            if (name.length == 0 || name.length > MAX_METADATA_NAME_BYTES || value.length > MAX_METADATA_VALUE_BYTES) {
                throw new IOException("metadata size invalid");
            }
            writeBytes(output, name);
            writeBytes(output, value);
        }
    }

    /** 写入不包含底层异常信息的稳定错误分类。 */
    static void writeFailure(DataOutputStream output, DocumentParser.Reason reason) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeByte(FAILURE);
        writeBytes(output, reason.name().getBytes(StandardCharsets.US_ASCII));
    }

    /** 读取有限响应并拒绝未知状态和尾随结构。 */
    static DocumentParser.Result readResponse(DataInputStream input, int maximumTextBytes) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("protocol header invalid");
        int status = input.readUnsignedByte();
        if (status == FAILURE) {
            String value = new String(readBytes(input, MAX_ERROR_BYTES), StandardCharsets.US_ASCII);
            DocumentParser.Reason reason;
            try {
                reason = DocumentParser.Reason.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IOException("failure reason invalid", exception);
            }
            throw new DocumentParser.ParseException(reason);
        }
        if (status != SUCCESS) throw new IOException("response status invalid");
        String text = new String(readBytes(input, maximumTextBytes), StandardCharsets.UTF_8);
        int count = input.readInt();
        if (count < 0 || count > MAX_METADATA_ENTRIES) throw new IOException("metadata count invalid");
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String name = new String(readBytes(input, MAX_METADATA_NAME_BYTES), StandardCharsets.UTF_8);
            String value = new String(readBytes(input, MAX_METADATA_VALUE_BYTES), StandardCharsets.UTF_8);
            if (name.isBlank() || metadata.putIfAbsent(name, value) != null) throw new IOException("metadata invalid");
        }
        if (text.isBlank()) throw new DocumentParser.ParseException(DocumentParser.Reason.EMPTY);
        return new DocumentParser.Result(text, metadata);
    }

    /** 写入一个非负长度和对应字节。 */
    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    /** 在读取内容前拒绝负数和超限长度。 */
    private static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) throw new IOException("field size invalid");
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new EOFException("field truncated");
        return value;
    }

    /** 解析容器接收的不可变请求。 */
    record Request(String fileName, int maximumCharacters, byte[] content) { }
}
