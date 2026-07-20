package com.baseai.platform.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobRequestSnapshotSanitizerTest {

    /** 验证参数和请求头中的密码、Token 会被统一脱敏。 */
    @Test
    void masksSensitiveRequestValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret-token");
        JobRequestSnapshotSanitizer sanitizer = new JobRequestSnapshotSanitizer(new ObjectMapper());
        JobSnapshot snapshot = sanitizer.sanitize(request, new String[]{"command"},
            new Object[]{Map.of("username", "admin", "password", "secret-password")});
        assertThat(snapshot.paramsJson()).contains("admin").contains("***").doesNotContain("secret-password");
        assertThat(snapshot.headersJson()).contains("***").doesNotContain("secret-token");
    }
}
