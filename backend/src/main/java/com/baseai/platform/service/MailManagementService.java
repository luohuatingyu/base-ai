package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.MailAccount;
import com.baseai.platform.domain.MailRoute;
import com.baseai.platform.repository.MailAccountRepository;
import com.baseai.platform.repository.MailRouteRepository;
import com.baseai.platform.security.AuthContext;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 管理 SMTP 邮箱账户、业务邮件路由及具体业务到邮箱的解析。 */
@Service
public class MailManagementService {
    public static final String DEFAULT_ROUTE = "DEFAULT";
    public static final String DEFAULT_ROUTE_NAME = "默认邮件路由";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{1,64}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");
    private static final List<String> TLS_MODES = List.of("NONE", "STARTTLS", "SSL");
    private final MailAccountRepository accountRepository;
    private final MailRouteRepository routeRepository;
    private final ConfigCryptoService cryptoService;

    /** 注入邮箱账户、邮件路由仓储和配置加密服务。 */
    public MailManagementService(MailAccountRepository accountRepository, MailRouteRepository routeRepository,
                                 ConfigCryptoService cryptoService) {
        this.accountRepository = accountRepository;
        this.routeRepository = routeRepository;
        this.cryptoService = cryptoService;
    }

    /** 查询全部邮箱账户，响应不包含密码密文或明文。 */
    public List<AccountView> accounts() {
        return accountRepository.findAll().stream().sorted(Comparator.comparing(MailAccount::getId))
            .map(this::accountView).toList();
    }

    /** 仅向系统管理员返回指定邮箱账户的解密密码。 */
    public AccountPasswordView accountPassword(Long id) {
        AuthContext.requireAdmin();
        MailAccount account = accountRepository.findById(id)
            .orElseThrow(() -> BusinessException.notFound("mail.account.notFound"));
        return new AccountPasswordView(account.getId(), cryptoService.decrypt(account.getPasswordEncrypted()));
    }

    /** 返回邮件路由编辑使用的启用邮箱简要选项。 */
    public List<AccountOption> accountOptions() {
        return accountRepository.findAll().stream().filter(item -> Boolean.TRUE.equals(item.getEnabled()))
            .sorted(Comparator.comparing(MailAccount::getId))
            .map(item -> new AccountOption(item.getId(), item.getCode(), item.getName())).toList();
    }

    /** 创建新的 SMTP 邮箱账户并加密保存密码。 */
    @Transactional
    public AccountView createAccount(AccountCommand command) {
        String code = code(command == null ? null : command.code(), "mail.account.codeRequired");
        if (accountRepository.findByCode(code).isPresent()) throw new BusinessException("mail.account.codeExists");
        return accountView(saveAccount(new MailAccount(), command, true));
    }

    /** 更新 SMTP 邮箱账户，密码留空时保留已有密文。 */
    @Transactional
    public AccountView updateAccount(Long id, AccountCommand command) {
        MailAccount account = accountRepository.findById(id)
            .orElseThrow(() -> BusinessException.notFound("mail.account.notFound"));
        String normalizedCode = code(command == null ? null : command.code(), "mail.account.codeRequired");
        accountRepository.findByCode(normalizedCode)
            .filter(existing -> !Objects.equals(existing.getId(), id))
            .ifPresent(existing -> { throw new BusinessException("mail.account.codeExists"); });
        return accountView(saveAccount(account, command, false));
    }

    /** 删除未被任何路由引用的邮箱账户。 */
    @Transactional
    public void deleteAccount(Long id) {
        if (!accountRepository.existsById(id)) throw BusinessException.notFound("mail.account.notFound");
        if (!routeRepository.findByAccountId(id).isEmpty()) throw new BusinessException("mail.account.inUse");
        accountRepository.deleteById(id);
    }

