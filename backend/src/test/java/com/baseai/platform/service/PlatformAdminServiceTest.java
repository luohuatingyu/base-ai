package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.MenuRepository;
import com.baseai.platform.repository.PositionRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.security.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAdminServiceTest {
    private RoleRepository roleRepository;
    private MenuRepository menuRepository;
    private UserRepository userRepository;
    private BCryptPasswordEncoder passwordEncoder;
    private SessionService sessionService;
    private PlatformAdminService service;
    private Menu catalog;
    private Menu page;
    private Menu button;

    /** 为角色权限校验准备标准目录、页面和按钮层级。 */
    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        menuRepository = mock(MenuRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        PositionRepository positionRepository = mock(PositionRepository.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        sessionService = mock(SessionService.class);
        PlatformProperties properties = new PlatformProperties();
        service = new PlatformAdminService(userRepository, roleRepository, menuRepository,
            departmentRepository, positionRepository, passwordEncoder, sessionService, properties);
        AuthContext.set(actor(99L, Set.of("ADMIN"), Set.of()));

        catalog = menu(1L, null, "CATALOG", "system:catalog");
        page = menu(2L, 1L, "MENU", "system:user:list");
        button = menu(3L, 2L, "BUTTON", "system:user:create");
        when(menuRepository.findAll()).thenReturn(List.of(catalog, page, button));
        when(menuRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<Long> selected = new java.util.ArrayList<>();
            ids.forEach(selected::add);
            return List.of(catalog, page, button).stream().filter(item -> selected.contains(item.getId())).toList();
        });
        when(roleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 清理线程认证上下文，避免测试之间相互污染。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 仅提交按钮权限时，创建角色必须拒绝保存。 */
    @Test
    void rejectsCreatingRoleWithButtonButWithoutPage() {
        when(roleRepository.findByCode("EDITOR")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.createRole(command(List.of(button.getId()))));

        assertEquals("role.buttonPageRequired", exception.getMessageKey());
        verify(roleRepository, never()).save(any());
    }

    /** 仅提交按钮权限时，编辑角色也必须拒绝保存。 */
    @Test
    void rejectsUpdatingRoleWithButtonButWithoutPage() {
        Role existing = role(10L, "EDITOR");
        when(roleRepository.findById(10L)).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.updateRole(10L, command(List.of(button.getId()))));

        assertEquals("role.buttonPageRequired", exception.getMessageKey());
        verify(roleRepository, never()).save(any());
    }

    /** 页面与按钮同时提交时必须正常保存。 */
    @Test
    void createsRoleWithPageAndButtonPermissions() {
        when(roleRepository.findByCode("EDITOR")).thenReturn(Optional.empty());

        PlatformAdminService.RoleView created = service.createRole(command(List.of(page.getId(), button.getId())));

        assertEquals(List.of(2L, 3L), created.menuIds());
        verify(roleRepository).save(any());
    }

    /** 空权限角色保持兼容并允许保存。 */
    @ParameterizedTest
    @NullAndEmptySource
    void createsRoleWithoutPermissions(List<Long> menuIds) {
        when(roleRepository.findByCode("EDITOR")).thenReturn(Optional.empty());

        PlatformAdminService.RoleView created = service.createRole(command(menuIds));

        assertEquals(List.of(), created.menuIds());
    }

    /** 编辑角色同时提交页面与按钮时必须正常保存。 */
    @Test
    void updatesRoleWithPageAndButtonPermissions() {
        Role existing = role(10L, "EDITOR");
        when(roleRepository.findById(10L)).thenReturn(Optional.of(existing));

        PlatformAdminService.RoleView updated = service.updateRole(10L, command(List.of(page.getId(), button.getId())));

        assertEquals(List.of(2L, 3L), updated.menuIds());
        verify(roleRepository).save(existing);
    }

    /** 非管理员即使拥有用户管理权限，也不能授予系统管理员角色。 */
    @Test
    void nonAdminCannotDelegateAdminRole() {
        Role admin = role(20L, "ADMIN");
        when(roleRepository.findAllById(any())).thenReturn(List.of(admin));
        when(userRepository.existsByUsername("new-user")).thenReturn(false);
        AuthContext.set(actor(7L, Set.of("EDITOR"), Set.of("system:user:manage")));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.createUser(userCommand("new-user", "strong-password", true, List.of(20L))));

        assertEquals("user.adminDelegationForbidden", exception.getMessageKey());
        verify(userRepository, never()).save(any());
    }

    /** 非管理员不能把自己没有的菜单权限通过角色转授给其他用户。 */
    @Test
    void nonAdminCannotDelegateExcessPermission() {
        Role elevated = role(21L, "AUDITOR");
        elevated.getMenus().add(button);
        when(roleRepository.findAllById(any())).thenReturn(List.of(elevated));
        when(userRepository.existsByUsername("new-user")).thenReturn(false);
        AuthContext.set(actor(7L, Set.of("EDITOR"), Set.of("system:user:list")));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.createUser(userCommand("new-user", "strong-password", true, List.of(21L))));

        assertEquals("user.permissionDelegationForbidden", exception.getMessageKey());
    }

    /** 提交不存在的角色 ID 时必须整体失败，不能静默创建低权限账号。 */
    @Test
    void rejectsUnknownRoleId() {
        when(roleRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.existsByUsername("new-user")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.createUser(userCommand("new-user", "strong-password", true, List.of(999L))));

        assertEquals("role.notFound", exception.getMessageKey());
    }

    /** 管理员密码必须同时满足最小字符数和 BCrypt 72 字节上限。 */
    @Test
    void validatesPasswordBoundaries() {
        when(roleRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.existsByUsername(any())).thenReturn(false);

        BusinessException shortPassword = assertThrows(BusinessException.class,
            () -> service.createUser(userCommand("short-user", "12345678901", true, List.of())));
        BusinessException longPassword = assertThrows(BusinessException.class,
            () -> service.createUser(userCommand("long-user", "密".repeat(25), true, List.of())));

        assertEquals("auth.passwordTooShort", shortPassword.getMessageKey());
        assertEquals("auth.passwordTooLong", longPassword.getMessageKey());
    }

    /** 禁用最后一个可登录管理员时必须拒绝，避免平台失去管理入口。 */
    @Test
    void keepsLastEnabledAdmin() {
        Role adminRole = role(20L, "ADMIN");
        UserAccount admin = user(1L, "admin", true, adminRole);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllForAdminGuard()).thenReturn(List.of(admin));
        when(roleRepository.findAllById(any())).thenReturn(List.of(adminRole));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.updateUser(1L, userCommand("admin", null, false, List.of(20L))));

        assertEquals("user.lastAdminRequired", exception.getMessageKey());
        verify(userRepository, never()).save(any());
    }

    /** 修改密码后必须撤销目标用户所有既有会话。 */
    @Test
    void passwordChangeTerminatesExistingSessions() {
        Role editor = role(21L, "EDITOR");
        UserAccount target = user(2L, "editor", true, editor);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(roleRepository.findAllById(any())).thenReturn(List.of(editor));
        when(passwordEncoder.encode("new-strong-password")).thenReturn("new-hash");

        service.updateUser(2L, userCommand("editor", "new-strong-password", true, List.of(21L)));

        assertEquals("new-hash", target.getPasswordHash());
        verify(sessionService).terminateUser(2L);
    }

    /** 非管理员不能通过编辑角色为自己间接增加权限。 */
    @Test
    void nonAdminCannotElevateRolePermissions() {
        Role editor = role(21L, "EDITOR");
        editor.getMenus().add(page);
        when(roleRepository.findById(21L)).thenReturn(Optional.of(editor));
        AuthContext.set(actor(7L, Set.of("EDITOR"), Set.of("system:user:list")));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.updateRole(21L, command(List.of(page.getId(), button.getId()))));

        assertEquals("role.permissionDelegationForbidden", exception.getMessageKey());
        verify(roleRepository, never()).save(any());
    }

    /** 创建角色命令，集中维护测试必需字段。 */
    private static PlatformAdminService.RoleCommand command(List<Long> menuIds) {
        return new PlatformAdminService.RoleCommand("EDITOR", "编辑人员", null, "ALL", true, menuIds, List.of());
    }

    /** 创建用户管理命令，集中维护测试必要字段。 */
    private static PlatformAdminService.UserCommand userCommand(String username, String password, boolean enabled,
                                                                 List<Long> roleIds) {
        return new PlatformAdminService.UserCommand(username, username, password, enabled, null, roleIds, List.of());
    }

    /** 创建具备角色的最小用户实体。 */
    private static UserAccount user(Long id, String username, boolean enabled, Role... roles) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEnabled(enabled);
        user.setPasswordHash("hash");
        user.setRoles(new java.util.LinkedHashSet<>(List.of(roles)));
        return user;
    }

    /** 创建当前操作人权限快照。 */
    private static AuthUser actor(Long id, Set<String> roles, Set<String> permissions) {
        return new AuthUser(id, "actor", roles, permissions, AuthenticationType.TOKEN, null, null);
    }

    /** 创建最小菜单节点。 */
    private static Menu menu(Long id, Long parentId, String type, String permission) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setName(permission);
        menu.setType(type);
        menu.setPermission(permission);
        menu.setEnabled(true);
        return menu;
    }

    /** 创建最小角色实体。 */
    private static Role role(Long id, String code) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        role.setName("编辑人员");
        return role;
    }
}
