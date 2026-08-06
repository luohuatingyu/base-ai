package com.baseai.platform.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Python Worker REST 客户端配置，统一设置地址并传播追踪上下文。 */
@Configuration
public class PythonWorkerRestClientConfig {

    /** 创建统一 Worker 客户端，强制 HTTP/1.1 并传播内部令牌和日志上下文。 */
    @Bean("pythonWorkerRestClient")
    public RestClient pythonWorkerRestClient(PlatformProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return RestClient.builder().baseUrl(properties.getPythonWorker().getUrl())
            .requestFactory(new JdkClientHttpRequestFactory(httpClient))
            .defaultHeader("X-Internal-Token", properties.getPythonWorker().getInternalToken())
            .defaultRequest(request -> {
                putIfPresent(request, "X-Request-Id", MDC.get("requestId"));
                putIfPresent(request, "X-Parent-Trace-Id", MDC.get("traceId"));
            }).build();
    }

    /** 创建具有短超时的 Worker 就绪检查客户端，避免故障探测长期占用请求线程。 */
    @Bean("pythonWorkerHealthRestClient")
    public RestClient pythonWorkerHealthRestClient(PlatformProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(properties.getPythonWorker().getUrl()).requestFactory(factory)
            .defaultHeader("X-Internal-Token", properties.getPythonWorker().getInternalToken()).build();
    }

    /** 非空上下文才写入跨服务请求头。 */
    private void putIfPresent(RestClient.RequestHeadersSpec<?> request, String name, String value) {
        if (value != null && !value.isBlank()) request.header(name, value);
    }
}
