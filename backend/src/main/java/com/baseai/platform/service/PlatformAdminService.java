package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import com.baseai.platform.security.AuthContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

/**
 * 平台管理服务类
 *
 * <p>提供平台核心管理功能，包括：
 * <ul>
 *   <li>用户管理：创建、更新、删除用户，管理用户的部门、岗位和角色关系</li>
 *   <li>角色管理：创建、更新、删除角色，配置角色的菜单权限和数据权限范围</li>
 *   <li>菜单管理：维护菜单树结构，支持目录、菜单和按钮三种类型</li>
 *   <li>部门管理：维护部门树结构，支持多级部门层次</li>
 *   <li>岗位管理：维护岗位列表，支持用户多岗位配置</li>
 * </ul>
 *
 * <p>该服务通过事务保证数据一致性，并实施业务规则校验，防止数据冲突和循环引用。
 *
 * @author BaseAI Platform
 * @since 1.0
 */
@Service
public class PlatformAdminService {
    /** 菜单类型常量：目录、菜单、按钮 */
    private static final Set<String> MENU_TYPES = Set.of("CATALOG", "MENU", "BUTTON");

    /** 数据权限范围常量：全部、本部门、本部门及下级、仅本人、自定义部门 */
    private static final Set<String> DATA_SCOPES = Set.of("ALL", "DEPARTMENT", "DEPARTMENT_AND_CHILDREN", "SELF", "CUSTOM");

    /** 用户数据访问对象 */
    private final UserRepository userRepository;

    /** 角色数据访问对象 */
    private final RoleRepository roleRepository;

    /** 菜单数据访问对象 */
    private final MenuRepository menuRepository;

    /** 部门数据访问对象 */
    private final DepartmentRepository departmentRepository;

    /** 岗位数据访问对象 */
    private final PositionRepository positionRepository;

