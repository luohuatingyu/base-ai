package com.baseai.platform.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PythonWorkerRestClientConfig {

    /** 创建统一 Worker 客户端并传播内部令牌和日志上下文。 */
    @Bean("pythonWorkerRestClient")
    public RestClient pythonWorkerRestClient(PlatformProperties properties) {
        return RestClient.builder().baseUrl(properties.getPythonWorker().getUrl())
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
