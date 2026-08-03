package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.MailDeliveryService;
import com.baseai.platform.service.MailManagementService;
import com.baseai.platform.trace.TraceType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** SMTP 邮箱账户和业务邮件路由管理接口。 */
@RestController
@RequestMapping("/api/mail")
public class MailManagementController {
    private final MailManagementService service;
    private final MailDeliveryService deliveryService;

    /** 注入邮件配置管理和邮件发送服务。 */
    public MailManagementController(MailManagementService service, MailDeliveryService deliveryService) {
        this.service = service;
        this.deliveryService = deliveryService;
    }

    /** 查询邮箱账户。 */
    @GetMapping("/accounts")
    @RequiredPermission("mail:account:list")
    public List<MailManagementService.AccountView> accounts() { return service.accounts(); }

    /** 查询指定邮箱账户的明文密码，服务层额外限制为系统管理员。 */
    @GetMapping("/accounts/{id}/password")
    @RequiredPermission("mail:account:update")
    public MailManagementService.AccountPasswordView accountPassword(@PathVariable Long id) {
        return service.accountPassword(id);
    }

    /** 查询邮件路由编辑所需的启用邮箱选项。 */
    @GetMapping("/account-options")
    @RequiredPermission("mail:route:list")
    public List<MailManagementService.AccountOption> accountOptions() { return service.accountOptions(); }

    /** 创建邮箱账户，追踪记录不保存密码请求。 */
    @PostMapping("/accounts")
    @RequiredPermission("mail:account:create")
    @TraceType(value = "MAIL_ACCOUNT_CREATE", captureRequest = false)
    public MailManagementService.AccountView createAccount(@RequestBody MailManagementService.AccountCommand command) {
        return service.createAccount(command);
    }

    /** 更新邮箱账户，追踪记录不保存密码请求。 */
    @PutMapping("/accounts/{id}")
    @RequiredPermission("mail:account:update")
    @TraceType(value = "MAIL_ACCOUNT_UPDATE", captureRequest = false)
    public MailManagementService.AccountView updateAccount(@PathVariable Long id,
                                                            @RequestBody MailManagementService.AccountCommand command) {
        return service.updateAccount(id, command);
    }

    /** 删除未被路由引用的邮箱账户。 */
    @DeleteMapping("/accounts/{id}")
    @RequiredPermission("mail:account:delete")
    public void deleteAccount(@PathVariable Long id) { service.deleteAccount(id); }

    /** 查询邮件业务路由。 */
    @GetMapping("/routes")
    @RequiredPermission("mail:route:list")
    public List<MailManagementService.RouteView> routes() { return service.routes(); }

    /** 创建邮件业务路由。 */
    @PostMapping("/routes")
    @RequiredPermission("mail:route:create")
    @TraceType(value = "MAIL_ROUTE_CREATE", captureRequest = false)
    public MailManagementService.RouteView createRoute(@RequestBody MailManagementService.RouteCommand command) {
        return service.createRoute(command);
    }

    /** 更新邮件业务路由。 */
    @PutMapping("/routes/{id}")
    @RequiredPermission("mail:route:update")
    @TraceType(value = "MAIL_ROUTE_UPDATE", captureRequest = false)
    public MailManagementService.RouteView updateRoute(@PathVariable Long id,
                                                        @RequestBody MailManagementService.RouteCommand command) {
        return service.updateRoute(id, command);
    }

    /** 使用当前请求语言向所选路由发送固定测试邮件。 */
    @PostMapping("/routes/{id}/test")
    @RequiredPermission("mail:route:update")
    @TraceType(value = "MAIL_ROUTE_TEST", captureRequest = false)
    public MailDeliveryService.DeliveryResult testRoute(@PathVariable Long id, Locale locale) {
        return deliveryService.sendTest(id, locale);
    }

    /** 删除非默认邮件业务路由。 */
    @DeleteMapping("/routes/{id}")
    @RequiredPermission("mail:route:delete")
    public void deleteRoute(@PathVariable Long id) { service.deleteRoute(id); }
}
