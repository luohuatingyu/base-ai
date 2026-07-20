package com.baseai.platform.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {
    @Test
    void successResponseUsesStableProtocol() {
        ApiResponse<String> response = ApiResponse.success("ok");
        assertTrue(response.success());
        assertEquals("SUCCESS", response.code());
        assertEquals("ok", response.data());
    }
}
