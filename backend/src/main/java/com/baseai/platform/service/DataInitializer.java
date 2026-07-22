package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Department;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.MenuRepository;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 数据初始化器组件
 *
 * <p>该组件在应用启动时自动执行，负责初始化系统的基础数据，包括：
 * <ul>
 *   <li>安全配置验证：确保密钥、令牌等敏感配置符合安全要求</li>
 *   <li>根部门初始化：创建系统默认的根部门组织结构</li>
 *   <li>菜单权限初始化：创建完整的系统菜单树和按钮权限配置</li>
 *   <li>管理员角色初始化：创建系统管理员角色并分配所有权限</li>
 *   <li>管理员账户初始化：创建默认的系统管理员账户</li>
 * </ul>
 *
 * <p>该组件实现了 {@link ApplicationRunner} 接口，在 Spring Boot 应用完全启动后
 * 自动执行初始化逻辑，确保系统首次运行时具备必要的基础数据。
 *
 * @author BaseAI Platform
 * @see ApplicationRunner
 * @see PlatformProperties
 */
@Component
public class DataInitializer implements ApplicationRunner {
    /** 平台配置属性，包含管理员账户、令牌密钥等配置信息 */
    private final PlatformProperties properties;

    /** 菜单权限数据仓库，用于管理系统菜单和权限 */
    private final MenuRepository menuRepository;

    /** 角色数据仓库，用于管理系统角色 */
    private final RoleRepository roleRepository;

    /** 用户账户数据仓库，用于管理用户信息 */
    private final UserRepository userRepository;

    /** 部门数据仓库，用于管理组织架构 */
    private final DepartmentRepository departmentRepository;

    /** BCrypt 密码编码器，用于加密用户密码 */
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 构造函数，通过依赖注入初始化所有必需的组件
     *
     * @param properties 平台配置属性
     * @param menuRepository 菜单权限数据仓库
     * @param roleRepository 角色数据仓库
     * @param userRepository 用户账户数据仓库
     * @param departmentRepository 部门数据仓库
     * @param passwordEncoder 密码编码器
     */
    public DataInitializer(PlatformProperties properties, MenuRepository menuRepository, RoleRepository roleRepository,
                           UserRepository userRepository, DepartmentRepository departmentRepository, BCryptPasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 应用启动时执行的初始化方法
     *
     * <p>该方法在 Spring Boot 应用完全启动后自动执行，按以下顺序初始化系统基础数据：
     * <ol>
     *   <li>验证安全配置：检查令牌密钥、内部令牌、管理员密码等是否符合安全要求</li>
     *   <li>初始化根部门：创建系统默认的根部门"AI平台"</li>
     *   <li>初始化菜单权限：创建完整的菜单树结构，包括页面菜单和按钮权限</li>
     *   <li>初始化管理员角色：创建或更新"系统管理员"角色，并分配所有菜单权限</li>
     *   <li>初始化管理员账户：创建或更新默认管理员账户，并关联管理员角色</li>
     * </ol>
     *
     * <p>该方法使用事务管理，确保初始化过程的原子性。如果任何步骤失败，
     * 所有更改都会回滚，保证数据一致性。
     *
     * @param arguments 应用启动参数（本方法未使用此参数）
     * @throws IllegalStateException 当安全配置不符合要求时抛出
     */
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        // 第一步：验证安全配置，确保密钥和密码符合安全标准
        validateSecrets();

        // 第二步：初始化根部门，为管理员和组织架构提供基础
        Department rootDepartment = seedRootDepartment();

        // 第三步：初始化所有系统菜单和权限
        seedMenus();

        // 获取所有菜单，用于分配给管理员角色
        List<Menu> menus = menuRepository.findAll();

        // 第四步：初始化或更新系统管理员角色
        Role adminRole = roleRepository.findByCode("ADMIN").orElseGet(() -> {
            Role role = new Role();
            role.setCode("ADMIN");
            role.setName("系统管理员");
            return role;
        });
        // 为管理员角色分配所有菜单权限
        adminRole.setMenus(new LinkedHashSet<>(menus));
        adminRole.setDescription("系统内置管理员角色");
        adminRole.setDataScope("ALL");  // 数据权限范围：全部数据
        adminRole.setEnabled(true);
        roleRepository.save(adminRole);

        // 第五步：初始化或更新默认管理员账户
        String username = properties.getSeed().getAdminUsername();
        UserAccount admin = userRepository.findByUsername(username).orElseGet(() -> {
            UserAccount user = new UserAccount();
            user.setUsername(username);
            user.setDisplayName("系统管理员");
            // 使用 BCrypt 加密管理员密码
            user.setPasswordHash(passwordEncoder.encode(properties.getSeed().getAdminPassword()));
            return user;
        });
        // 如果管理员账户还没有所属部门，关联到根部门
        if (admin.getDepartment() == null) admin.setDepartment(rootDepartment);
        // 为管理员账户分配管理员角色
        admin.getRoles().add(adminRole);
        admin.setEnabled(true);
        userRepository.save(admin);
    }