    /** 密码加密编码器 */
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 构造函数，注入所需的依赖
     *
     * @param userRepository 用户仓储
     * @param roleRepository 角色仓储
     * @param menuRepository 菜单仓储
     * @param departmentRepository 部门仓储
     * @param positionRepository 岗位仓储
     * @param passwordEncoder 密码编码器
     */
    public PlatformAdminService(UserRepository userRepository, RoleRepository roleRepository, MenuRepository menuRepository,
                                DepartmentRepository departmentRepository, PositionRepository positionRepository,
                                BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.departmentRepository = departmentRepository;
        this.positionRepository = positionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 分页查询用户列表
     *
     * <p>支持按关键字（用户名或显示名称）和启用状态进行筛选。
     * 查询结果按用户ID升序排列，并返回分页数据。
     *
     * @param keyword 关键字，用于模糊匹配用户名或显示名称，为空时不过滤
     * @param enabled 启用状态过滤条件，true-仅启用，false-仅禁用，null-全部
     * @param page 页码，从1开始
     * @param size 每页大小
     * @return 分页后的用户视图列表
     */
    @Transactional(readOnly = true)
    public PageResult<UserView> users(String keyword, Boolean enabled, int page, int size) {
        // 获取所有用户并应用过滤条件
        return page(userRepository.findAll().stream()
            // 关键字过滤：匹配用户名或显示名称
            .filter(user -> blank(keyword) || contains(user.getUsername(), keyword) || contains(user.getDisplayName(), keyword))
            // 状态过滤：根据启用状态筛选
            .filter(user -> enabled == null || enabled.equals(user.getEnabled()))
            // 按ID排序并转换为视图对象
            .sorted(Comparator.comparing(UserAccount::getId)).map(this::toUserView).toList(), page, size);
    }

    /**
     * 创建新用户
     *
     * <p>创建用户时会校验用户名唯一性，设置密码哈希，并绑定部门、岗位和角色关系。
     * 用户名重复时抛出业务异常。
     *
     * @param command 用户创建命令对象，包含用户名、显示名称、密码、启用状态等信息
     * @return 创建成功后的用户视图对象
     * @throws BusinessException 当用户名已存在时抛出
     */
    @Transactional
    public UserView createUser(UserCommand command) {
        String username = require(command.username(), "请输入用户名");
        // 校验用户名唯一性
        if (userRepository.existsByUsername(username)) throw new BusinessException("用户名已存在");
        UserAccount user = new UserAccount();
        user.setUsername(username);
        // 应用用户属性和关系（创建模式）
        applyUser(user, command, true);
        return toUserView(userRepository.save(user));
    }

    /**
     * 更新用户信息
     *
     * <p>更新用户的基本信息、部门、岗位和角色关系。
     * 如果修改用户名，会校验新用户名的唯一性。
     * 密码字段为空时保持原密码不变。
     *
     * @param id 用户ID
     * @param command 用户更新命令对象
     * @return 更新后的用户视图对象
     * @throws BusinessException 当用户不存在或新用户名已被使用时抛出
     */
    @Transactional
    public UserView updateUser(Long id, UserCommand command) {
        UserAccount user = userRepository.findById(id).orElseThrow(() -> BusinessException.notFound("用户不存在"));
        // 如果用户名发生变化，校验新用户名的唯一性
        if (!user.getUsername().equals(command.username()) && userRepository.existsByUsername(require(command.username(), "请输入用户名"))) {
            throw new BusinessException("用户名已存在");
        }
        user.setUsername(require(command.username(), "请输入用户名"));
        // 应用用户属性和关系（更新模式）
        applyUser(user, command, false);
        return toUserView(userRepository.save(user));
    }

    /**
     * 删除用户
     *
     * <p>删除指定用户及其关联的角色和岗位关系。
     * 为防止误操作，不允许删除当前登录的用户。
     *
     * @param id 用户ID
     * @throws BusinessException 当用户不存在或尝试删除当前登录用户时抛出
     */
    @Transactional
    public void deleteUser(Long id) {
        // 防止删除当前登录用户
        if (Objects.equals(AuthContext.require().id(), id)) throw new BusinessException("不能删除当前登录用户");
        UserAccount user = userRepository.findById(id).orElseThrow(() -> BusinessException.notFound("用户不存在"));
        // 清除用户的角色和岗位关联关系
        user.getRoles().clear();
        user.getPositions().clear();
        userRepository.delete(user);
    }

    /**
     * 查询所有角色列表
     *
     * <p>返回系统中所有角色的完整信息，包括角色的菜单权限和自定义部门数据范围。
     * 结果按角色ID升序排列。
     *
     * @return 角色视图对象列表
     */
    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        return roleRepository.findAll().stream().sorted(Comparator.comparing(Role::getId)).map(this::toRoleView).toList();
    }

    /**
     * 创建新角色
     *
     * <p>创建角色时会校验角色编码的唯一性，并配置角色的菜单权限和数据权限范围。
     * 角色编码会自动转换为大写。
     *
     * @param command 角色创建命令对象，包含编码、名称、描述、数据权限范围等信息
     * @return 创建成功后的角色视图对象
     * @throws BusinessException 当角色编码已存在时抛出
     */
    @Transactional
    public RoleView createRole(RoleCommand command) {
        String code = require(command.code(), "请输入角色编码").toUpperCase(Locale.ROOT);
        // 校验角色编码唯一性
        if (roleRepository.findByCode(code).isPresent()) throw new BusinessException("角色编码已存在");
        Role role = new Role();
        role.setCode(code);
        // 应用角色属性和权限配置
        applyRole(role, command);
        return toRoleView(roleRepository.save(role));
    }

    /** 更新角色菜单与数据权限。 */
    @Transactional
    public RoleView updateRole(Long id, RoleCommand command) {
        Role role = roleRepository.findById(id).orElseThrow(() -> BusinessException.notFound("角色不存在"));
        if (!role.getCode().equalsIgnoreCase(command.code()) && roleRepository.findByCode(require(command.code(), "请输入角色编码").toUpperCase(Locale.ROOT)).isPresent()) {
            throw new BusinessException("角色编码已存在");
        }
        role.setCode(require(command.code(), "请输入角色编码").toUpperCase(Locale.ROOT));
        applyRole(role, command);
        return toRoleView(roleRepository.save(role));
    }

