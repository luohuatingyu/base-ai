package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.Department;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 统一解析角色数据范围，并提供用户管理的对象级授权判断。 */
@Component
public class DataScopeResolver {
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    /** 注入当前用户和部门树查询依赖。 */
    public DataScopeResolver(UserRepository userRepository, DepartmentRepository departmentRepository) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
    }

    /** 解析当前认证用户的实时数据范围，管理员身份直接拥有全部范围。 */
    public DataScopeContext.Scope resolveCurrent() {
        AuthUser authenticated = AuthContext.require();
        if (authenticated.roles().contains("ADMIN")) {
            return new DataScopeContext.Scope(true, true, authenticated.id(), Set.of());
        }
        UserAccount user = userRepository.findById(authenticated.id())
            .orElseThrow(() -> BusinessException.unauthorized("auth.userNotFound"));
        return resolve(user);
    }

    /** 合并用户全部启用角色的可见范围；多个角色按并集授权。 */
    public DataScopeContext.Scope resolve(UserAccount user) {
        if (user.getRoles().stream().anyMatch(role -> Boolean.TRUE.equals(role.getEnabled())
            && ("ADMIN".equals(role.getCode()) || "ALL".equals(role.getDataScope())))) {
            return new DataScopeContext.Scope(true, true, user.getId(), Set.of());
        }
        Set<Long> departments = new HashSet<>();
        // 保留用户自身可见性，避免 CUSTOM 与 SELF 等组合角色互相抵消。
        boolean self = true;
        for (Role role : user.getRoles()) {
            if (!Boolean.TRUE.equals(role.getEnabled())) continue;
            String dataScope = role.getDataScope() == null ? "" : role.getDataScope();
            switch (dataScope) {
                case "SELF" -> self = true;
                case "DEPARTMENT" -> addCurrentDepartment(user, departments);
                case "DEPARTMENT_AND_CHILDREN" -> {
                    if (user.getDepartment() != null) collectChildren(user.getDepartment().getId(), departments);
                }
                case "CUSTOM" -> role.getCustomDepartments().stream().map(Department::getId).filter(Objects::nonNull)
                    .forEach(departments::add);
                default -> { /* 非法范围在角色配置写入时已拒绝，运行时按最小权限处理。 */ }
            }
        }
        return new DataScopeContext.Scope(false, self, user.getId(), Set.copyOf(departments));
    }

    /** 判断当前范围是否可读取或操作指定用户。 */
    public boolean canAccessUser(DataScopeContext.Scope scope, UserAccount target) {
        if (scope.all()) return true;
        if (scope.self() && Objects.equals(scope.userId(), target.getId())) return true;
        return target.getDepartment() != null && scope.departmentIds().contains(target.getDepartment().getId());
    }

    /** 判断当前范围是否可将一个用户归属到指定部门。 */
    public boolean canAssignDepartment(DataScopeContext.Scope scope, Long departmentId) {
        return scope.all() || departmentId != null && scope.departmentIds().contains(departmentId);
    }

    /** 判断待分配角色在目标用户部门上的数据范围是否为当前范围的子集。 */
    public boolean isRoleScopeWithin(DataScopeContext.Scope actorScope, Role role, Department targetDepartment) {
        if (actorScope.all()) return true;
        String dataScope = role.getDataScope() == null ? "" : role.getDataScope();
        return switch (dataScope) {
            case "SELF" -> true;
            case "CUSTOM" -> role.getCustomDepartments().stream().map(Department::getId).filter(Objects::nonNull)
                .allMatch(actorScope.departmentIds()::contains);
            case "DEPARTMENT" -> targetDepartment != null && actorScope.departmentIds().contains(targetDepartment.getId());
            case "DEPARTMENT_AND_CHILDREN" -> {
                if (targetDepartment == null) yield false;
                Set<Long> targetDepartments = new HashSet<>();
                collectChildren(targetDepartment.getId(), targetDepartments);
                yield actorScope.departmentIds().containsAll(targetDepartments);
            }
            default -> false;
        };
    }

    /** 对不可见用户返回统一的权限错误，阻止通过 ID 绕过列表过滤。 */
    public void requireUserAccess(DataScopeContext.Scope scope, UserAccount target) {
        if (!canAccessUser(scope, target)) throw BusinessException.forbidden("user.dataScopeForbidden");
    }

    /** 对超出当前范围的用户归属变更返回统一的权限错误。 */
    public void requireDepartmentAssignment(DataScopeContext.Scope scope, Long departmentId) {
        if (!canAssignDepartment(scope, departmentId)) throw BusinessException.forbidden("user.dataScopeForbidden");
    }

    /** 采集当前用户所属部门。 */
    private void addCurrentDepartment(UserAccount user, Set<Long> departments) {
        if (user.getDepartment() != null && user.getDepartment().getId() != null) {
            departments.add(user.getDepartment().getId());
        }
    }

    /** 递归收集当前部门及其全部下级部门。 */
    private void collectChildren(Long parentId, Set<Long> result) {
        if (!result.add(parentId)) return;
        departmentRepository.findAll().stream().filter(item -> parentId.equals(item.getParentId()))
            .forEach(item -> collectChildren(item.getId(), result));
    }
}
