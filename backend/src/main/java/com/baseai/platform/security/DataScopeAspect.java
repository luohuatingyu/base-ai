package com.baseai.platform.security;

import com.baseai.platform.domain.Department;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.UserRepository;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Aspect
@Component
public class DataScopeAspect {
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    public DataScopeAspect(UserRepository userRepository, DepartmentRepository departmentRepository) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
    }

    /** 在声明数据权限的方法执行期间提供统一过滤范围。 */
    @Around("@annotation(com.baseai.platform.security.DataScope)")
    public Object apply(ProceedingJoinPoint point) throws Throwable {
        AuthUser authUser = AuthContext.require();
        UserAccount user = userRepository.findById(authUser.id()).orElseThrow();
        DataScopeContext.set(resolve(user));
        try {
            return point.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }

    /** 合并用户全部角色的数据范围，取可见范围并集。 */
    private DataScopeContext.Scope resolve(UserAccount user) {
        if (user.getRoles().stream().anyMatch(role -> "ADMIN".equals(role.getCode()) || "ALL".equals(role.getDataScope()))) {
            return new DataScopeContext.Scope(true, false, user.getId(), Set.of());
        }
        Set<Long> departments = new HashSet<>();
        boolean selfOnly = true;
        for (Role role : user.getRoles()) {
            if (!Boolean.TRUE.equals(role.getEnabled())) continue;
            if ("SELF".equals(role.getDataScope())) continue;
            selfOnly = false;
            if ("DEPARTMENT".equals(role.getDataScope()) && user.getDepartment() != null) departments.add(user.getDepartment().getId());
            if ("DEPARTMENT_AND_CHILDREN".equals(role.getDataScope()) && user.getDepartment() != null) {
                collectChildren(user.getDepartment().getId(), departments);
            }
            if ("CUSTOM".equals(role.getDataScope())) role.getCustomDepartments().stream().map(Department::getId).forEach(departments::add);
        }
        return new DataScopeContext.Scope(false, selfOnly, user.getId(), Set.copyOf(departments));
    }

    /** 递归收集当前部门和全部下级部门。 */
    private void collectChildren(Long parentId, Set<Long> result) {
        if (!result.add(parentId)) return;
        departmentRepository.findAll().stream().filter(item -> parentId.equals(item.getParentId()))
            .forEach(item -> collectChildren(item.getId(), result));
    }
}
