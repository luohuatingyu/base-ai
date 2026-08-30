package com.baseai.platform.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserWorkerTest {
    /** 隔离 Worker 必须提取普通文本并返回检测到的有限元数据。 */
    @Test
    void extractsPlainText() {
        DocumentParser.Result result = DocumentParserWorker.parse(new DocumentParserProtocol.Request(
            "note.txt", 100, "hello sandbox".getBytes(StandardCharsets.UTF_8)), 5_000);
        assertEquals("hello sandbox", result.text());
        assertTrue(result.metadata().size() <= 64);
    }

    /** 提取正文超过调用方字符上限时必须稳定拒绝。 */
    @Test
    void rejectsTextBeyondCharacterLimit() {
        DocumentParser.ParseException exception = assertThrows(DocumentParser.ParseException.class,
            () -> DocumentParserWorker.parse(new DocumentParserProtocol.Request(
                "large.txt", 5, "content larger than five".getBytes(StandardCharsets.UTF_8)), 5_000));
        assertEquals(DocumentParser.Reason.TOO_LARGE, exception.reason());
    }

    /** 压缩包内嵌文件不得被递归提取，避免压缩炸弹和复合格式扩大攻击面。 */
    @Test
    void doesNotExtractEmbeddedZipEntries() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("secret.txt"));
            zip.write("embedded secret".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        DocumentParser.ParseException exception = assertThrows(DocumentParser.ParseException.class,
            () -> DocumentParserWorker.parse(new DocumentParserProtocol.Request(
                "archive.zip", 100, bytes.toByteArray()), 5_000));
        assertEquals(DocumentParser.Reason.EMPTY, exception.reason());
    }
}
