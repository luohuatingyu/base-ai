package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.MailAccount;
import com.baseai.platform.repository.MailAccountRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.security.AuthContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 按角色授权读取远端邮箱，邮件正文不持久化。 */
@Service
public class MailInboxService {
    private final MailAccountRepository accounts;
    private final RoleRepository roles;
    private final ConfigCryptoService crypto;
    private final RestClient worker;

    /** 注入邮箱仓储、加密服务和受内部认证保护的 Worker 客户端。 */
    public MailInboxService(MailAccountRepository accounts, RoleRepository roles, ConfigCryptoService crypto,
                            @Qualifier("pythonWorkerRestClient") RestClient worker) {
        this.accounts = accounts;
        this.roles = roles;
        this.crypto = crypto;
        this.worker = worker;
    }

    /** 返回当前用户可见邮箱；不暴露服务器配置与密码。 */
    public List<InboxAccount> accounts() {
        requireRead();
        return accounts.findAll().stream().filter(this::visible).sorted(Comparator.comparing(MailAccount::getId))
            .map(account -> new InboxAccount(account.getId(), account.getName(), account.getFromAddress(),
                Boolean.TRUE.equals(account.getEnabled()) && Boolean.TRUE.equals(account.getImapEnabled()))).toList();
    }

    /** 仅管理员获取邮箱授权角色选项，避免额外开放角色管理权限。 */
    public List<RoleOption> roleOptions() {
        AuthContext.requireAdmin();
        return roles.findAll().stream().map(role -> new RoleOption(role.getId(), role.getName(), role.getEnabled())).toList();
    }

    /** 在每次远端请求前重新校验邮箱权限和参数。 */
    public Map<?, ?> read(Long accountId, int page, int size, String uid, String validity) {
        requireRead();
        MailAccount account = accounts.findById(accountId)
            .filter(this::visible).orElseThrow(() -> BusinessException.notFound("mail.account.notFound"));
        if (page < 1 || page > 1000000 || size < 1 || size > 50
            || (uid != null && (!validUid(uid) || !validUid(validity)))) {
            throw new BusinessException("mail.imap.invalidRequest");
        }
        if (!Boolean.TRUE.equals(account.getEnabled()) || !Boolean.TRUE.equals(account.getImapEnabled())) {
            throw new BusinessException("mail.imap.disabled");
        }
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("imap", Map.of("host", account.getImapHost(), "port", account.getImapPort(),
            "username", account.getImapUsername(), "tlsMode", account.getImapTlsMode(),
            "password", crypto.decrypt(account.getImapPasswordEncrypted())));
        request.put("page", page);
        request.put("size", size);
        request.put("uid", uid);
        request.put("uidValidity", validity);
        try {
            Map<?, ?> response = worker.post().uri("/email/inbox").body(request).retrieve().body(Map.class);
            if (response == null) throw new BusinessException(502, "mail.workerEmptyResponse");
            return response;
        } catch (RestClientResponseException exception) {
            String key = switch (exception.getStatusCode().value()) {
                case 404 -> "mail.imap.messageMissing";
                case 409 -> "mail.imap.mailboxChanged";
                case 413 -> "mail.imap.tooLarge";
                default -> "mail.imap.readFailed";
            };
            throw new BusinessException(exception.getStatusCode().value() >= 500 ? 502 : exception.getStatusCode().value(), key);
        } catch (RestClientException exception) {
            throw new BusinessException(502, "mail.workerUnavailable");
        }
    }

    /** 将管理员全量可见与启用角色交集统一应用于列表和详情。 */
    private boolean visible(MailAccount account) {
        var user = AuthContext.require();
        return user.roles().contains("ADMIN") || account.getRoles().stream()
            .anyMatch(role -> Boolean.TRUE.equals(role.getEnabled()) && user.roles().contains(role.getCode()));
    }

    /** 服务层也执行权限检查，防止绕过控制器调用。 */
    private void requireRead() {
        if (!AuthContext.require().hasPermission("system:mail:inbox:list")) {
            throw BusinessException.forbidden("auth.permissionDenied");
        }
    }

    /** UID 和 UIDVALIDITY 必须为 IMAP 无符号非零 32 位整数。 */
    private boolean validUid(String value) {
        return value != null && value.matches("[1-9][0-9]{0,9}") && Long.parseLong(value) <= 4294967295L;
    }

    public record InboxAccount(Long id, String name, String address, boolean available) { }
    public record RoleOption(Long id, String name, Boolean enabled) { }
}
