package com.baseai.platform.security;

import com.baseai.platform.domain.Department;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataScopeResolverTest {
    /** SELF 与 CUSTOM 角色应按并集保留本人和自定义部门可见性。 */
    @Test
    void combinesSelfAndCustomDepartmentScopes() {
        Department customDepartment = department(21L, null);
        Role self = role("SELF");
        Role custom = role("CUSTOM");
        custom.setCustomDepartments(new LinkedHashSet<>(Set.of(customDepartment)));
        UserAccount user = user(7L, department(11L, null), self, custom);
        DataScopeResolver resolver = new DataScopeResolver(mock(UserRepository.class), mock(DepartmentRepository.class));

        DataScopeContext.Scope scope = resolver.resolve(user);

        assertFalse(scope.all());
        assertTrue(scope.self());
        assertTrue(scope.departmentIds().contains(21L));
        assertTrue(resolver.canAccessUser(scope, user(7L, department(99L, null))));
        assertTrue(resolver.canAccessUser(scope, user(8L, customDepartment)));
        assertFalse(resolver.canAccessUser(scope, user(9L, department(22L, null))));
    }

    /** 本部门及下级范围必须递归覆盖所有子部门。 */
    @Test
    void includesDepartmentDescendants() {
        Department root = department(11L, null);
        Department child = department(12L, 11L);
        Department grandchild = department(13L, 12L);
        Role role = role("DEPARTMENT_AND_CHILDREN");
        DepartmentRepository departments = mock(DepartmentRepository.class);
        when(departments.findAll()).thenReturn(List.of(root, child, grandchild));
        DataScopeResolver resolver = new DataScopeResolver(mock(UserRepository.class), departments);

        DataScopeContext.Scope scope = resolver.resolve(user(7L, root, role));

        assertTrue(scope.departmentIds().containsAll(Set.of(11L, 12L, 13L)));
    }

    /** 创建最小数据范围角色。 */
    private static Role role(String scope) {
        Role role = new Role();
        role.setCode("ROLE_" + scope);
        role.setName(scope);
        role.setEnabled(true);
        role.setDataScope(scope);
        return role;
    }

    /** 创建最小部门实体。 */
    private static Department department(Long id, Long parentId) {
        Department department = new Department();
        department.setId(id);
        department.setParentId(parentId);
        department.setCode("DEPT_" + id);
        department.setName("Department " + id);
        return department;
    }

    /** 创建带部门和角色的最小用户实体。 */
    private static UserAccount user(Long id, Department department, Role... roles) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setDepartment(department);
        user.setRoles(new LinkedHashSet<>(List.of(roles)));
        return user;
    }
}
