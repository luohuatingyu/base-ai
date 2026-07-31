package com.baseai.platform.automation;

import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ApiTriggerServiceResponseDecodingTest {
    private final ApiTriggerService service = new ApiTriggerService(mock(JdbcTemplate.class), new ObjectMapper(),
        mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties());

    /** JSON 未声明字符集时应按 UTF-8 解码中文响应。 */
    @Test
    void decodesJsonWithoutCharsetAsUtf8() {
        byte[] body = "{\"message\":\"操作成功\"}".getBytes(StandardCharsets.UTF_8);

        String decoded = ReflectionTestUtils.invokeMethod(service, "decodeResponseBody", body, MediaType.APPLICATION_JSON);

        assertEquals("{\"message\":\"操作成功\"}", decoded);
    }

    /** 上游显式声明字符集时应按声明解码响应。 */
    @Test
    void decodesResponseUsingDeclaredCharset() {
        byte[] body = "操作成功".getBytes(StandardCharsets.UTF_16LE);
        MediaType contentType = new MediaType("text", "plain", StandardCharsets.UTF_16LE);

        String decoded = ReflectionTestUtils.invokeMethod(service, "decodeResponseBody", body, contentType);

        assertEquals("操作成功", decoded);
    }
}
