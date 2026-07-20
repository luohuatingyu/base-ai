package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.MenuRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PlatformAdminService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public PlatformAdminService(UserRepository userRepository, RoleRepository roleRepository,
                                MenuRepository menuRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 查询全部平台用户。 */
    @Transactional(readOnly = true)
    public List<UserView> users() {
        return userRepository.findAll().stream().map(this::toUserView).toList();
    }

    /** 创建用户并绑定角色。 */
    @Transactional
    public UserView createUser(UserCommand command) {
        String username = require(command.username(), "账号不能为空");
        if (userRepository.existsByUsername(username)) throw new BusinessException("账号已存在");
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setDisplayName(require(command.displayName(), "显示名称不能为空"));
        user.setPasswordHash(passwordEncoder.encode(require(command.password(), "密码不能为空")));
        user.setEnabled(command.enabled() == null || command.enabled());
        user.setRoles(loadRoles(command.roleIds()));
        return toUserView(userRepository.save(user));
    }

    /** 更新用户资料、状态、角色及可选密码。 */
    @Transactional
    public UserView updateUser(Long id, UserCommand command) {
        UserAccount user = userRepository.findById(id).orElseThrow(() -> BusinessException.notFound("用户不存在"));
        user.setDisplayName(require(command.displayName(), "显示名称不能为空"));
        user.setEnabled(command.enabled() == null || command.enabled());
        user.setRoles(loadRoles(command.roleIds()));
        if (command.password() != null && !command.password().isBlank()) user.setPasswordHash(passwordEncoder.encode(command.password()));
        return toUserView(user);
    }

    /** 查询全部角色及其权限菜单。 */
    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        return roleRepository.findAll().stream().map(this::toRoleView).toList();
    }

    /** 创建平台角色并绑定菜单权限。 */
    @Transactional
    public RoleView createRole(RoleCommand command) {
        String code = require(command.code(), "角色编码不能为空").toUpperCase();
        if (roleRepository.findByCode(code).isPresent()) throw new BusinessException("角色编码已存在");
        Role role = new Role();
        role.setCode(code);
        role.setName(require(command.name(), "角色名称不能为空"));
        role.setEnabled(command.enabled() == null || command.enabled());
        role.setMenus(loadMenus(command.menuIds()));
        return toRoleView(roleRepository.save(role));
    }

    /** 更新角色名称、状态和权限菜单。 */
    @Transactional
    public RoleView updateRole(Long id, RoleCommand command) {
        Role role = roleRepository.findById(id).orElseThrow(() -> BusinessException.notFound("角色不存在"));
        role.setName(require(command.name(), "角色名称不能为空"));
        role.setEnabled(command.enabled() == null || command.enabled());
        role.setMenus(loadMenus(command.menuIds()));
        return toRoleView(role);
    }

    /** 查询全部菜单权限定义。 */
    @Transactional(readOnly = true)
    public List<MenuView> menus() {
        return menuRepository.findAll().stream().map(this::toMenuView).toList();
    }

    /** 创建新的权限菜单。 */
    @Transactional
    public MenuView createMenu(MenuCommand command) {
        String permission = require(command.permission(), "权限编码不能为空");
        if (menuRepository.findByPermission(permission).isPresent()) throw new BusinessException("权限编码已存在");
        Menu menu = new Menu();
        applyMenu(menu, command);
        return toMenuView(menuRepository.save(menu));
    }

    /** 更新权限菜单展示信息和状态。 */
    @Transactional
    public MenuView updateMenu(Long id, MenuCommand command) {
        Menu menu = menuRepository.findById(id).orElseThrow(() -> BusinessException.notFound("菜单不存在"));
        applyMenu(menu, command);
        return toMenuView(menu);
    }

    /** 应用菜单命令字段。 */
    private void applyMenu(Menu menu, MenuCommand command) {
        menu.setName(require(command.name(), "菜单名称不能为空"));
        menu.setPermission(require(command.permission(), "权限编码不能为空"));
        menu.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        menu.setEnabled(command.enabled() == null || command.enabled());
    }

    /** 按编号加载角色集合。 */
    private Set<Role> loadRoles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new LinkedHashSet<>();
        List<Role> roles = roleRepository.findAllById(ids);
        if (roles.size() != new LinkedHashSet<>(ids).size()) throw new BusinessException("包含不存在的角色");
        return new LinkedHashSet<>(roles);
    }

    /** 按编号加载菜单集合。 */
    private Set<Menu> loadMenus(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new LinkedHashSet<>();
        List<Menu> menus = menuRepository.findAllById(ids);
        if (menus.size() != new LinkedHashSet<>(ids).size()) throw new BusinessException("包含不存在的菜单");
        return new LinkedHashSet<>(menus);
    }

    /** 转换用户响应。 */
    private UserView toUserView(UserAccount user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getEnabled(),
            user.getRoles().stream().map(Role::getId).sorted().toList());
    }

    /** 转换角色响应。 */
    private RoleView toRoleView(Role role) {
        return new RoleView(role.getId(), role.getCode(), role.getName(), role.getEnabled(),
            role.getMenus().stream().map(Menu::getId).sorted().toList());
    }

    /** 转换菜单响应。 */
    private MenuView toMenuView(Menu menu) {
        return new MenuView(menu.getId(), menu.getName(), menu.getPermission(), menu.getSortOrder(), menu.getEnabled());
    }

    /** 校验必要文本字段。 */
    private String require(String value, String message) {
        if (value == null || value.isBlank()) throw new BusinessException(message);
        return value.trim();
    }

    public record UserCommand(String username, String displayName, String password, Boolean enabled, List<Long> roleIds) {}
    public record UserView(Long id, String username, String displayName, Boolean enabled, List<Long> roleIds) {}
    public record RoleCommand(String code, String name, Boolean enabled, List<Long> menuIds) {}
    public record RoleView(Long id, String code, String name, Boolean enabled, List<Long> menuIds) {}
    public record MenuCommand(String name, String permission, Integer sortOrder, Boolean enabled) {}
    public record MenuView(Long id, String name, String permission, Integer sortOrder, Boolean enabled) {}
}
