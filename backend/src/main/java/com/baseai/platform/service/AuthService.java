package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.TokenClaims;
import com.baseai.platform.security.TokenService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /** 校验账号密码并签发登录令牌。 */
    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        UserAccount user = userRepository.findByUsername(requireText(username, "请输入账号"))
            .orElseThrow(() -> BusinessException.unauthorized("账号或密码错误"));
        if (!passwordEncoder.matches(requireText(password, "请输入密码"), user.getPasswordHash())) {
            throw BusinessException.unauthorized("账号或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) throw BusinessException.forbidden("账号已停用");
        String token = tokenService.createToken(user.getId(), user.getUsername());
        TokenClaims claims = tokenService.parseToken(token);
        return new LoginResult(token, claims.expiresAt(), toCurrentUser(user));
    }

    /** 查询当前用户及其角色、权限和菜单。 */
    @Transactional(readOnly = true)
    public CurrentUser currentUser() {
        UserAccount user = userRepository.findById(AuthContext.require().id())
            .orElseThrow(() -> BusinessException.unauthorized("登录用户不存在"));
        return toCurrentUser(user);
    }

    /** 将当前令牌写入 Redis 撤销缓存。 */
    public void logout(String token) {
        tokenService.revoke(token);
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
    public record CurrentUser(Long id, String username, String displayName, Long departmentId, List<String> roles, List<String> permissions, List<MenuItem> menus) {}
    public record MenuItem(Long id, Long parentId, String name, String type, String path, String component, String icon,
                           String permission, Integer sortOrder, Boolean visible) {}
}
