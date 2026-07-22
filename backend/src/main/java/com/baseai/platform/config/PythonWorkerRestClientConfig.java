package com.baseai.platform.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

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
                putIfPresent(request, "X-Parent-Job-Id", MDC.get("jobId"));
            }).build();
    }

    /** 非空上下文才写入跨服务请求头。 */
    private void putIfPresent(RestClient.RequestHeadersSpec<?> request, String name, String value) {
        if (value != null && !value.isBlank()) request.header(name, value);
    }
}