    /** 删除未绑定用户的非内置角色。 */
    @Transactional
    public void deleteRole(Long id) {
        Role role = roleRepository.findById(id).orElseThrow(() -> BusinessException.notFound("角色不存在"));
        if ("ADMIN".equals(role.getCode())) throw new BusinessException("不能删除内置管理员角色");
        boolean used = userRepository.findAll().stream().anyMatch(user -> user.getRoles().stream().anyMatch(item -> item.getId().equals(id)));
        if (used) throw new BusinessException("角色已被用户使用，不能删除");
        role.getMenus().clear();
        role.getCustomDepartments().clear();
        roleRepository.delete(role);
    }

    /** 查询完整菜单树数据。 */
    @Transactional(readOnly = true)
    public List<MenuView> menus() {
        return menuRepository.findAll().stream().sorted(menuComparator()).map(this::toMenuView).toList();
    }

    /** 创建目录、菜单或按钮权限节点。 */
    @Transactional
    public MenuView createMenu(MenuCommand command) {
        Menu menu = new Menu();
        applyMenu(menu, command);
        return toMenuView(menuRepository.save(menu));
    }

    /** 更新菜单节点并阻止循环父子关系。 */
    @Transactional
    public MenuView updateMenu(Long id, MenuCommand command) {
        Menu menu = menuRepository.findById(id).orElseThrow(() -> BusinessException.notFound("菜单不存在"));
        if (Objects.equals(id, command.parentId())) throw new BusinessException("菜单不能选择自身作为父级");
        applyMenu(menu, command);
        return toMenuView(menuRepository.save(menu));
    }

    /** 删除未被角色使用且没有下级节点的菜单。 */
    @Transactional
    public void deleteMenu(Long id) {
        Menu menu = menuRepository.findById(id).orElseThrow(() -> BusinessException.notFound("菜单不存在"));
        if (menuRepository.findAll().stream().anyMatch(item -> id.equals(item.getParentId()))) throw new BusinessException("请先删除下级菜单");
        if (roleRepository.findAll().stream().anyMatch(role -> role.getMenus().stream().anyMatch(item -> item.getId().equals(id)))) {
            throw new BusinessException("菜单已被角色使用，不能删除");
        }
        menuRepository.delete(menu);
    }

    /** 查询部门树数据。 */
    public List<DepartmentView> departments() {
        return departmentRepository.findAll().stream().sorted(Comparator.comparing(Department::getSortOrder).thenComparing(Department::getId))
            .map(this::toDepartmentView).toList();
    }

    /** 创建部门节点。 */
    @Transactional
    public DepartmentView createDepartment(DepartmentCommand command) {
        if (departmentRepository.findByCode(require(command.code(), "请输入部门编码")).isPresent()) throw new BusinessException("部门编码已存在");
        Department department = new Department();
        applyDepartment(department, command);
        return toDepartmentView(departmentRepository.save(department));
    }

    /** 更新部门节点。 */
    @Transactional
    public DepartmentView updateDepartment(Long id, DepartmentCommand command) {
        Department department = departmentRepository.findById(id).orElseThrow(() -> BusinessException.notFound("部门不存在"));
        if (Objects.equals(id, command.parentId())) throw new BusinessException("部门不能选择自身作为上级");
        applyDepartment(department, command);
        return toDepartmentView(departmentRepository.save(department));
    }

    /** 删除无下级且未被用户引用的部门。 */
    @Transactional
    public void deleteDepartment(Long id) {
        if (departmentRepository.existsByParentId(id)) throw new BusinessException("请先删除下级部门");
        if (userRepository.findAll().stream().anyMatch(user -> user.getDepartment() != null && id.equals(user.getDepartment().getId()))) {
            throw new BusinessException("部门已被用户使用，不能删除");
        }
        departmentRepository.deleteById(id);
    }