    /**
     * 初始化完整的系统菜单树和权限结构
     *
     * <p>该方法创建系统的所有菜单项，包括：
     * <ul>
     *   <li>AI 能力模块：AI 对话等功能</li>
     *   <li>系统管理模块：用户、角色、菜单、部门、岗位、字典、参数、在线用户、日志、任务调度等</li>
     *   <li>模型管理模块：模型供应商、模型配置、能力路由等</li>
     *   <li>自动化模块：接口触发及相关操作</li>
     * </ul>
     *
     * <p>菜单类型包括：
     * <ul>
     *   <li>CATALOG：目录节点，用于组织菜单结构</li>
     *   <li>MENU：页面菜单，对应前端路由和页面组件</li>
     *   <li>BUTTON：按钮权限，用于控制页面内的操作权限</li>
     * </ul>
     *
     * <p>该方法是幂等的，重复执行会更新现有菜单而不是创建重复数据。
     */
    private void seedMenus() {
        // ========== AI 能力模块 ==========
        Menu ai = menu(null, "AI 能力", "CATALOG", "/ai", null, "MagicStick", "ai:catalog", 10, true);
        menu(ai.getId(), "AI 对话", "MENU", "/ai-chat", "AiChatView", "ChatDotRound", "ai:chat:invoke", 11, true);

        // ========== 系统管理模块 ==========
        Menu system = menu(null, "系统管理", "CATALOG", "/system", null, "Setting", "system:catalog", 20, true);
        // 初始化用户管理的页面和 CRUD 权限
        seedCrud(system, "用户管理", "/users", "UsersView", "User", "system:user", 21);
        // 初始化角色管理的页面和 CRUD 权限
        seedCrud(system, "角色管理", "/roles", "RolesView", "Avatar", "system:role", 22);
        // 初始化菜单管理的页面和 CRUD 权限
        seedCrud(system, "菜单管理", "/menus", "MenusView", "Menu", "system:menu", 23);
        // 初始化部门管理的页面和 CRUD 权限
        seedCrud(system, "部门管理", "/departments", "DepartmentsView", "OfficeBuilding", "system:department", 24);
        // 初始化岗位管理的页面和 CRUD 权限
        seedCrud(system, "岗位管理", "/positions", "PositionsView", "Briefcase", "system:position", 25);
        // 初始化字典管理的页面和 CRUD 权限
        seedCrud(system, "字典管理", "/dictionaries", "DictionariesView", "Collection", "system:dictionary", 26);
        // 初始化系统参数的页面和 CRUD 权限
        seedCrud(system, "系统参数", "/settings", "SettingsView", "Tools", "system:setting", 27);
        // 在线用户管理页面
        menu(system.getId(), "在线用户", "MENU", "/online-users", "OnlineUsersView", "Connection", "system:session:list", 28, true);
        // 强制下线操作按钮权限
        menu(system.getId(), "强制下线", "BUTTON", null, null, null, "system:session:terminate", 281, false);
        // 操作日志查看页面
        menu(system.getId(), "操作日志", "MENU", "/operation-logs", "OperationLogsView", "Document", "system:audit:operation:list", 29, true);
        // 登录日志查看页面
        menu(system.getId(), "登录日志", "MENU", "/login-logs", "LoginLogsView", "Tickets", "system:audit:login:list", 30, true);
        // 任务调度查看页面
        menu(system.getId(), "任务调度", "MENU", "/tasks", "TasksView", "List", "system:task:view", 31, true);
        // 任务管理操作按钮权限（包括启动、停止、编辑等）
        menu(system.getId(), "任务管理", "BUTTON", null, null, null, "system:task:manage", 311, false);

        // ========== 模型管理模块 ==========
        Menu model = menu(null, "模型管理", "CATALOG", "/models", null, "Cpu", "model:catalog", 40, true);
        // 初始化模型供应商的页面和 CRUD 权限
        seedCrud(model, "模型供应商", "/model-providers", "ModelProvidersView", "Link", "model:provider", 41);
        // 初始化模型配置的页面和 CRUD 权限
        seedCrud(model, "模型配置", "/models", "ModelsView", "Cpu", "model:model", 42);
        // 初始化能力路由的页面和 CRUD 权限
        seedCrud(model, "能力路由", "/model-routes", "ModelRoutesView", "Guide", "model:route", 43);

        // ========== 自动化模块 ==========
        Menu automation = menu(null, "自动化", "CATALOG", "/automation", null, "Operation", "automation:catalog", 50, true);
        // 接口触发管理页面
        Menu trigger = menu(automation.getId(), "接口触发", "MENU", "/automation/api-triggers", "ApiTriggerView", "Promotion", "automation:api-trigger:list", 51, true);
        // 接口触发相关的各项操作按钮权限
        menu(trigger.getId(), "新增接口触发", "BUTTON", null, null, null, "automation:api-trigger:create", 511, false);
        menu(trigger.getId(), "更新接口触发", "BUTTON", null, null, null, "automation:api-trigger:update", 512, false);
        menu(trigger.getId(), "删除接口触发", "BUTTON", null, null, null, "automation:api-trigger:delete", 513, false);
        menu(trigger.getId(), "执行接口触发", "BUTTON", null, null, null, "automation:api-trigger:trigger", 514, false);
        menu(trigger.getId(), "接口触发日志", "BUTTON", null, null, null, "automation:api-trigger:logs", 515, false);

        // ========== 兼容性权限 ==========
        // 以下权限用于兼容旧版本的权限代码，提供统一的管理权限标识
        menu(system.getId(), "兼容用户管理", "BUTTON", null, null, null, "system:user:manage", 901, false);
        menu(system.getId(), "兼容角色管理", "BUTTON", null, null, null, "system:role:manage", 902, false);
        menu(system.getId(), "兼容菜单管理", "BUTTON", null, null, null, "system:menu:manage", 903, false);
    }

