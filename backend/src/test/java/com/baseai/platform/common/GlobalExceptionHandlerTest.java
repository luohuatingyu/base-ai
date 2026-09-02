package com.baseai.platform.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {
    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    /** 超出 multipart 限制必须返回明确的 413，而不是通用的 400。 */
    @Test
    void mapsMultipartLimitToPayloadTooLarge() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.requestTooLarge", Locale.US, "Request body too large");
        LocaleContextHolder.setLocale(Locale.US);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);

        ResponseEntity<ApiResponse<Void>> response = handler.uploadTooLarge(new MaxUploadSizeExceededException(10));

        assertEquals(413, response.getStatusCode().value());
        assertFalse(response.getBody().success());
        assertEquals(413, response.getBody().code());
        assertEquals("Request body too large", response.getBody().message());
    }
}
