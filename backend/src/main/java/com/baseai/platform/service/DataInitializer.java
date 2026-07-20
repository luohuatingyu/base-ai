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

@Component
public class DataInitializer implements ApplicationRunner {
    private final PlatformProperties properties;
    private final MenuRepository menuRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DataInitializer(PlatformProperties properties, MenuRepository menuRepository, RoleRepository roleRepository,
                           UserRepository userRepository, DepartmentRepository departmentRepository, BCryptPasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 校验安全配置并初始化管理员、角色和基础权限。 */
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        validateSecrets();
        Department rootDepartment = seedRootDepartment();
        seedMenus();
        List<Menu> menus = menuRepository.findAll();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseGet(() -> {
            Role role = new Role();
            role.setCode("ADMIN");
            role.setName("系统管理员");
            return role;
        });
        adminRole.setMenus(new LinkedHashSet<>(menus));
        adminRole.setDescription("系统内置管理员角色");
        adminRole.setDataScope("ALL");
        adminRole.setEnabled(true);
        roleRepository.save(adminRole);
        String username = properties.getSeed().getAdminUsername();
        if (!userRepository.existsByUsername(username)) {
            UserAccount admin = new UserAccount();
            admin.setUsername(username);
            admin.setDisplayName("系统管理员");
            admin.setPasswordHash(passwordEncoder.encode(properties.getSeed().getAdminPassword()));
            admin.setDepartment(rootDepartment);
            admin.setRoles(new LinkedHashSet<>(List.of(adminRole)));
            userRepository.save(admin);
        }
    }

    /** 初始化完整系统菜单、按钮权限和兼容权限。 */
    private void seedMenus() {
        Menu ai = menu(null, "AI 能力", "CATALOG", "/ai", null, "MagicStick", "ai:catalog", 10, true);
        menu(ai.getId(), "AI 对话", "MENU", "/ai-chat", "AiChatView", "ChatDotRound", "ai:chat:invoke", 11, true);

        Menu system = menu(null, "系统管理", "CATALOG", "/system", null, "Setting", "system:catalog", 20, true);
        seedCrud(system, "用户管理", "/users", "UsersView", "User", "system:user", 21);
        seedCrud(system, "角色管理", "/roles", "RolesView", "Avatar", "system:role", 22);
        seedCrud(system, "菜单管理", "/menus", "MenusView", "Menu", "system:menu", 23);
        seedCrud(system, "部门管理", "/departments", "DepartmentsView", "OfficeBuilding", "system:department", 24);
        seedCrud(system, "岗位管理", "/positions", "PositionsView", "Briefcase", "system:position", 25);
        seedCrud(system, "字典管理", "/dictionaries", "DictionariesView", "Collection", "system:dictionary", 26);
        seedCrud(system, "系统参数", "/settings", "SettingsView", "Tools", "system:setting", 27);
        menu(system.getId(), "在线用户", "MENU", "/online-users", "OnlineUsersView", "Connection", "system:session:list", 28, true);
        menu(system.getId(), "强制下线", "BUTTON", null, null, null, "system:session:terminate", 281, false);
        menu(system.getId(), "操作日志", "MENU", "/operation-logs", "OperationLogsView", "Document", "system:audit:operation:list", 29, true);
        menu(system.getId(), "登录日志", "MENU", "/login-logs", "LoginLogsView", "Tickets", "system:audit:login:list", 30, true);
        menu(system.getId(), "任务调度", "MENU", "/tasks", "TasksView", "List", "system:task:view", 31, true);
        menu(system.getId(), "任务管理", "BUTTON", null, null, null, "system:task:manage", 311, false);

        Menu model = menu(null, "模型管理", "CATALOG", "/models", null, "Cpu", "model:catalog", 40, true);
        seedCrud(model, "模型供应商", "/model-providers", "ModelProvidersView", "Link", "model:provider", 41);
        seedCrud(model, "模型配置", "/models", "ModelsView", "Cpu", "model:model", 42);
        seedCrud(model, "能力路由", "/model-routes", "ModelRoutesView", "Guide", "model:route", 43);

        Menu automation = menu(null, "自动化", "CATALOG", "/automation", null, "Operation", "automation:catalog", 50, true);
        Menu trigger = menu(automation.getId(), "接口触发", "MENU", "/automation/api-triggers", "ApiTriggerView", "Promotion", "automation:api-trigger:list", 51, true);
        menu(trigger.getId(), "新增接口触发", "BUTTON", null, null, null, "automation:api-trigger:create", 511, false);
        menu(trigger.getId(), "更新接口触发", "BUTTON", null, null, null, "automation:api-trigger:update", 512, false);
        menu(trigger.getId(), "删除接口触发", "BUTTON", null, null, null, "automation:api-trigger:delete", 513, false);
        menu(trigger.getId(), "执行接口触发", "BUTTON", null, null, null, "automation:api-trigger:trigger", 514, false);
        menu(trigger.getId(), "接口触发日志", "BUTTON", null, null, null, "automation:api-trigger:logs", 515, false);

        menu(system.getId(), "兼容用户管理", "BUTTON", null, null, null, "system:user:manage", 901, false);
        menu(system.getId(), "兼容角色管理", "BUTTON", null, null, null, "system:role:manage", 902, false);
        menu(system.getId(), "兼容菜单管理", "BUTTON", null, null, null, "system:menu:manage", 903, false);
    }

    /** 初始化单个系统资源的页面和四类操作权限。 */
    private void seedCrud(Menu parent, String name, String path, String component, String icon, String prefix, int sortOrder) {
        Menu page = menu(parent.getId(), name, "MENU", path, component, icon, prefix + ":list", sortOrder, true);
        menu(page.getId(), "新增" + name, "BUTTON", null, null, null, prefix + ":create", sortOrder * 10 + 1, false);
        menu(page.getId(), "编辑" + name, "BUTTON", null, null, null, prefix + ":update", sortOrder * 10 + 2, false);
        menu(page.getId(), "删除" + name, "BUTTON", null, null, null, prefix + ":delete", sortOrder * 10 + 3, false);
    }

    /** 获取或创建固定菜单节点。 */
    private Menu menu(Long parentId, String name, String type, String path, String component, String icon,
                      String permission, int sortOrder, boolean visible) {
        Menu menu = menuRepository.findByPermission(permission).orElseGet(Menu::new);
        menu.setParentId(parentId);
        menu.setName(name);
        menu.setType(type);
        menu.setPath(path);
        menu.setComponent(component);
        menu.setIcon(icon);
        menu.setPermission(permission);
        menu.setSortOrder(sortOrder);
        menu.setVisible(visible);
        menu.setEnabled(true);
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