    /**
     * 为单个资源模块初始化标准 CRUD 权限结构
     *
     * <p>该方法为指定的资源创建一个页面菜单和四个标准的操作按钮权限：
     * <ul>
     *   <li>页面菜单：用于访问资源列表页面，权限格式为 {prefix}:list</li>
     *   <li>新增操作：用于创建新资源，权限格式为 {prefix}:create</li>
     *   <li>编辑操作：用于修改现有资源，权限格式为 {prefix}:update</li>
     *   <li>删除操作：用于删除资源，权限格式为 {prefix}:delete</li>
     * </ul>
     *
     * @param parent 父级菜单对象，新创建的菜单将作为其子菜单
     * @param name 资源名称，用于显示在菜单中（如"用户管理"）
     * @param path 前端路由路径（如"/users"）
     * @param component 前端组件名称（如"UsersView"）
     * @param icon 菜单图标名称（如"User"），对应前端图标库
     * @param prefix 权限前缀（如"system:user"），用于生成完整的权限标识
     * @param sortOrder 排序序号，决定菜单在同级中的显示顺序
     */
    private void seedCrud(Menu parent, String name, String path, String component, String icon, String prefix, int sortOrder) {
        // 创建资源的列表页面菜单
        Menu page = menu(parent.getId(), name, "MENU", path, component, icon, prefix + ":list", sortOrder, true);
        // 创建新增操作按钮权限，排序序号为 sortOrder*10+1
        menu(page.getId(), "新增" + name, "BUTTON", null, null, null, prefix + ":create", sortOrder * 10 + 1, false);
        // 创建编辑操作按钮权限，排序序号为 sortOrder*10+2
        menu(page.getId(), "编辑" + name, "BUTTON", null, null, null, prefix + ":update", sortOrder * 10 + 2, false);
        // 创建删除操作按钮权限，排序序号为 sortOrder*10+3
        menu(page.getId(), "删除" + name, "BUTTON", null, null, null, prefix + ":delete", sortOrder * 10 + 3, false);
    }