    /** 查询全部邮件业务路由，并附带邮箱账户展示名称。 */
    public List<RouteView> routes() {
        return routeRepository.findAll().stream().sorted(Comparator.comparing(MailRoute::getBusinessCode))
            .map(this::routeView).toList();
    }

    /** 幂等创建始终启用的 DEFAULT 路由，未配置邮箱时保留待配置状态。 */
    @Transactional
    public synchronized RouteView ensureDefaultRoute() {
        MailRoute route = routeRepository.findByBusinessCode(DEFAULT_ROUTE).orElseGet(() -> {
            MailRoute item = new MailRoute();
            item.setBusinessCode(DEFAULT_ROUTE);
            item.setToAddresses("");
            item.setCcAddresses("");
            return item;
        });
        route.setBusinessCode(DEFAULT_ROUTE);
        route.setName(DEFAULT_ROUTE_NAME);
        route.setEnabled(true);
        return routeView(routeRepository.save(route));
    }

    /** 创建业务邮件路由。 */
    @Transactional
    public RouteView createRoute(RouteCommand command) {
        String businessCode = code(command == null ? null : command.businessCode(), "mail.route.businessCodeRequired");
        if (routeRepository.findByBusinessCode(businessCode).isPresent()) {
            throw new BusinessException("mail.route.businessCodeExists");
        }
        return routeView(saveRoute(new MailRoute(), command, businessCode));
    }

    /** 更新业务邮件路由。 */
    @Transactional
    public RouteView updateRoute(Long id, RouteCommand command) {
        MailRoute route = routeRepository.findById(id)
            .orElseThrow(() -> BusinessException.notFound("mail.route.notFound"));
        String businessCode = DEFAULT_ROUTE.equals(route.getBusinessCode()) ? DEFAULT_ROUTE
            : code(command == null ? null : command.businessCode(), "mail.route.businessCodeRequired");
        routeRepository.findByBusinessCode(businessCode)
            .filter(existing -> !Objects.equals(existing.getId(), id))
            .ifPresent(existing -> { throw new BusinessException("mail.route.businessCodeExists"); });
        return routeView(saveRoute(route, command, businessCode));
    }

    /** 删除邮件业务路由，DEFAULT 路由必须永久保留。 */
    @Transactional
    public void deleteRoute(Long id) {
        MailRoute route = routeRepository.findById(id)
            .orElseThrow(() -> BusinessException.notFound("mail.route.notFound"));
        if (DEFAULT_ROUTE.equals(route.getBusinessCode())) {
            throw new BusinessException("mail.route.defaultDeleteForbidden");
        }
        routeRepository.delete(route);
    }

    /** 先解析具体业务路由，缺失或停用时回退到 DEFAULT 通用路由。 */
    public ResolvedRoute resolve(String businessCode) {
        String normalized = code(businessCode, "mail.route.businessCodeRequired");
        List<MailRoute> candidates = new java.util.ArrayList<>();
        routeRepository.findByBusinessCode(normalized)
            .filter(item -> Boolean.TRUE.equals(item.getEnabled())).ifPresent(candidates::add);
        if (!DEFAULT_ROUTE.equals(normalized)) {
            routeRepository.findByBusinessCode(DEFAULT_ROUTE)
                .filter(item -> Boolean.TRUE.equals(item.getEnabled())).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) throw new BusinessException("mail.route.unavailable");
        boolean configuredCandidate = false;
        for (MailRoute route : candidates) {
            List<String> toAddresses = splitAddresses(route.getToAddresses());
            if (route.getAccountId() == null || toAddresses.isEmpty()) continue;
            configuredCandidate = true;
            MailAccount account = accountRepository.findById(route.getAccountId())
                .filter(item -> Boolean.TRUE.equals(item.getEnabled())).orElse(null);
            if (account != null) {
                return new ResolvedRoute(route.getBusinessCode(), account.getHost(), account.getPort(),
                    account.getUsername(), account.getFromAddress(), account.getTlsMode(),
                    cryptoService.decrypt(account.getPasswordEncrypted()), toAddresses,
                    splitAddresses(route.getCcAddresses()));
            }
        }
        throw new BusinessException(configuredCandidate ? "mail.account.unavailable" : "mail.route.unavailable");
    }

