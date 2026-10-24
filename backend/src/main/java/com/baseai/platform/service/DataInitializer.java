package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Department;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.domain.DictionaryData;
import com.baseai.platform.domain.DictionaryType;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
import com.baseai.platform.repository.DictionaryDataRepository;
import com.baseai.platform.repository.DictionaryTypeRepository;
import com.baseai.platform.repository.MenuRepository;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.io.IOException;

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
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PASSWORD_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*_-+=".toCharArray();
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

    /** 模型类型字典仓储。 */
    private final DictionaryTypeRepository dictionaryTypeRepository;

    /** 模型类型字典数据仓储。 */
    private final DictionaryDataRepository dictionaryDataRepository;

    /** 系统参数数据仓库，用于启动时回填运行时缓存。 */
    private final SystemSettingRepository systemSettingRepository;

    /** 系统参数运行时缓存同步服务。 */
    private final SystemSettingCacheService systemSettingCacheService;

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
                           UserRepository userRepository, DepartmentRepository departmentRepository,
                           DictionaryTypeRepository dictionaryTypeRepository, DictionaryDataRepository dictionaryDataRepository,
                           BCryptPasswordEncoder passwordEncoder, SystemSettingRepository systemSettingRepository,
                           SystemSettingCacheService systemSettingCacheService) {
        this.properties = properties;
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.dictionaryTypeRepository = dictionaryTypeRepository;
        this.dictionaryDataRepository = dictionaryDataRepository;
        this.passwordEncoder = passwordEncoder;
        this.systemSettingRepository = systemSettingRepository;
        this.systemSettingCacheService = systemSettingCacheService;
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

        // 初始化可扩展的模型类型目录，后续类型可直接通过字典管理追加
        seedModelTypes();

        // 初始化默认关闭的插件适配器开关，仅允许节点管理专用接口切换。

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
        seedOperationsRole(menus);

        // 第五步：初始化或更新默认管理员账户
        String username = properties.getSeed().getAdminUsername();
        UserAccount admin = userRepository.findByUsername(username).orElse(null);
        boolean creatingAdmin = admin == null;
        if (creatingAdmin) {
            UserAccount user = new UserAccount();
            user.setUsername(username);
            user.setDisplayName("系统管理员");
            admin = user;
        }
        synchronizeAdminPassword(admin, creatingAdmin);
        // 如果管理员账户还没有所属部门，关联到根部门
        if (admin.getDepartment() == null) admin.setDepartment(rootDepartment);
        // 为管理员账户分配管理员角色
        admin.getRoles().add(adminRole);
        admin.setEnabled(true);
        userRepository.save(admin);

        // 启动时将数据库中的系统参数完整回填到运行时缓存。
        systemSettingCacheService.applyAll(systemSettingRepository.findAll());
    }

    /** 初始化只管理本人自动化设备 Agent 的运维角色。 */
    private void seedOperationsRole(List<Menu> menus) {
        Role operations = roleRepository.findByCode("OPS").orElseGet(() -> {
            Role role = new Role();
            role.setCode("OPS");
            role.setName("系统运维");
            return role;
        });
        LinkedHashSet<Menu> permissions = new LinkedHashSet<>(operations.getMenus());
        menus.stream().filter(menu -> "operations:catalog".equals(menu.getPermission())
            || "automation:catalog".equals(menu.getPermission())
            || menu.getPermission() != null && menu.getPermission().startsWith("automation:device-agent:")).forEach(permissions::add);
        operations.setMenus(permissions);
        operations.setDescription("系统内置自动化设备 Agent 运维角色");
        operations.setDataScope("SELF");
        operations.setSortOrder(20);
        operations.setEnabled(true);
        roleRepository.save(operations);
    }

    /** 首次创建时设置密码，并按显式开关将已有管理员密码同步为种子密码。 */
    private void synchronizeAdminPassword(UserAccount admin, boolean creatingAdmin) {
        String seedPassword = resolveAdminPassword(creatingAdmin);
        if (seedPassword == null) return;
        if (creatingAdmin || (properties.getSeed().isAdminPasswordSyncEnabled()
            && !passwordEncoder.matches(seedPassword, admin.getPasswordHash()))) {
            admin.setPasswordHash(passwordEncoder.encode(seedPassword));
        }
    }

    /** 仅在创建或显式同步管理员时解析强密码，首次缺失或弱配置自动生成。 */
    private String resolveAdminPassword(boolean creatingAdmin) {
        if (!creatingAdmin && !properties.getSeed().isAdminPasswordSyncEnabled()) return null;
        String configured = trim(properties.getSeed().getAdminPassword());
        if (strongPassword(configured)) return configured;
        if (!creatingAdmin) {
            throw new IllegalStateException("APP_SEED_ADMIN_PASSWORD 开启同步时必须包含大小写字母、数字和符号");
        }
        Path passwordFile = Path.of(properties.getSeed().getAdminPasswordFile()).toAbsolutePath().normalize();
        try {
            if (Files.exists(passwordFile, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(passwordFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("管理员初始密码文件必须是普通文件");
                }
                String persisted = Files.readString(passwordFile).strip();
                if (!strongPassword(persisted)) throw new IllegalStateException("管理员初始密码文件内容不符合强度要求");
                restrictPasswordFile(passwordFile);
                return persisted;
            }
            String generated = generatedPassword();
            persistGeneratedPassword(passwordFile, generated);
            log.warn("event=admin_bootstrap_password_generated file={}", passwordFile);
            return generated;
        } catch (IOException exception) {
            throw new IllegalStateException("无法安全保存管理员初始密码", exception);
        }
    }

    /** 使用安全随机源生成四类字符齐全的 32 位初始密码。 */
    private String generatedPassword() {
        char[] value = new char[32];
        value[0] = "ABCDEFGHJKLMNPQRSTUVWXYZ".charAt(RANDOM.nextInt(23));
        value[1] = "abcdefghijkmnopqrstuvwxyz".charAt(RANDOM.nextInt(24));
        value[2] = "23456789".charAt(RANDOM.nextInt(8));
        value[3] = "!@#$%^&*_-+=".charAt(RANDOM.nextInt(12));
        for (int index = 4; index < value.length; index++) {
            value[index] = PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)];
        }
        for (int index = value.length - 1; index > 0; index--) {
            int target = RANDOM.nextInt(index + 1);
            char temporary = value[index];
            value[index] = value[target];
            value[target] = temporary;
        }
        return new String(value);
    }

    /** 原子写入初始密码并把权限收紧为仅容器运行用户可读写。 */
    private void persistGeneratedPassword(Path target, String password) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("密码文件缺少父目录");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".admin-password-", ".tmp");
        try {
            Files.writeString(temporary, password + System.lineSeparator());
            restrictPasswordFile(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            restrictPasswordFile(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 在支持 POSIX 权限的平台把密码文件限制为 0600。 */
    private void restrictPasswordFile(Path target) throws IOException {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            if (!target.toFile().setReadable(false, false) || !target.toFile().setReadable(true, true)
                || !target.toFile().setWritable(false, false) || !target.toFile().setWritable(true, true)) {
                throw new IOException("无法限制密码文件权限");
            }
        }
    }

    /** 校验密码长度、BCrypt 字节上限及四类字符。 */
    private boolean strongPassword(String password) {
        int minimum = Math.max(12, properties.getLoginSecurity().getPasswordMinLength());
        return password != null && password.length() >= minimum && PasswordPolicy.utf8Length(password) <= 72
            && PasswordPolicy.hasRequiredCharacterClasses(password) && !password.contains("replace");
    }

    /** 将可选配置规范化为空值或去除首尾空白的内容。 */
    private String trim(String value) { return value == null ? null : value.trim(); }

    /** 初始化首批模型类型，重复启动只补充缺失项且不覆盖管理员配置。 */
    private void seedModelTypes() {
        DictionaryType type=dictionaryTypeRepository.findByCode("llm_model_type").orElseGet(DictionaryType::new);
        if(type.getId()==null){
            type.setCode("llm_model_type");
            type.setName("模型类型");
            type.setDescription("模型可支持的输入或能力类型编码");
            type.setEnabled(true);
            dictionaryTypeRepository.save(type);
        }
        seedModelType("text_model","文本模型",10);
        seedModelType("vision_model","视觉模型",20);
        seedModelType("embedding_model","向量模型",30);
        seedModelType("image_model","图片生成模型",40);
        seedModelType("video_model","视频生成模型",50);
    }

    /** 仅在缺失时写入内置模型类型，保留管理员对已有字典项的调整。 */
    private void seedModelType(String value,String label,int sortOrder) {
        if(dictionaryDataRepository.findByTypeCodeOrderBySortOrderAscIdAsc("llm_model_type").stream()
            .anyMatch(item->value.equals(item.getDictValue())))return;
        DictionaryData data=new DictionaryData();
        data.setTypeCode("llm_model_type");
        data.setDictValue(value);
        data.setLabel(label);
        data.setSortOrder(sortOrder);
        data.setEnabled(true);
        dictionaryDataRepository.save(data);
    }

    /**
     * 初始化完整的系统菜单树和权限结构
     *
     * <p>该方法创建系统的所有菜单项，包括：
     * <ul>
     *   <li>系统管理模块：访问控制、组织、字典和参数</li>
     *   <li>运维管理模块：邮件和监控审计</li>
     *   <li>AI 视频模块：内容创作、视觉资产、生成任务和发布</li>
     *   <li>AI 能力模块：AI 对话和模型</li>
     *   <li>自动化模块：设备 Agent</li>
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
        // ========== AI 视频模块 ==========
        seedAiVideoMenus();

        // ========== 套餐管理模块 ==========
        Menu packages = menu(null, "套餐管理", "CATALOG", "/packages", null, "Goods",
            "package:catalog", 50, true);
        menu(packages.getId(), "套餐调整", "MENU", "/package-adjustment", "PackageAdjustmentView", "Goods",
            "ai:package-adjustment:list", 10, true);

        // ========== AI 能力模块 ==========
        Menu ai = menu(null, "AI 能力", "CATALOG", "/ai", null, "MagicStick", "ai:catalog", 30, true);
        menu(ai.getId(), "AI 对话", "MENU", "/ai-chat", "AiChatView", "ChatDotRound", "ai:chat:invoke", 11, true);
        menu(ai.getId(), "AI 生成", "MENU", "/ai-generation", "AiGenerationView", "Picture", "ai:generation:invoke", 13, true);
        Menu model = menu(ai.getId(), "模型管理", "CATALOG", "/models", null, "Cpu",
            "ai:model:catalog", 20, true);
        seedCrud(model, "模型供应商", "/model-providers", "ModelProvidersView", "Link",
            "ai:model:provider", 21);
        seedCrud(model, "模型配置", "/models", "ModelsView", "Cpu", "ai:model:model", 22);
        seedCrud(model, "能力路由", "/model-routes", "ModelRoutesView", "Guide", "ai:model:route", 23);

        // AI 小说是独立的原著创作入口，影视改编剧本仍归属于 AI 视频工作台。
        menu(null, "AI剧本", "MENU", "/ai-novel", "AiScriptView", "EditPen",
            "ai:novel:invoke", 35, true);

        // ========== 自动化模块 ==========
        Menu automation = menu(null, "自动化", "CATALOG", "/automation", null, "Operation",
            "automation:catalog", 40, true);
        // 设备 Agent 的高权限自动化动作与查看、配置和删除权限分离。
        Menu deviceAgents = menu(automation.getId(), "设备 Agent 管理", "MENU", "/automation/device-agents",
            "DeviceAgentsView", "Iphone", "automation:device-agent:list", 13, true);
        menu(deviceAgents.getId(), "新增设备 Agent", "BUTTON", null, null, null,
            "automation:device-agent:create", 131, false);
        menu(deviceAgents.getId(), "修改设备 Agent", "BUTTON", null, null, null,
            "automation:device-agent:update", 132, false);
        menu(deviceAgents.getId(), "删除设备 Agent", "BUTTON", null, null, null,
            "automation:device-agent:delete", 133, false);
        menu(deviceAgents.getId(), "执行设备自动化", "BUTTON", null, null, null,
            "automation:device-agent:execute", 134, false);

        // ========== 运维管理模块 ==========
        Menu operations = menu(null, "运维管理", "CATALOG", "/operations", null, "Monitor",
            "operations:catalog", 20, true);
        // 邮件属于运维范畴，归入运维管理并排在服务器管理之后、监控审计之前。
        Menu mail = menu(operations.getId(), "邮件管理", "CATALOG", "/mail", null, "Message",
            "system:mail:catalog", 15, true);
        seedCrud(mail, "邮箱配置", "/mail/accounts", "MailAccountsView", "MessageBox",
            "system:mail:account", 41);
        seedCrud(mail, "邮件路由", "/mail/routes", "MailRoutesView", "Promotion",
            "system:mail:route", 42);
        Menu monitoring = menu(operations.getId(), "监控审计", "CATALOG", "/operations/monitoring",
            null, "DataAnalysis", "operations:monitoring:catalog", 20, true);
        Menu onlineUsers = menu(monitoring.getId(), "在线用户", "MENU", "/online-users",
            "OnlineUsersView", "Connection", "operations:session:list", 21, true);
        menu(onlineUsers.getId(), "强制下线", "BUTTON", null, null, null,
            "operations:session:terminate", 211, false);
        menu(monitoring.getId(), "操作日志", "MENU", "/operation-logs", "OperationLogsView",
            "Document", "operations:audit:operation:list", 22, true);
        menu(monitoring.getId(), "登录日志", "MENU", "/login-logs", "LoginLogsView",
            "Tickets", "operations:audit:login:list", 23, true);
        Menu tasks = menu(monitoring.getId(), "任务调度", "MENU", "/tasks", "TasksView", "List",
            "operations:task:view", 24, true);
        menu(tasks.getId(), "任务管理", "BUTTON", null, null, null,
            "operations:task:manage", 241, false);

        // ========== 系统管理模块 ==========
        Menu system = menu(null, "系统管理", "CATALOG", "/system", null, "Setting",
            "system:catalog", 10, true);
        Menu access = menu(system.getId(), "访问控制", "CATALOG", "/system/access", null, "Lock",
            "system:access:catalog", 10, true);
        seedCrud(access, "用户管理", "/users", "UsersView", "User", "system:user", 11);
        seedCrud(access, "角色管理", "/roles", "RolesView", "Avatar", "system:role", 12);
        seedCrud(access, "菜单管理", "/menus", "MenusView", "Menu", "system:menu", 13);
        Menu apiKeys = menu(access.getId(), "API Key 管理", "MENU", "/api-keys", "ApiKeysView", "Key",
            "system:api-key:list", 14, true);
        menu(apiKeys.getId(), "新增 API Key", "BUTTON", null, null, null,
            "system:api-key:create", 141, false);
        menu(apiKeys.getId(), "更新 API Key", "BUTTON", null, null, null,
            "system:api-key:update", 142, false);
        menu(apiKeys.getId(), "吊销 API Key", "BUTTON", null, null, null,
            "system:api-key:delete", 143, false);
        menu(apiKeys.getId(), "轮换 API Key", "BUTTON", null, null, null,
            "system:api-key:rotate", 144, false);
        Menu organization = menu(system.getId(), "组织管理", "CATALOG", "/system/organization",
            null, "OfficeBuilding", "system:organization:catalog", 20, true);
        seedCrud(organization, "部门管理", "/departments", "DepartmentsView", "OfficeBuilding",
            "system:department", 21);
        seedCrud(organization, "岗位管理", "/positions", "PositionsView", "Briefcase",
            "system:position", 22);
        seedCrud(system, "字典管理", "/dictionaries", "DictionariesView", "Collection",
            "system:dictionary", 31);
        seedCrud(system, "系统参数", "/settings", "SettingsView", "Tools", "system:setting", 32);

    }

    /**
     * 初始化 AI 视频工作台的菜单和细粒度权限。
     *
     * <p>AI 视频作为独立业务域，按内容创作、资产中心、生成中心和发布中心组织，
     * 物品资产单独使用 {@code item} 权限前缀，便于后续配置独立角色授权。</p>
     */
    private void seedAiVideoMenus() {
        Menu aiVideo = menu(null, "AI视频", "CATALOG", "/ai-video", null, "VideoCamera", "ai:video:catalog", 36, true);
        menu(aiVideo.getId(), "工作台首页", "MENU", "/ai-video/dashboard", "AiVideoView", "House",
            "ai:video:dashboard:list", 1, true);

        Menu content = menu(aiVideo.getId(), "内容创作", "CATALOG", "/ai-video/content", null, "EditPen",
            "ai:video:content:catalog", 10, true);
        seedCrud(content, "项目管理", "/ai-video/projects", "AiVideoView", "FolderOpened", "ai:video:project", 11);
        seedCrud(content, "剧本管理", "/ai-video/scripts", "AiVideoView", "Document", "ai:video:script", 12);
        seedCrud(content, "分镜管理", "/ai-video/storyboards", "AiVideoView", "Film", "ai:video:storyboard", 13);

        Menu assets = menu(aiVideo.getId(), "资产中心", "CATALOG", "/ai-video/assets", null, "Collection",
            "ai:video:asset:catalog", 20, true);
        seedCrud(assets, "角色资产", "/ai-video/assets/characters", "AiVideoView", "User", "ai:video:character", 21);
        seedCrud(assets, "场景资产", "/ai-video/assets/scenes", "AiVideoView", "Picture", "ai:video:scene", 22);
        seedCrud(assets, "物品资产", "/ai-video/assets/items", "AiVideoView", "Goods", "ai:video:item", 23);
        seedCrud(assets, "服装造型", "/ai-video/assets/costumes", "AiVideoView", "Suitcase", "ai:video:costume", 24);
        seedCrud(assets, "动作表情", "/ai-video/assets/motions", "AiVideoView", "Aim", "ai:video:motion", 25);
        seedCrud(assets, "画风模板", "/ai-video/assets/styles", "AiVideoView", "Brush", "ai:video:style", 26);
        seedCrud(assets, "声音管理", "/ai-video/assets/voices", "AiVideoView", "Microphone", "ai:video:voice", 27);
        seedCrud(assets, "音乐音效", "/ai-video/assets/sounds", "AiVideoView", "Headset", "ai:video:sound", 28);

        Menu generation = menu(aiVideo.getId(), "生成中心", "CATALOG", "/ai-video/generation", null, "VideoPlay",
            "ai:video:generation:catalog", 30, true);
        seedCrud(generation, "生成任务", "/ai-video/tasks", "AiVideoView", "List", "ai:video:task", 31);
        seedCrud(generation, "视频合成", "/ai-video/renders", "AiVideoView", "VideoCamera", "ai:video:render", 32);

        Menu publishing = menu(aiVideo.getId(), "发布中心", "CATALOG", "/ai-video/publishing", null, "Promotion",
            "ai:video:publishing:catalog", 40, true);
        seedCrud(publishing, "成片管理", "/ai-video/videos", "AiVideoView", "VideoCamera", "ai:video:video", 41);
        seedCrud(publishing, "审核管理", "/ai-video/reviews", "AiVideoView", "DocumentChecked", "ai:video:review", 42);
        seedCrud(publishing, "发布管理", "/ai-video/publishes", "AiVideoView", "Promotion", "ai:video:publish", 43);
        seedCrud(publishing, "数据分析", "/ai-video/analytics", "AiVideoView", "DataAnalysis", "ai:video:analytics", 44);
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
     * @return 创建或更新后的页面菜单
     */
    private Menu seedCrud(Menu parent, String name, String path, String component, String icon, String prefix, int sortOrder) {
        // 创建资源的列表页面菜单
        Menu page = menu(parent.getId(), name, "MENU", path, component, icon, prefix + ":list", sortOrder, true);
        // 创建新增操作按钮权限，排序序号为 sortOrder*10+1
        menu(page.getId(), "新增" + name, "BUTTON", null, null, null, prefix + ":create", sortOrder * 10 + 1, false);
        // 创建编辑操作按钮权限，排序序号为 sortOrder*10+2
        menu(page.getId(), "编辑" + name, "BUTTON", null, null, null, prefix + ":update", sortOrder * 10 + 2, false);
        // 创建删除操作按钮权限，排序序号为 sortOrder*10+3
        menu(page.getId(), "删除" + name, "BUTTON", null, null, null, prefix + ":delete", sortOrder * 10 + 3, false);
        return page;
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
        String encryptionKey = properties.getConfigEncryptionKey();
        String apiKeyHashSecret = properties.getApiKey().getHashSecret();
        if (tokenSecret == null || tokenSecret.length() < 32 || tokenSecret.contains("replace-with")) {
            throw new IllegalStateException("APP_TOKEN_SECRET 必须设置为至少 32 位随机字符串");
        }
        if (internalToken == null || internalToken.length() < 24 || internalToken.contains("replace-with")) {
            throw new IllegalStateException("PYTHON_WORKER_INTERNAL_TOKEN 必须设置为随机字符串");
        }
        try {
            if (encryptionKey == null || java.util.Base64.getDecoder().decode(encryptionKey).length != 32) {
                throw new IllegalStateException("APP_CONFIG_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_CONFIG_ENCRYPTION_KEY 必须是有效 Base64", exception);
        }
        if (apiKeyHashSecret == null || apiKeyHashSecret.length() < 32) {
            throw new IllegalStateException("APP_API_KEY_HASH_SECRET 必须设置为至少 32 位密钥");
        }
    }
}
