package com.baseai.platform.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentParserProtocolTest {
    /** 请求往返必须完整保留有限文件名、字符上限和原始正文。 */
    @Test
    void roundTripsBoundedRequest() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DocumentParserProtocol.writeRequest(new DataOutputStream(bytes), "说明.txt", 200, "正文".getBytes(StandardCharsets.UTF_8));
        DocumentParserProtocol.Request request = DocumentParserProtocol.readRequest(
            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 100, 500);
        assertEquals("说明.txt", request.fileName());
        assertEquals(200, request.maximumCharacters());
        assertArrayEquals("正文".getBytes(StandardCharsets.UTF_8), request.content());
    }

    /** 接收端必须在分配正文前拒绝超出本地上限的声明长度。 */
    @Test
    void rejectsRequestBeyondReceiverLimit() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DocumentParserProtocol.writeRequest(new DataOutputStream(bytes), "note.txt", 200, new byte[8]);
        assertThrows(IOException.class, () -> DocumentParserProtocol.readRequest(
            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 7, 500));
    }

    /** 成功响应必须保留正文和有限元数据。 */
    @Test
    void roundTripsSuccessfulResponse() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DocumentParserProtocol.writeSuccess(new DataOutputStream(bytes),
            new DocumentParser.Result("hello", Map.of("Content-Type", "text/plain")), 100);
        DocumentParser.Result result = DocumentParserProtocol.readResponse(
            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 100);
        assertEquals("hello", result.text());
        assertEquals("text/plain", result.metadata().get("Content-Type"));
    }

    /** 失败响应只能映射为协议定义的稳定错误类型。 */
    @Test
    void mapsStableFailureReason() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DocumentParserProtocol.writeFailure(new DataOutputStream(bytes), DocumentParser.Reason.TIMEOUT);
        DocumentParser.ParseException exception = assertThrows(DocumentParser.ParseException.class,
            () -> DocumentParserProtocol.readResponse(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 100));
        assertEquals(DocumentParser.Reason.TIMEOUT, exception.reason());
    }
}
