package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.DictionaryData;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.DepartmentRepository;
import com.baseai.platform.repository.DictionaryDataRepository;
import com.baseai.platform.repository.DictionaryTypeRepository;
import com.baseai.platform.repository.MenuRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.repository.SystemSettingRepository;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.workflow.WorkflowAdapterLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataInitializerTest {
    private static final String SEED_PASSWORD = "Configured-Password-123!";

    private PlatformProperties properties;
    private MenuRepository menuRepository;
    private UserRepository userRepository;
    private BCryptPasswordEncoder passwordEncoder;
    private DictionaryDataRepository dictionaryDataRepository;
    private SystemSettingRepository systemSettingRepository;
    private SystemSettingCacheService systemSettingCacheService;
    private DataInitializer initializer;

    /** 为每个管理员初始化场景准备有效安全配置和隔离仓储。 */
    @BeforeEach
    void setUp() {
        properties = validProperties();
        menuRepository = mock(MenuRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        userRepository = mock(UserRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        DictionaryTypeRepository dictionaryTypeRepository = mock(DictionaryTypeRepository.class);
        dictionaryDataRepository = mock(DictionaryDataRepository.class);
        systemSettingRepository = mock(SystemSettingRepository.class);
        systemSettingCacheService = mock(SystemSettingCacheService.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);

        AtomicLong menuId = new AtomicLong();
        when(menuRepository.save(any())).thenAnswer(invocation -> {
            Menu menu = invocation.getArgument(0);
            if (menu.getId() == null) menu.setId(menuId.incrementAndGet());
            return menu;
        });
        when(menuRepository.findAll()).thenReturn(List.of());
        when(roleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(departmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dictionaryTypeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dictionaryDataRepository.findByTypeCodeOrderBySortOrderAscIdAsc("llm_model_type"))
            .thenReturn(List.of());
        when(systemSettingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        initializer = new DataInitializer(properties, menuRepository, roleRepository, userRepository,
            departmentRepository, dictionaryTypeRepository, dictionaryDataRepository, passwordEncoder,
            systemSettingRepository, systemSettingCacheService);
    }

    /** 首次启动应同时补齐文本、视觉和向量三种内置模型类型。 */
    @Test
    void seedsEmbeddingModelTypeWithBuiltInCatalog() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(SEED_PASSWORD)).thenReturn("created-hash");

        initializer.run(null);

        ArgumentCaptor<DictionaryData> captor = ArgumentCaptor.forClass(DictionaryData.class);
        verify(dictionaryDataRepository, atLeastOnce()).save(captor.capture());
        Map<String, String> labels = captor.getAllValues().stream().collect(Collectors.toMap(
            DictionaryData::getDictValue, DictionaryData::getLabel));
        assertEquals(Map.of(
            "text_model", "文本模型",
            "vision_model", "视觉模型",
            "embedding_model", "向量模型"
        ), labels);
    }

    /** 未配置同步开关时必须保留已有管理员密码。 */
    @Test
    void preservesExistingPasswordByDefault() {
        UserAccount admin = existingAdmin("existing-hash");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer.run(null);

        assertEquals("existing-hash", admin.getPasswordHash());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(passwordEncoder, never()).encode(any());
        verify(systemSettingCacheService).applyAll(any());
    }

    /** 显式关闭同步开关时必须保留已有管理员密码。 */
    @Test
    void preservesExistingPasswordWhenSyncIsDisabled() {
        properties.getSeed().setAdminPasswordSyncEnabled(false);
        UserAccount admin = existingAdmin("existing-hash");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer.run(null);

        assertEquals("existing-hash", admin.getPasswordHash());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(passwordEncoder, never()).encode(any());
    }

    /** 显式开启同步且密码不一致时必须更新已有管理员密码。 */
    @Test
    void synchronizesDifferentExistingPasswordWhenEnabled() {
        properties.getSeed().setAdminPasswordSyncEnabled(true);
        UserAccount admin = existingAdmin("existing-hash");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(SEED_PASSWORD, "existing-hash")).thenReturn(false);
        when(passwordEncoder.encode(SEED_PASSWORD)).thenReturn("synchronized-hash");

        initializer.run(null);

        assertEquals("synchronized-hash", admin.getPasswordHash());
    }

    /** 显式开启同步但密码已一致时不得重复生成哈希。 */
    @Test
    void keepsMatchingExistingPasswordHashWhenEnabled() {
        properties.getSeed().setAdminPasswordSyncEnabled(true);
        UserAccount admin = existingAdmin("matching-hash");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(SEED_PASSWORD, "matching-hash")).thenReturn(true);

        initializer.run(null);

        assertEquals("matching-hash", admin.getPasswordHash());
        verify(passwordEncoder, never()).encode(any());
    }

    /** 管理员首次创建时无论同步开关状态都必须设置种子密码。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsAdminWithSeedPasswordRegardlessOfSyncSetting(boolean syncEnabled) {
        properties.getSeed().setAdminPasswordSyncEnabled(syncEnabled);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(SEED_PASSWORD)).thenReturn("created-hash");

        initializer.run(null);

        verify(userRepository).save(argThat(admin -> "admin".equals(admin.getUsername())
            && "created-hash".equals(admin.getPasswordHash())));
        verify(passwordEncoder).encode(SEED_PASSWORD);
    }

    /** 同步开关应默认关闭，并能从 app.seed 配置显式开启。 */
    @Test
    void bindsAdminPasswordSyncEnabledWithDisabledDefault() {
        assertFalse(new PlatformProperties().getSeed().isAdminPasswordSyncEnabled());

        PlatformProperties bound = new Binder(new MapConfigurationPropertySource(
            Map.of("app.seed.admin-password-sync-enabled", "true")))
            .bind("app", Bindable.of(PlatformProperties.class)).orElseThrow(IllegalStateException::new);

        assertTrue(bound.getSeed().isAdminPasswordSyncEnabled());
    }

    /** 首次创建且未提供强密码时应生成并持久化随机密码。 */
    @Test
    void generatesPersistentStrongPasswordForFirstAdmin(@TempDir Path temporary) throws Exception {
        properties.getSeed().setAdminPassword("");
        Path passwordFile = temporary.resolve("admin-password");
        properties.getSeed().setAdminPasswordFile(passwordFile.toString());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("generated-hash");

        initializer.run(null);

        String generated = Files.readString(passwordFile).strip();
        assertEquals(32, generated.length());
        assertTrue(com.baseai.platform.security.PasswordPolicy.hasRequiredCharacterClasses(generated));
        assertNotEquals(SEED_PASSWORD, generated);
        verify(passwordEncoder).encode(generated);
    }

    /** 已有管理员且未开启同步时不应再要求种子密码。 */
    @Test
    void allowsMissingSeedPasswordForExistingAdmin() {
        properties.getSeed().setAdminPassword(null);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingAdmin("existing-hash")));

        initializer.run(null);

        verify(passwordEncoder, never()).encode(any());
    }

    /** 开启同步时必须拒绝不满足四类字符要求的密码。 */
    @Test
    void rejectsWeakPasswordWhenSynchronizationIsEnabled() {
        properties.getSeed().setAdminPassword("onlylowercasepassword");
        properties.getSeed().setAdminPasswordSyncEnabled(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingAdmin("existing-hash")));

        assertThrows(IllegalStateException.class, () -> initializer.run(null));
    }

    /** 所有内置按钮必须直接归属页面，避免产生无法配置的孤立按钮权限。 */
    @Test
    void assignsEverySeededButtonToPageMenu() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingAdmin("existing-hash")));

        initializer.run(null);

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuRepository, atLeastOnce()).save(captor.capture());
        Map<Long, Menu> menusById = captor.getAllValues().stream()
            .collect(Collectors.toMap(Menu::getId, Function.identity(), (first, ignored) -> first));
        List<Menu> buttons = menusById.values().stream().filter(item -> "BUTTON".equals(item.getType())).toList();

        assertFalse(buttons.isEmpty());
        assertTrue(buttons.stream().allMatch(item -> {
            Menu parent = menusById.get(item.getParentId());
            return parent != null && "MENU".equals(parent.getType());
        }));
    }

    /** 初始化菜单不得继续写入旧版统一管理权限。 */
    @Test
    void excludesLegacyManagementPermissions() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingAdmin("existing-hash")));

        initializer.run(null);

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuRepository, atLeastOnce()).save(captor.capture());
        Set<String> permissions = captor.getAllValues().stream().map(Menu::getPermission).collect(Collectors.toSet());
        assertFalse(permissions.contains("system:user:manage"));
        assertFalse(permissions.contains("system:role:manage"));
        assertFalse(permissions.contains("system:menu:manage"));
    }

    /** 邮件管理必须位于系统与模型目录之间，并保持账户、路由的独立权限层级。 */
    @Test
    void seedsMailManagementBeforeModelManagement() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingAdmin("existing-hash")));

        initializer.run(null);

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuRepository, atLeastOnce()).save(captor.capture());
        Map<String, Menu> menusByPermission = captor.getAllValues().stream()
            .collect(Collectors.toMap(Menu::getPermission, Function.identity(), (first, ignored) -> first));
        Menu system = menusByPermission.get("system:catalog");
        Menu mail = menusByPermission.get("mail:catalog");
        Menu model = menusByPermission.get("model:catalog");

        assertTrue(system.getSortOrder() < mail.getSortOrder());
        assertTrue(mail.getSortOrder() < model.getSortOrder());
        assertEquals(mail.getId(), menusByPermission.get("mail:account:list").getParentId());
        assertEquals(mail.getId(), menusByPermission.get("mail:route:list").getParentId());
        assertEquals(menusByPermission.get("mail:account:list").getId(),
            menusByPermission.get("mail:account:update").getParentId());
        assertEquals(menusByPermission.get("mail:route:list").getId(),
            menusByPermission.get("mail:route:update").getParentId());
    }

    /** 工作流必须作为独立一级目录，并包含节点、连接和画布管理页面。 */
    @Test
    void seedsWorkflowCatalogWithNodeAndCanvasPages() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingAdmin("existing-hash")));

        initializer.run(null);

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuRepository, atLeastOnce()).save(captor.capture());
        Map<String, Menu> menusByPermission = captor.getAllValues().stream()
            .collect(Collectors.toMap(Menu::getPermission, Function.identity(), (first, ignored) -> first));
        Menu workflow = menusByPermission.get("workflow:catalog");

        assertEquals("CATALOG", workflow.getType());
        assertEquals(workflow.getId(), menusByPermission.get("workflow:node:list").getParentId());
        assertEquals(workflow.getId(), menusByPermission.get("workflow:connection:list").getParentId());
        assertEquals(workflow.getId(), menusByPermission.get("workflow:canvas:list").getParentId());
        assertEquals(menusByPermission.get("workflow:node:list").getId(),
            menusByPermission.get("workflow:node:update").getParentId());
        assertEquals(menusByPermission.get("workflow:node:list").getId(),
            menusByPermission.get("workflow:adapter:manage").getParentId());
        assertEquals(menusByPermission.get("workflow:node:list").getId(),
            menusByPermission.get("workflow:plugin:admission").getParentId());
        assertEquals(menusByPermission.get("workflow:connection:list").getId(),
            menusByPermission.get("workflow:connection:update").getParentId());
        assertEquals(menusByPermission.get("workflow:canvas:list").getId(),
            menusByPermission.get("workflow:canvas:execute").getParentId());
    }

    /** 首次启动必须创建两个默认关闭且不可由通用参数页面修改的适配器开关。 */
    @Test
    void seedsDisabledSystemManagedAdapterSettings() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existingAdmin("existing-hash")));

        initializer.run(null);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(systemSettingRepository, atLeastOnce()).save(captor.capture());
        Map<String, SystemSetting> settings = captor.getAllValues().stream()
            .collect(Collectors.toMap(SystemSetting::getConfigKey, Function.identity()));
        assertEquals("false", settings.get(WorkflowAdapterLifecycleService.N8N_SETTING_KEY).getConfigValue());
        assertEquals("false", settings.get(WorkflowAdapterLifecycleService.DIFY_SETTING_KEY).getConfigValue());
        assertTrue(settings.values().stream().allMatch(SystemSetting::getSystemManaged));
    }

    /** 创建满足启动安全校验的测试配置。 */
    private static PlatformProperties validProperties() {
        PlatformProperties configured = new PlatformProperties();
        configured.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        configured.getToken().setSecret("test-token-secret-with-at-least-32-characters");
        configured.getApiKey().setHashSecret("test-api-key-secret-with-at-least-32-characters");
        configured.getPythonWorker().setInternalToken("test-internal-token-with-24-characters");
        configured.getSeed().setAdminPassword(SEED_PASSWORD);
        return configured;
    }

    /** 创建具备最小必需字段的已有管理员。 */
    private static UserAccount existingAdmin(String passwordHash) {
        UserAccount admin = new UserAccount();
        admin.setUsername("admin");
        admin.setDisplayName("系统管理员");
        admin.setPasswordHash(passwordHash);
        return admin;
    }
}
