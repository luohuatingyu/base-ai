package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.MailInboxService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 只读收件接口，所有响应禁止浏览器及代理缓存。 */
@RestController
@RequestMapping("/api/mail")
public class MailInboxController {
    private final MailInboxService service;

    /** 注入按邮箱角色过滤的收件服务。 */
    public MailInboxController(MailInboxService service) { this.service = service; }

    /** 查询收件页可用邮箱。 */
    @GetMapping("/inbox/accounts")
    @RequiredPermission("system:mail:inbox:list")
    public ResponseEntity<List<MailInboxService.InboxAccount>> accounts() { return noStore(service.accounts()); }

    /** 管理员获取角色绑定候选项。 */
    @GetMapping("/accounts/role-options")
    public ResponseEntity<List<MailInboxService.RoleOption>> roles() { return noStore(service.roleOptions()); }

    /** 分页读取 INBOX 邮件摘要。 */
    @GetMapping("/inbox/{accountId}/messages")
    @RequiredPermission("system:mail:inbox:list")
    public ResponseEntity<Map<?, ?>> messages(@PathVariable Long accountId,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return noStore(service.read(accountId, page, size, null, null));
    }

    /** 通过 UID 与邮箱版本读取单封邮件，不设置已读标志。 */
    @GetMapping("/inbox/{accountId}/messages/{uid}")
    @RequiredPermission("system:mail:inbox:list")
    public ResponseEntity<Map<?, ?>> message(@PathVariable Long accountId, @PathVariable String uid,
        @RequestParam String uidValidity) {
        return noStore(service.read(accountId, 1, 20, uid, uidValidity));
    }

    /** 禁止缓存包含邮箱及邮件数据的响应。 */
    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }
}