    /**
     * 创建或更新单个菜单项
     *
     * <p>该方法根据权限标识查找现有菜单，如果不存在则创建新菜单。
     * 无论菜单是否已存在，都会更新其所有属性为最新值，确保菜单配置与代码定义保持一致。
     *
     * <p>该方法是幂等的，重复调用会更新现有菜单而不会创建重复数据。
     *
     * @param parentId 父菜单 ID，顶级菜单传入 null
     * @param name 菜单名称，用于在界面上显示
     * @param type 菜单类型：CATALOG（目录）、MENU（页面菜单）、BUTTON（按钮权限）
     * @param path 前端路由路径，仅对 MENU 类型有效，BUTTON 类型传入 null
     * @param component 前端组件名称，仅对 MENU 类型有效，BUTTON 类型传入 null
     * @param icon 图标名称，对应前端图标库中的图标标识
     * @param permission 权限标识，用于权限控制和菜单去重的唯一标识
     * @param sortOrder 排序序号，数字越小越靠前
     * @param visible 是否在菜单中可见，BUTTON 类型通常为 false
     * @return 保存后的菜单对象
     */
    private Menu menu(Long parentId, String name, String type, String path, String component, String icon,
                      String permission, int sortOrder, boolean visible) {
        // 根据权限标识查找现有菜单，不存在则创建新对象
        Menu menu = menuRepository.findByPermission(permission).orElseGet(Menu::new);
        // 设置菜单的所有属性
        menu.setParentId(parentId);
        menu.setName(name);
        menu.setType(type);
        menu.setPath(path);
        menu.setComponent(component);
        menu.setIcon(icon);
        menu.setPermission(permission);
        menu.setSortOrder(sortOrder);
        menu.setVisible(visible);
        menu.setEnabled(true);  // 默认启用菜单
        // 保存并返回菜单对象
        return menuRepository.save(menu);
    }

    /** 初始化默认根部门供管理员和数据权限使用。 */
    private Department seedRootDepartment() {
        Department department = departmentRepository.findByCode("ROOT").orElseGet(Department::new);
        department.setCode("ROOT");
        department.setName("AI平台");
        department.setSortOrder(1);
        department.setEnabled(true);
        return departmentRepository.save(department);
    }

    /** 阻止弱密钥或示例密码进入运行环境。 */
    private void validateSecrets() {
        String tokenSecret = properties.getToken().getSecret();
        String internalToken = properties.getPythonWorker().getInternalToken();
        String adminPassword = properties.getSeed().getAdminPassword();
        String encryptionKey = properties.getConfigEncryptionKey();
        if (tokenSecret == null || tokenSecret.length() < 32 || tokenSecret.contains("replace-with")) {
            throw new IllegalStateException("APP_TOKEN_SECRET 必须设置为至少 32 位随机字符串");
        }
        if (internalToken == null || internalToken.length() < 24 || internalToken.contains("replace-with")) {
            throw new IllegalStateException("PYTHON_WORKER_INTERNAL_TOKEN 必须设置为随机字符串");
        }
        if (adminPassword == null || adminPassword.length() < 10 || adminPassword.contains("replace")) {
            throw new IllegalStateException("APP_SEED_ADMIN_PASSWORD 必须设置为安全密码");
        }
        try {
            if (encryptionKey == null || java.util.Base64.getDecoder().decode(encryptionKey).length != 32) {
                throw new IllegalStateException("APP_CONFIG_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_CONFIG_ENCRYPTION_KEY 必须是有效 Base64", exception);
        }
    }
}
