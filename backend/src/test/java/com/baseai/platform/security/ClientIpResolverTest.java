package com.baseai.platform.security;

import com.baseai.platform.config.PlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {
    /** 非可信直连来源携带的转发头必须被忽略。 */
    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        HttpServletRequest request = request("203.0.113.8", "198.51.100.7");

        assertEquals("203.0.113.8", resolver.resolve(request));
    }

    /** 从右向左剥离连续可信代理后应返回第一个外部客户端地址。 */
    @Test
    void resolvesClientAcrossTrustedProxyChain() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        HttpServletRequest request = request("10.0.0.2", "198.51.100.7, 10.0.0.1");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    /** 可信代理发送格式错误的转发链时必须安全回退到直连地址。 */
    @Test
    void fallsBackToPeerForMalformedForwardedChain() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        HttpServletRequest request = request("10.0.0.2", "198.51.100.7, attacker.example");

        assertEquals("10.0.0.2", resolver.resolve(request));
    }

    /** 使用指定可信网段创建客户端地址解析器。 */
    private static ClientIpResolver resolver(List<String> trustedCidrs) {
        PlatformProperties properties = new PlatformProperties();
        properties.getProxy().setTrustedCidrs(trustedCidrs);
        return new ClientIpResolver(new ApiKeyCidrMatcher(), properties);
    }

    /** 创建固定直连地址和转发头的请求对象。 */
    private static HttpServletRequest request(String remoteAddress, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddress);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }
}
