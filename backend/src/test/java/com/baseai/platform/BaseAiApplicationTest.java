package com.baseai.platform;

import org.junit.jupiter.api.Test;

import java.security.Security;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseAiApplicationTest {
    /** 成功解析在进程生命周期内保持固定，失败解析不形成持久拒绝缓存。 */
    @Test
    void enforcesProcessLifetimeDnsPinning() {
        BaseAiApplication.enforceProcessDnsPinning();

        assertEquals("-1", Security.getProperty("networkaddress.cache.ttl"));
        assertEquals("0", Security.getProperty("networkaddress.cache.negative.ttl"));
    }
}