    /** 精确解析人工测试所选路由，不受启停状态影响且不回退 DEFAULT。 */
    public ResolvedRoute resolveRoute(Long routeId) {
        MailRoute route = routeRepository.findById(routeId)
            .orElseThrow(() -> BusinessException.notFound("mail.route.notFound"));
        List<String> toAddresses = splitAddresses(route.getToAddresses());
        if (route.getAccountId() == null || toAddresses.isEmpty()) {
            throw new BusinessException("mail.route.unavailable");
        }
        MailAccount account = accountRepository.findById(route.getAccountId())
            .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
            .orElseThrow(() -> new BusinessException("mail.account.unavailable"));
        return new ResolvedRoute(route.getBusinessCode(), account.getHost(), account.getPort(),
            account.getUsername(), account.getFromAddress(), account.getTlsMode(),
            cryptoService.decrypt(account.getPasswordEncrypted()), toAddresses,
            splitAddresses(route.getCcAddresses()));
    }

    /** 校验并保存邮箱账户字段。 */
    private MailAccount saveAccount(MailAccount account, AccountCommand command, boolean creating) {
        if (command == null) throw new BusinessException("mail.account.requestRequired");
        account.setCode(code(command.code(), "mail.account.codeRequired"));
        account.setName(required(command.name(), "mail.account.nameRequired"));
        account.setHost(safeField(command.host(), "mail.account.hostRequired"));
        if (command.port() == null || command.port() < 1 || command.port() > 65535) {
            throw new BusinessException("mail.account.portInvalid");
        }
        account.setPort(command.port());
        account.setUsername(safeField(command.username(), "mail.account.usernameRequired"));
        account.setFromAddress(singleAddress(command.fromAddress(), "mail.account.fromInvalid"));
        String tlsMode = required(command.tlsMode(), "mail.account.tlsRequired").toUpperCase(Locale.ROOT);
        if (!TLS_MODES.contains(tlsMode)) throw new BusinessException("mail.account.tlsInvalid");
        account.setTlsMode(tlsMode);
        if (creating || !blank(command.password())) {
            account.setPasswordEncrypted(cryptoService.encrypt(required(command.password(), "mail.account.passwordRequired")));
        }
        account.setEnabled(command.enabled() == null || command.enabled());
        try {
            return accountRepository.save(account);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("mail.account.codeExists");
        }
    }

    /** 校验并保存业务邮件路由字段。 */
    private MailRoute saveRoute(MailRoute route, RouteCommand command, String businessCode) {
        if (command == null) throw new BusinessException("mail.route.requestRequired");
        route.setBusinessCode(businessCode);
        route.setName(DEFAULT_ROUTE.equals(businessCode) ? DEFAULT_ROUTE_NAME
            : required(command.name(), "mail.route.nameRequired"));
        if (command.accountId() == null || !accountRepository.existsById(command.accountId())) {
            throw new BusinessException("mail.account.notFound");
        }
        route.setAccountId(command.accountId());
        route.setToAddresses(joinAddresses(command.toAddresses(), true));
        route.setCcAddresses(joinAddresses(command.ccAddresses(), false));
        route.setEnabled(DEFAULT_ROUTE.equals(businessCode) || command.enabled() == null || command.enabled());
        try {
            return routeRepository.save(route);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("mail.route.businessCodeExists");
        }
    }

    /** 构造不暴露密码内容的邮箱账户页面视图。 */
    private AccountView accountView(MailAccount account) {
        return new AccountView(account.getId(), account.getCode(), account.getName(), account.getHost(),
            account.getPort(), account.getUsername(), account.getFromAddress(), account.getTlsMode(),
            !blank(account.getPasswordEncrypted()), account.getEnabled());
    }

