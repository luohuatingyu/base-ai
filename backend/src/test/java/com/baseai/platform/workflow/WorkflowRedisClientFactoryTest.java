package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRedisClientFactoryTest {
    /** Lettuce 在 Socket 解析阶段必须调用工作流策略并复用其已验证地址。 */
    @Test
    void resolvesRedisHostThroughNetworkPolicy() throws Exception {
        WorkflowNetworkPolicy policy = mock(WorkflowNetworkPolicy.class);
        InetAddress[] addresses = {InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})};
        when(policy.resolveVerifiedHost("redis.example.com")).thenReturn(addresses);
        WorkflowRedisClientFactory factory = new WorkflowRedisClientFactory(policy);
        try {
            assertArrayEquals(addresses, factory.resolve("redis.example.com"));
            verify(policy).resolveVerifiedHost("redis.example.com");
        } finally {
            factory.close();
        }
    }
}
