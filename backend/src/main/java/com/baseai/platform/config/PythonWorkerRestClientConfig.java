package com.baseai.platform.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
public class PythonWorkerRestClientConfig {

    /** 创建统一 Worker 客户端并传播内部令牌和日志上下文。 */
    @Bean("pythonWorkerRestClient")
    public RestClient pythonWorkerRestClient(PlatformProperties properties) {
        return RestClient.builder().baseUrl(properties.getPythonWorker().getUrl())
            .requestInterceptor((request, body, execution) -> {
                HttpHeaders headers = request.getHeaders();
                headers.set("X-Internal-Token", properties.getPythonWorker().getInternalToken());
                putIfPresent(headers, "X-Request-Id", MDC.get("requestId"));
                putIfPresent(headers, "X-Parent-Job-Id", MDC.get("jobId"));
                return execution.execute(request, body);
            }).build();
    }

    /** 非空上下文才写入跨服务请求头。 */
    private void putIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) headers.set(name, value);
    }
}