    /** 查询岗位列表。 */
    public List<PositionView> positions() {
        return positionRepository.findAll().stream().sorted(Comparator.comparing(Position::getSortOrder).thenComparing(Position::getId))
            .map(this::toPositionView).toList();
    }

    /** 创建岗位。 */
    @Transactional
    public PositionView createPosition(PositionCommand command) {
        if (positionRepository.findByCode(require(command.code(), "请输入岗位编码")).isPresent()) throw new BusinessException("岗位编码已存在");
        Position position = new Position();
        applyPosition(position, command);
        return toPositionView(positionRepository.save(position));
    }

    /** 更新岗位。 */
    @Transactional
    public PositionView updatePosition(Long id, PositionCommand command) {
        Position position = positionRepository.findById(id).orElseThrow(() -> BusinessException.notFound("岗位不存在"));
        applyPosition(position, command);
        return toPositionView(positionRepository.save(position));
    }

    /** 删除未被用户引用的岗位。 */
    @Transactional
    public void deletePosition(Long id) {
        if (userRepository.findAll().stream().anyMatch(user -> user.getPositions().stream().anyMatch(item -> item.getId().equals(id)))) {
            throw new BusinessException("岗位已被用户使用，不能删除");
        }
        positionRepository.deleteById(id);
    }

    /** 应用用户表单字段和关系。 */
    private void applyUser(UserAccount user, UserCommand command, boolean creating) {
        user.setDisplayName(require(command.displayName(), "请输入显示名称"));
        user.setEnabled(command.enabled() == null || command.enabled());
        if (creating || !blank(command.password())) user.setPasswordHash(passwordEncoder.encode(require(command.password(), "请输入密码")));
        user.setRoles(load(command.roleIds(), roleRepository::findAllById));
        user.setPositions(load(command.positionIds(), positionRepository::findAllById));
        user.setDepartment(command.departmentId() == null ? null : departmentRepository.findById(command.departmentId())
            .orElseThrow(() -> BusinessException.notFound("部门不存在")));
    }

    /** 应用角色基础信息、菜单和数据范围。 */
    private void applyRole(Role role, RoleCommand command) {
        role.setName(require(command.name(), "请输入角色名称"));
        role.setDescription(trim(command.description()));
        role.setEnabled(command.enabled() == null || command.enabled());
        String dataScope = blank(command.dataScope()) ? "ALL" : command.dataScope().toUpperCase(Locale.ROOT);
        if (!DATA_SCOPES.contains(dataScope)) throw new BusinessException("数据权限范围不正确");
        role.setDataScope(dataScope);
        role.setMenus(load(command.menuIds(), menuRepository::findAllById));
        role.setCustomDepartments("CUSTOM".equals(dataScope) ? load(command.departmentIds(), departmentRepository::findAllById) : new LinkedHashSet<>());
    }

    /** 校验并应用菜单配置。 */
    private void applyMenu(Menu menu, MenuCommand command) {
        String type = blank(command.type()) ? "MENU" : command.type().toUpperCase(Locale.ROOT);
        if (!MENU_TYPES.contains(type)) throw new BusinessException("菜单类型不正确");
        String permission = trim(command.permission());
        menuRepository.findByPermission(permission).filter(existing -> !existing.getId().equals(menu.getId()))
            .ifPresent(existing -> { throw new BusinessException("权限编码已存在"); });
        if (command.parentId() != null && menuRepository.findById(command.parentId()).isEmpty()) throw BusinessException.notFound("父级菜单不存在");
        menu.setParentId(command.parentId());
        menu.setName(require(command.name(), "请输入菜单名称"));
        menu.setType(type);
        menu.setPath(trim(command.path()));
        menu.setComponent(trim(command.component()));
        menu.setIcon(trim(command.icon()));
        menu.setPermission(permission);
        menu.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        menu.setVisible(command.visible() == null || command.visible());
        menu.setEnabled(command.enabled() == null || command.enabled());
    }

    /** 应用部门字段。 */
    private void applyDepartment(Department department, DepartmentCommand command) {
        department.setParentId(command.parentId());
        department.setCode(require(command.code(), "请输入部门编码"));
        department.setName(require(command.name(), "请输入部门名称"));
        department.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        department.setEnabled(command.enabled() == null || command.enabled());
    }