    /** 构造包含邮箱展示名称但不包含密码的路由页面视图。 */
    private RouteView routeView(MailRoute route) {
        String accountName = route.getAccountId() == null ? "" : accountRepository.findById(route.getAccountId())
            .map(MailAccount::getName).orElse("");
        List<String> toAddresses = splitAddresses(route.getToAddresses());
        boolean configured = route.getAccountId() != null && !accountName.isBlank() && !toAddresses.isEmpty();
        return new RouteView(route.getId(), route.getBusinessCode(), route.getName(), route.getAccountId(),
            accountName, toAddresses, splitAddresses(route.getCcAddresses()), route.getEnabled(), configured);
    }

    /** 规范化业务或账户编码。 */
    private String code(String value, String messageKey) {
        String normalized = required(value, messageKey).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw new BusinessException("mail.codeInvalid");
        return normalized;
    }

    /** 将邮箱数组校验并规范为换行分隔文本。 */
    private String joinAddresses(List<String> addresses, boolean required) {
        List<String> normalized = addresses == null ? List.of() : addresses.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        if (required && normalized.isEmpty()) throw new BusinessException("mail.route.recipientRequired");
        normalized.forEach(value -> singleAddress(value, "mail.route.recipientInvalid"));
        return String.join("\n", normalized);
    }

    /** 将持久化的邮箱文本恢复为不可变列表。 */
    private List<String> splitAddresses(String value) {
        if (blank(value)) return List.of();
        return Arrays.stream(value.split("[\\r\\n,;]+"))
            .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    /** 校验单个邮箱地址并拒绝邮件头换行注入。 */
    private String singleAddress(String value, String messageKey) {
        String normalized = required(value, messageKey);
        if (normalized.contains("\r") || normalized.contains("\n") || !EMAIL.matcher(normalized).matches()) {
            throw new BusinessException(messageKey);
        }
        return normalized;
    }

    /** 校验参与 SMTP 连接或认证的单行文本。 */
    private String safeField(String value, String messageKey) {
        String normalized = required(value, messageKey);
        if (normalized.contains("\r") || normalized.contains("\n")) throw new BusinessException(messageKey);
        return normalized;
    }

    /** 返回去除空白后的必填文本。 */
    private String required(String value, String messageKey) {
        if (blank(value)) throw new BusinessException(messageKey);
        return value.trim();
    }

    /** 判断文本是否为空。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record AccountCommand(String code, String name, String host, Integer port, String username,
                                 String fromAddress, String tlsMode, String password, Boolean enabled) { }
    public record AccountView(Long id, String code, String name, String host, Integer port, String username,
                              String fromAddress, String tlsMode, boolean passwordConfigured, Boolean enabled) { }
    public record AccountPasswordView(Long id, String password) { }
    public record AccountOption(Long id, String code, String name) { }
    public record RouteCommand(String businessCode, String name, Long accountId, List<String> toAddresses,
                               List<String> ccAddresses, Boolean enabled) { }
    public record RouteView(Long id, String businessCode, String name, Long accountId, String accountName,
                            List<String> toAddresses, List<String> ccAddresses, Boolean enabled, boolean configured) { }
    public record ResolvedRoute(String businessCode, String host, Integer port, String username,
                                String fromAddress, String tlsMode, @JsonIgnore String password, List<String> toAddresses,
                                List<String> ccAddresses) {
        /** 调试输出始终掩码 SMTP 密码。 */
        @Override
        public String toString() {
            return "ResolvedRoute[businessCode=" + businessCode + ", host=" + host + ", port=" + port
                + ", username=" + username + ", fromAddress=" + fromAddress + ", tlsMode=" + tlsMode
                + ", password=***, toAddresses=" + toAddresses + ", ccAddresses=" + ccAddresses + "]";
        }
    }
}
