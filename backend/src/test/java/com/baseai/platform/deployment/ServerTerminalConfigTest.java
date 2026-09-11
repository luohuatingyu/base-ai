package com.baseai.platform.deployment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 TLS 代理场景下的来源判定，拒绝跨站及畸形来源。 */
class ServerTerminalConfigTest {
    /** 来源必须与原始 Host 的主机及有效端口完全匹配。 */
    @ParameterizedTest
    @CsvSource({
        "https://localhost,localhost,true", "https://localhost:443,localhost,true",
        "http://localhost:5173,localhost:5173,true", "https://example.test:444,example.test:444,true",
        "https://evil.test,example.test,false", "https://example.test:444,example.test,false",
        "https://example.test@evil.test,example.test,false", "https://example.test/path,example.test,false",
        "https://example.test?query,example.test,false", "https://example.test#fragment,example.test,false",
        "null,example.test,false", ",example.test,false", "https://example.test,,false",
        "file://example.test,example.test,false", "https://example.test,example.test@evil.test,false"
    })
    void validatesExternalOrigin(String origin, String host, boolean accepted) {
        assertEquals(accepted, ServerTerminalConfig.sameOrigin(origin, host));
    }
}
