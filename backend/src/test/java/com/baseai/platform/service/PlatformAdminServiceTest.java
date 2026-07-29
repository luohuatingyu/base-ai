package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.MenuRepository;
import com.baseai.platform.repository.PositionRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

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
    private PlatformAdminService service;
    private Menu catalog;
    private Menu page;
    private Menu button;

    /** 为角色权限校验准备标准目录、页面和按钮层级。 */
    @BeforeEach
    void setUp() {
        UserRepository userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        menuRepository = mock(MenuRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        PositionRepository positionRepository = mock(PositionRepository.class);
        BCryptPasswordEncoder passwordEncoder = mock(BCryptPasswordEncoder.class);
        service = new PlatformAdminService(userRepository, roleRepository, menuRepository,
            departmentRepository, positionRepository, passwordEncoder);

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

    /** 创建角色命令，集中维护测试必需字段。 */
    private static PlatformAdminService.RoleCommand command(List<Long> menuIds) {
        return new PlatformAdminService.RoleCommand("EDITOR", "编辑人员", null, "ALL", true, menuIds, List.of());
    }

    /** 创建最小菜单节点。 */
    private static Menu menu(Long id, Long parentId, String type, String permission) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setName(permission);
        menu.setType(type);
        menu.setPermission(permission);
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
