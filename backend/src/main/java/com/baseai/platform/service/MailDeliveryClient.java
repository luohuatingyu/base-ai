package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/** 通过内部认证的 Python Worker 执行 SMTP 邮件发送。 */
@Service
public class MailDeliveryClient {
    private final RestClient restClient;

    /** 注入统一 Python Worker 客户端。 */
    public MailDeliveryClient(@Qualifier("pythonWorkerRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** 将已解析的 SMTP 配置和邮件内容发送给 Worker，错误不透传敏感细节。 */
    public Map<String, Object> send(MailManagementService.ResolvedRoute route, String subject, String body) {
        try {
            Map<?, ?> response = restClient.post().uri("/email/send")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SendRequest(new SmtpConfig(route.host(), route.port(), route.username(),
                        route.fromAddress(), route.tlsMode(), route.password()),
                    route.toAddresses(), route.ccAddresses(), subject, body))
                .retrieve().body(Map.class);
            if (response == null) throw new BusinessException(502, "mail.workerEmptyResponse");
            return response.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        } catch (RestClientResponseException exception) {
            throw new BusinessException(502, "mail.sendFailed");
        } catch (RestClientException exception) {
            throw new BusinessException(502, "mail.workerUnavailable");
        }
    }

    private record SmtpConfig(String host, Integer port, String username, String fromAddress,
                              String tlsMode, String password) { }
    private record SendRequest(SmtpConfig smtp, java.util.List<String> toAddresses,
                               java.util.List<String> ccAddresses, String subject, String body) { }
}
