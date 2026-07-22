package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.PlatformAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组织架构管理控制器
 * <p>
 * 提供部门和岗位的增删改查接口，用于管理企业的组织架构信息。
 * 所有接口均需要相应的权限才能访问。
 * </p>
 *
 * @author BaseAI Platform
 * @since 1.0
 */
@RestController
@RequestMapping("/api/system")
public class OrganizationController {
    /**
     * 平台管理服务，提供组织架构相关的业务逻辑处理
     */
    private final PlatformAdminService service;

    /**
     * 构造函数，通过依赖注入获取服务实例
     *
     * @param service 平台管理服务实例
     */
    public OrganizationController(PlatformAdminService service) {
        this.service = service;
    }

    /**
     * 获取部门列表
     * <p>
     * 查询并返回系统中所有部门的信息列表
     * </p>
     *
     * @return 部门视图对象列表，包含部门的基本信息
     */
    @GetMapping("/departments")
    @RequiredPermission("system:department:list")
    public List<PlatformAdminService.DepartmentView> departments() {
        return service.departments();
    }

    /**
     * 创建新部门
     * <p>
     * 根据传入的部门信息创建新的部门记录
     * </p>
     *
     * @param command 部门命令对象，包含要创建的部门信息（如部门名称、描述等）
     * @return 创建成功后的部门视图对象，包含新部门的完整信息
     */
    @PostMapping("/departments")
    @RequiredPermission("system:department:create")
    public PlatformAdminService.DepartmentView createDepartment(@RequestBody PlatformAdminService.DepartmentCommand command) {
        return service.createDepartment(command);
    }

    /**
     * 更新部门信息
     * <p>
     * 根据部门ID更新指定部门的信息
     * </p>
     *
     * @param id 部门ID，用于定位要更新的部门
     * @param command 部门命令对象，包含要更新的部门信息
     * @return 更新成功后的部门视图对象，包含最新的部门信息
     */
    @PutMapping("/departments/{id}")
    @RequiredPermission("system:department:update")
    public PlatformAdminService.DepartmentView updateDepartment(@PathVariable Long id, @RequestBody PlatformAdminService.DepartmentCommand command) {
        return service.updateDepartment(id, command);
    }

    /**
     * 删除部门
     * <p>
     * 根据部门ID删除指定的部门记录
     * </p>
     *
     * @param id 部门ID，用于定位要删除的部门
     */
    @DeleteMapping("/departments/{id}")
    @RequiredPermission("system:department:delete")
    public void deleteDepartment(@PathVariable Long id) {
        service.deleteDepartment(id);
    }

    /**
     * 获取岗位列表
     * <p>
     * 查询并返回系统中所有岗位的信息列表
     * </p>
     *
     * @return 岗位视图对象列表，包含岗位的基本信息
     */
    @GetMapping("/positions")
    @RequiredPermission("system:position:list")
    public List<PlatformAdminService.PositionView> positions() {
        return service.positions();
    }

    /**
     * 创建新岗位
     * <p>
     * 根据传入的岗位信息创建新的岗位记录
     * </p>
     *
     * @param command 岗位命令对象，包含要创建的岗位信息（如岗位名称、职责描述等）
     * @return 创建成功后的岗位视图对象，包含新岗位的完整信息
     */
    @PostMapping("/positions")
    @RequiredPermission("system:position:create")
    public PlatformAdminService.PositionView createPosition(@RequestBody PlatformAdminService.PositionCommand command) {
        return service.createPosition(command);
    }

    /**
     * 更新岗位信息
     * <p>
     * 根据岗位ID更新指定岗位的信息
     * </p>
     *
     * @param id 岗位ID，用于定位要更新的岗位
     * @param command 岗位命令对象，包含要更新的岗位信息
     * @return 更新成功后的岗位视图对象，包含最新的岗位信息
     */
    @PutMapping("/positions/{id}")
    @RequiredPermission("system:position:update")
    public PlatformAdminService.PositionView updatePosition(@PathVariable Long id, @RequestBody PlatformAdminService.PositionCommand command) {
        return service.updatePosition(id, command);
    }

    /**
     * 删除岗位
     * <p>
     * 根据岗位ID删除指定的岗位记录
     * </p>
     *
     * @param id 岗位ID，用于定位要删除的岗位
     */
    @DeleteMapping("/positions/{id}")
    @RequiredPermission("system:position:delete")
    public void deletePosition(@PathVariable Long id) {
        service.deletePosition(id);
    }
}
