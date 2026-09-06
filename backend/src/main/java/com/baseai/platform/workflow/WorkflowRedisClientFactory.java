package com.baseai.platform.workflow;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DnsResolver;
import io.lettuce.core.resource.SocketAddressResolver;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

/** 创建在实际 Socket 解析阶段复用工作流网络策略的 Redis 客户端。 */
@Component
public class WorkflowRedisClientFactory {
    private final DnsResolver dnsResolver;
    private final ClientResources resources;

    /** 将 Lettuce 的独立解析器替换为返回已验证地址的策略解析器。 */
    public WorkflowRedisClientFactory(WorkflowNetworkPolicy networkPolicy) {
        this.dnsResolver = networkPolicy::resolveVerifiedHost;
        this.resources = DefaultClientResources.builder()
            .socketAddressResolver(SocketAddressResolver.create(dnsResolver)).build();
    }

    /** 使用共享受控解析资源创建短生命周期 Redis 客户端。 */
    public RedisClient create(RedisURI uri) { return RedisClient.create(resources, uri); }

    /** 在测试和实际建连路径中执行相同的受控解析。 */
    InetAddress[] resolve(String host) throws UnknownHostException { return dnsResolver.resolve(host); }

    /** 应用停止时关闭 Lettuce 线程和定时器。 */
    @PreDestroy
    public void close() { resources.shutdown(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(); }
}
