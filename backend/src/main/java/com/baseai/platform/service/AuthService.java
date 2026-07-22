package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.TokenClaims;
import com.baseai.platform.security.TokenService;
import com.baseai.platform.security.SessionService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 认证服务类
 * <p>
 * 负责处理用户认证相关的核心业务逻辑，包括：
 * <ul>
 *   <li>用户登录验证和令牌签发</li>
 *   <li>当前用户信息查询（包含角色、权限、菜单）</li>
 *   <li>用户登出和令牌撤销</li>
 *   <li>登录审计记录</li>
 * </ul>
 *
 * @author BaseAI Platform
 * @since 1.0
 */
@Service
public class AuthService {
    /** 用户仓储，用于查询用户账号信息 */
    private final UserRepository userRepository;

    /** BCrypt密码编码器，用于验证密码 */
    private final BCryptPasswordEncoder passwordEncoder;

    /** 令牌服务，负责JWT令牌的创建、解析和撤销 */
    private final TokenService tokenService;

    /** 会话服务，负责管理用户会话信息 */
    private final SessionService sessionService;

    /** 登录审计服务，记录登录行为和结果 */
    private final LoginAuditService loginAuditService;

    /**
     * 构造函数，通过Spring依赖注入初始化所需的服务组件
     *
     * @param userRepository 用户仓储
     * @param passwordEncoder BCrypt密码编码器
     * @param tokenService 令牌服务
     * @param sessionService 会话服务
     * @param loginAuditService 登录审计服务
     */
    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder, TokenService tokenService,
                       SessionService sessionService, LoginAuditService loginAuditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.loginAuditService = loginAuditService;
    }

    /**
     * 用户登录认证
     * <p>
     * 校验用户账号密码的有效性，验证通过后签发JWT令牌并注册会话。
     * 该方法会执行以下步骤：
     * <ol>
     *   <li>标准化并验证用户名和密码的合法性</li>
     *   <li>从数据库查询用户信息</li>
     *   <li>使用BCrypt验证密码是否匹配</li>
     *   <li>检查用户账号是否已启用</li>
     *   <li>生成JWT令牌并注册会话</li>
     *   <li>记录登录审计日志</li>
     * </ol>
     * <p>
     * 无论登录成功或失败，都会记录审计日志。失败时会抛出对应的业务异常。
     *
     * @param username 用户名，不能为空或空白字符串
     * @param password 密码，不能为空或空白字符串
     * @param metadata 登录元数据，包含IP地址和User-Agent信息
     * @return LoginResult 登录结果，包含令牌、过期时间和当前用户信息
     * @throws BusinessException 当账号或密码错误、账号已停用或字段验证失败时抛出
     */
    @Transactional
    public LoginResult login(String username, String password, LoginMetadata metadata) {
        // 标准化用户名（去除前后空格），用于审计日志记录
        String normalized = username == null ? "" : username.trim();
        try {
            // 查询用户账号，验证用户名格式并检查用户是否存在
            UserAccount user = userRepository.findByUsername(requireText(username, "请输入账号"))
                .orElseThrow(() -> BusinessException.unauthorized("账号或密码错误"));

            // 使用BCrypt验证密码，密码错误时统一返回"账号或密码错误"避免信息泄露
            if (!passwordEncoder.matches(requireText(password, "请输入密码"), user.getPasswordHash())) {
                throw BusinessException.unauthorized("账号或密码错误");
            }

            // 检查账号是否已启用
            if (!Boolean.TRUE.equals(user.getEnabled())) throw BusinessException.forbidden("账号已停用");

            // 创建JWT令牌
            String token = tokenService.createToken(user.getId(), user.getUsername());

            // 解析令牌获取声明信息
            TokenClaims claims = tokenService.parseToken(token);

            // 注册会话，记录用户登录信息（IP地址、User-Agent等）
            sessionService.register(claims, metadata.ipAddress(), metadata.userAgent());

            // 记录登录成功的审计日志
            loginAuditService.save(user.getUsername(), metadata, true, "登录成功");

            // 返回登录结果，包含令牌、过期时间和用户信息
            return new LoginResult(token, claims.expiresAt(), toCurrentUser(user));
        } catch (RuntimeException exception) {
            // 捕获所有运行时异常，记录登录失败的审计日志
            loginAuditService.save(normalized, metadata, false, exception.getMessage());
            throw exception;
        }
    }

    /**
     * 获取当前登录用户的完整信息
     * <p>
     * 查询当前认证用户的详细信息，包括用户基本信息、所属角色、拥有的权限以及可访问的菜单列表。
     * 该方法会过滤掉已禁用的角色和菜单，只返回有效的权限数据。
     *
     * @return CurrentUser 当前用户信息，包含用户ID、用户名、显示名、部门ID、角色列表、权限列表和菜单列表
     * @throws BusinessException 当令牌无效或用户不存在时抛出未授权异常
     */
    @Transactional(readOnly = true)
    public CurrentUser currentUser() {
        // 从认证上下文获取当前用户ID，并查询用户完整信息
        UserAccount user = userRepository.findById(AuthContext.require().id())
            .orElseThrow(() -> BusinessException.unauthorized("登录用户不存在"));
        return toCurrentUser(user);
    }

    /**
     * 用户登出
     * <p>
     * 撤销当前令牌的有效性并清除会话信息。
     * 该方法会将令牌ID写入Redis撤销缓存，使该令牌在过期前无法通过验证。
     *
     * @param token JWT令牌字符串
     */
    public void logout(String token) {
        // 解析令牌获取声明信息
        TokenClaims claims = tokenService.parseToken(token);

        // 将令牌ID加入撤销列表，直到令牌过期时间
        tokenService.revokeTokenId(claims.tokenId(), claims.expiresAt());

        // 从会话管理中移除该会话
        sessionService.remove(claims);
    }

    /** 将用户实体转换为前端需要的权限快照。 */
    private CurrentUser toCurrentUser(UserAccount user) {
        List<String> roles = user.getRoles().stream().filter(role -> Boolean.TRUE.equals(role.getEnabled()))
            .map(Role::getCode).sorted().toList();
        List<MenuItem> menus = user.getRoles().stream().filter(role -> Boolean.TRUE.equals(role.getEnabled()))
            .flatMap(role -> role.getMenus().stream()).filter(menu -> Boolean.TRUE.equals(menu.getEnabled()))
            .distinct().sorted(Comparator.comparing(Menu::getSortOrder).thenComparing(Menu::getId))
            .map(menu -> new MenuItem(menu.getId(), menu.getParentId(), menu.getName(), menu.getType(), menu.getPath(), menu.getComponent(),
                menu.getIcon(), menu.getPermission(), menu.getSortOrder(), menu.getVisible())).toList();
        List<String> permissions = menus.stream().map(MenuItem::permission).filter(java.util.Objects::nonNull).distinct().toList();
        return new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
            user.getDepartment() == null ? null : user.getDepartment().getId(), roles, permissions, menus);
    }

    /** 校验必要文本字段。 */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new BusinessException(message);
        return value.trim();
    }

    public record LoginResult(String token, Instant expiresAt, CurrentUser user) {}
    public record LoginMetadata(String ipAddress, String userAgent) {}
    public record CurrentUser(Long id, String username, String displayName, Long departmentId, List<String> roles, List<String> permissions, List<MenuItem> menus) {}
    public record MenuItem(Long id, Long parentId, String name, String type, String path, String component, String icon,
                           String permission, Integer sortOrder, Boolean visible) {}
}