    /** 应用岗位字段。 */
    private void applyPosition(Position position, PositionCommand command) {
        position.setCode(require(command.code(), "请输入岗位编码"));
        position.setName(require(command.name(), "请输入岗位名称"));
        position.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        position.setEnabled(command.enabled() == null || command.enabled());
    }

    private UserView toUserView(UserAccount user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getEnabled(),
            user.getDepartment() == null ? null : user.getDepartment().getId(),
            user.getRoles().stream().map(Role::getId).sorted().toList(), user.getPositions().stream().map(Position::getId).sorted().toList());
    }
    private RoleView toRoleView(Role role) {
        return new RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.getDataScope(), role.getEnabled(),
            role.getMenus().stream().map(Menu::getId).sorted().toList(), role.getCustomDepartments().stream().map(Department::getId).sorted().toList());
    }
    private MenuView toMenuView(Menu menu) {
        return new MenuView(menu.getId(), menu.getParentId(), menu.getName(), menu.getType(), menu.getPath(), menu.getComponent(), menu.getIcon(),
            menu.getPermission(), menu.getSortOrder(), menu.getVisible(), menu.getEnabled());
    }
    private DepartmentView toDepartmentView(Department item) { return new DepartmentView(item.getId(), item.getParentId(), item.getCode(), item.getName(), item.getSortOrder(), item.getEnabled()); }
    private PositionView toPositionView(Position item) { return new PositionView(item.getId(), item.getCode(), item.getName(), item.getSortOrder(), item.getEnabled()); }

    /** 将查询结果切分为稳定分页结构。 */
    private <T> PageResult<T> page(List<T> rows, int page, int size) {
        int safePage = Math.max(1, page), safeSize = Math.min(200, Math.max(1, size));
        int from = Math.min(rows.size(), (safePage - 1) * safeSize), to = Math.min(rows.size(), from + safeSize);
        return new PageResult<>(rows.subList(from, to), rows.size(), safePage, safeSize);
    }
    private Comparator<Menu> menuComparator() { return Comparator.comparing(Menu::getSortOrder).thenComparing(Menu::getId); }
    private <T> LinkedHashSet<T> load(List<Long> ids, Function<Iterable<Long>, List<T>> loader) { return new LinkedHashSet<>(loader.apply(ids == null ? List.of() : ids)); }
    private boolean contains(String value, String keyword) { return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT)); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String trim(String value) { return blank(value) ? null : value.trim(); }
    private String require(String value, String message) { if (blank(value)) throw new BusinessException(message); return value.trim(); }

    public record PageResult<T>(List<T> items, long total, int page, int size) {}
    public record UserCommand(String username, String displayName, String password, Boolean enabled, Long departmentId, List<Long> roleIds, List<Long> positionIds) {}
    public record UserView(Long id, String username, String displayName, Boolean enabled, Long departmentId, List<Long> roleIds, List<Long> positionIds) {}
    public record RoleCommand(String code, String name, String description, String dataScope, Boolean enabled, List<Long> menuIds, List<Long> departmentIds) {}
    public record RoleView(Long id, String code, String name, String description, String dataScope, Boolean enabled, List<Long> menuIds, List<Long> departmentIds) {}
    public record MenuCommand(Long parentId, String name, String type, String path, String component, String icon, String permission, Integer sortOrder, Boolean visible, Boolean enabled) {}
    public record MenuView(Long id, Long parentId, String name, String type, String path, String component, String icon, String permission, Integer sortOrder, Boolean visible, Boolean enabled) {}
    public record DepartmentCommand(Long parentId, String code, String name, Integer sortOrder, Boolean enabled) {}
    public record DepartmentView(Long id, Long parentId, String code, String name, Integer sortOrder, Boolean enabled) {}
    public record PositionCommand(String code, String name, Integer sortOrder, Boolean enabled) {}
    public record PositionView(Long id, String code, String name, Integer sortOrder, Boolean enabled) {}
}
