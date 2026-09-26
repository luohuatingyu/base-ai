package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import com.baseai.platform.security.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 使用真实 JPA Repository 和 H2 验证筛选、权限、分页及 SQL 执行边界。 */
class DatabaseListPaginationTest {
    private static EntityManagerFactory factory;
    private static final List<String> SQL = new ArrayList<>();
    private EntityManager entityManager;
    private PlatformAdminService users;
    private ApiKeyManagementService keys;
    private UserAccount owner;

    /** 独立启动持久层，禁止集合抓取分页退化为内存分页。 */
    @BeforeAll
    static void startDatabase() {
        JdbcDataSource source = new JdbcDataSource();
        // MySQL 的无长度 BINARY 转换保留全部字节；H2 需显式启用相同语义。
        source.setURL("jdbc:h2:mem:list-pagination;MODE=MySQL;VARIABLE_BINARY=TRUE;DB_CLOSE_DELAY=-1");
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(source);
        bean.setPackagesToScan("com.baseai.platform.domain");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setJpaPropertyMap(Map.of(
            "hibernate.hbm2ddl.auto", "create-drop",
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.query.fail_on_pagination_over_collection_fetch", "true",
            "hibernate.generate_statistics", "true",
            "hibernate.session.events.log", "false",
            "hibernate.session_factory.statement_inspector", (StatementInspector) sql -> { SQL.add(sql); return sql; }
        ));
        bean.afterPropertiesSet();
        factory = bean.getObject();
    }

    /** 销毁测试数据库及持久层资源。 */
    @AfterAll
    static void stopDatabase() {
        if (factory != null) factory.close();
    }

    /** 使用真实 Repository 和权限解析器，外部会话、加密等依赖仅作隔离。 */
    @BeforeEach
    void setUp() {
        entityManager = factory.createEntityManager();
        entityManager.getTransaction().begin();
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
        UserRepository userRepository = repositories.getRepository(UserRepository.class);
        DepartmentRepository departments = repositories.getRepository(DepartmentRepository.class);
        PlatformProperties properties = new PlatformProperties();
        properties.getApiKey().setHashSecret("0123456789abcdef0123456789abcdef");
        users = new PlatformAdminService(userRepository, repositories.getRepository(RoleRepository.class),
            repositories.getRepository(MenuRepository.class), departments,
            repositories.getRepository(PositionRepository.class), mock(BCryptPasswordEncoder.class),
            mock(SessionService.class), properties, new DataScopeResolver(userRepository, departments));
        keys = new ApiKeyManagementService(repositories.getRepository(ApiKeyCredentialRepository.class),
            userRepository, new ApiKeySecretService(properties), mock(ConfigCryptoService.class),
            mock(ApiKeyEndpointCatalogService.class), new ApiKeyCidrMatcher(), mock(JdbcTemplate.class));
        owner = user("owner", "Owner", true, null);
        authenticate(owner, Set.of("ADMIN"));
    }

    /** 每例回滚数据并清理线程上下文。 */
    @AfterEach
    void tearDown() {
        entityManager.getTransaction().rollback();
        entityManager.close();
        DataScopeContext.clear();
        AuthContext.clear();
    }

    /** 用户总数、排序、筛选与关联字段均应正确，且只加载当前页实体。 */
    @Test
    void pagesUsersInDatabaseWithCompleteAssociations() {
        Role firstRole = role("FIRST", "SELF");
        Role secondRole = role("SECOND", "SELF");
        Position firstPosition = position("FIRST");
        Position secondPosition = position("SECOND");
        Department department = department("engineering", null);
        user("match-first", "First", true, department);
        UserAccount second = user("match-second", "Second", true, department);
        second.getRoles().addAll(Set.of(firstRole, secondRole));
        second.getPositions().addAll(Set.of(firstPosition, secondPosition));
        user("match-third", "Third", true, department);
        user("match-disabled", "Disabled", false, department);
        resetQueries();

        var result = users.users(" MATCH ", true, 2, 1);

        assertEquals(3, result.total());
        assertEquals(List.of(second.getId()), result.items().stream().map(PlatformAdminService.UserView::id).toList());
        assertEquals(department.getId(), result.items().get(0).departmentId());
        assertEquals(List.of(firstRole.getId(), secondRole.getId()), result.items().get(0).roleIds());
        assertEquals(List.of(firstPosition.getId(), secondPosition.getId()), result.items().get(0).positionIds());
        assertDatabasePage("sys_user");
        assertEquals(1, loaded(UserAccount.class));
    }

    /** API Key 按 ID 倒序分页，排除吊销记录且保留完整白名单。 */
    @Test
    void pagesKeysInDatabaseWithoutRevokedRecordsOrSecrets() {
        key("match-first", "first", true);
        ApiKeyCredential second = key("match-second", "second", true);
        second.getEndpointCodes().addAll(Set.of("chat", "workflow"));
        second.getAllowedCidrs().addAll(Set.of("127.0.0.1/32", "10.0.0.0/8"));
        second.getWorkflowIds().addAll(Set.of(3L, 7L));
        key("match-third", "third", true);
        key("match-revoked", "revoked", true).setRevokedAt(Instant.now());
        key("match-disabled", "disabled", false);
        resetQueries();

        var result = keys.list(" MATCH ", true, 2, 1);

        assertEquals(3, result.total());
        assertEquals(List.of(second.getId()), result.items().stream().map(ApiKeyManagementService.ApiKeyView::id).toList());
        var view = result.items().get(0);
        assertEquals(Set.of("chat", "workflow"), view.endpointCodes());
        assertEquals(Set.of("127.0.0.1/32", "10.0.0.0/8"), view.allowedCidrs());
        assertEquals(Set.of(3L, 7L), view.workflowIds());
        assertEquals(owner.getUsername(), view.ownerUsername());
        assertFalse(view.toString().contains("test-secret"));
        assertDatabasePage("sys_api_key");
        assertEquals(1, loaded(ApiKeyCredential.class));
    }

    /** 超大页码和整数溢出临界值必须返回空页及正确总数，不加载实体。 */
    @ParameterizedTest
    @CsvSource({"2147483647,200", "2147483647,100", "10737419,200", "21474838,100", "42949674,100", "3,1"})
    void returnsEmptyPagesWithoutOverflow(int page, int size) {
        key("sample", "sample", true);
        resetQueries();
        var userPage = users.users(null, null, page, size);
        var keyPage = keys.list(null, null, page, size);
        assertTrue(userPage.items().isEmpty());
        assertTrue(keyPage.items().isEmpty());
        assertEquals(1, userPage.total());
        assertEquals(1, keyPage.total());
        assertEquals(page, userPage.page());
        assertEquals(page, keyPage.page());
        assertEquals(Math.min(200, size), userPage.size());
        assertEquals(Math.min(100, size), keyPage.size());
        assertEquals(0, loaded(UserAccount.class));
        assertEquals(0, loaded(ApiKeyCredential.class));
    }

    /** 页码与页大小归一化兼容旧接口的最小值和上限。 */
    @ParameterizedTest
    @CsvSource({"0,0,1", "-2147483648,-2147483648,1", "1,2147483647,200", "1,1,1"})
    void normalizesPagination(int page, int size, int expectedSize) {
        key("sample", "sample", true);
        resetQueries();
        var userPage = users.users(null, null, page, size);
        var keyPage = keys.list(null, null, page, size);
        assertEquals(1, userPage.items().size());
        assertEquals(1, keyPage.items().size());
        assertEquals(1, userPage.page());
        assertEquals(1, keyPage.page());
        assertEquals(expectedSize, userPage.size());
        assertEquals(Math.min(100, expectedSize), keyPage.size());
    }

    /** 空或纯空白关键词不筛选，布尔条件只返回禁用项。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\u2003"})
    void handlesBlankKeywordsAndDisabledFilter(String keyword) {
        UserAccount disabled = user("disabled", "Disabled", false, null);
        ApiKeyCredential disabledKey = key("Disabled", "disabled", false);
        key("Enabled", "enabled", true);
        resetQueries();
        assertEquals(2, users.users(keyword, null, 1, 20).total());
        assertEquals(2, keys.list(keyword, null, 1, 20).total());
        assertEquals(List.of(disabled.getId()), users.users(keyword, false, 1, 20).items().stream()
            .map(PlatformAdminService.UserView::id).toList());
        assertEquals(List.of(disabledKey.getId()), keys.list(keyword, false, 1, 20).items().stream()
            .map(ApiKeyManagementService.ApiKeyView::id).toList());
    }

    /** 数据库关键词匹配保留普通字符语义，不把输入解释为通配符或 SQL。 */
    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\", "' OR 1=1 --", "MiXeD", "中文"})
    void treatsKeywordAsLiteralSubstring(String keyword) {
        UserAccount expectedUser = user("search-user", "prefix" + keyword + "suffix", true, null);
        ApiKeyCredential expectedKey = key("prefix" + keyword + "suffix", "search-key", true);
        key("unrelated", "unrelated", true);
        resetQueries();
        var userPage = users.users(" " + keyword + " ", null, 1, 20);
        var keyPage = keys.list(" " + keyword + " ", null, 1, 20);
        assertEquals(1, userPage.total());
        assertEquals(expectedUser.getId(), userPage.items().get(0).id());
        assertEquals(1, keyPage.total());
        assertEquals(expectedKey.getId(), keyPage.items().get(0).id());
    }

    /** Key ID 维持旧接口的关键词小写化及大小写敏感匹配行为。 */
    @Test
    void matchesKeyIdWithExistingCaseSemantics() {
        ApiKeyCredential expected = key("first", "abc123", true);
        key("second", "ABC123", true);
        resetQueries();
        var result = keys.list(" ABC123 ", null, 1, 20);
        assertEquals(1, result.total());
        assertEquals(expected.getId(), result.items().get(0).id());
    }

    /** 无匹配和超长输入都应返回总数为零的空页。 */
    @ParameterizedTest
    @ValueSource(ints = {1, 10000})
    void returnsEmptyResultsForUnmatchedKeywords(int length) {
        resetQueries();
        assertEquals(0, users.users("z".repeat(length), null, 1, 20).total());
        assertTrue(users.users("z".repeat(length), null, 1, 20).items().isEmpty());
        assertEquals(0, keys.list("z".repeat(length), null, 1, 20).total());
        assertTrue(keys.list("z".repeat(length), null, 1, 20).items().isEmpty());
    }

    /** 真实权限解析覆盖本人、部门、子部门、自定义部门、并集以及停用角色。 */
    @ParameterizedTest
    @ValueSource(strings = {"SELF", "DEPARTMENT", "DEPARTMENT_AND_CHILDREN", "CUSTOM", "UNION", "ALL", "DISABLED", "NONE"})
    void appliesResolvedScopeToRowsAndCount(String scopeName) {
        Department own = department("own", null);
        Department child = department("child", own.getId());
        Department other = department("other", null);
        owner.setDepartment(own);
        UserAccount teammate = user("teammate", "Match", true, own);
        UserAccount descendant = user("child-user", "Match", true, child);
        UserAccount outsider = user("outsider", "Match", true, other);
        List<Long> expected = new ArrayList<>(List.of(owner.getId()));
        if (!scopeName.equals("NONE")) {
            Role role = role("EDITOR", scopeName.equals("UNION") ? "DEPARTMENT" : scopeName.equals("DISABLED") ? "ALL" : scopeName);
            role.setEnabled(!scopeName.equals("DISABLED"));
            role.getCustomDepartments().add(other);
            owner.getRoles().add(role);
        }
        if (Set.of("DEPARTMENT", "DEPARTMENT_AND_CHILDREN", "UNION", "ALL").contains(scopeName)) expected.add(teammate.getId());
        if (Set.of("DEPARTMENT_AND_CHILDREN", "ALL").contains(scopeName)) expected.add(descendant.getId());
        if (Set.of("CUSTOM", "UNION", "ALL").contains(scopeName)) expected.add(outsider.getId());
        if (scopeName.equals("UNION")) {
            Role custom = role("CUSTOM_EDITOR", "CUSTOM");
            custom.getCustomDepartments().add(other);
            owner.getRoles().add(custom);
        }
        authenticate(owner, Set.of("EDITOR"));
        resetQueries();
        var result = users.users(null, null, 1, 20);
        assertEquals(expected, result.items().stream().map(PlatformAdminService.UserView::id).toList());
        assertEquals(expected.size(), result.total());
        assertEquals(expected.size() - 1, users.users("Match", true, 1, 1).total());
        if (expected.size() > 1) {
            var next = users.users(null, null, 2, 1);
            assertEquals(expected.get(1), next.items().get(0).id());
            assertEquals(expected.size(), next.total());
        }
    }

    /** 上下文权限优先于管理员身份，空部门集合及无部门本人不会扩大范围。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void honorsContextScopeWithoutDepartments(boolean self) {
        user("outsider", "Outsider", true, null);
        DataScopeContext.set(new DataScopeContext.Scope(false, self, owner.getId(), Set.of()));
        resetQueries();
        var result = users.users(null, null, 1, 20);
        assertEquals(self ? List.of(owner.getId()) : List.of(), result.items().stream().map(PlatformAdminService.UserView::id).toList());
        assertEquals(self ? 1 : 0, result.total());
    }

    /** 缺少认证身份时应拒绝解析用户范围。 */
    @Test
    void rejectsUnauthenticatedUserListing() {
        AuthContext.clear();
        assertThrows(com.baseai.platform.common.BusinessException.class, () -> users.users(null, null, 1, 20));
    }

    /** 落库并清空一级缓存，保证查询断言不依赖测试预先加载的实体。 */
    private void resetQueries() {
        entityManager.flush();
        entityManager.clear();
        SQL.clear();
        factory.unwrap(SessionFactory.class).getStatistics().clear();
    }

    /** 验证主表查询实际发送数据库分页语句。 */
    private void assertDatabasePage(String table) {
        assertTrue(SQL.stream().map(sql -> sql.toLowerCase(Locale.ROOT)).anyMatch(sql -> sql.contains(table)
            && (sql.contains(" limit ") || sql.contains(" fetch first ") || sql.contains(" fetch next "))), SQL.toString());
    }

    /** 获取当前用例实际从数据库加载的实体数。 */
    private long loaded(Class<?> entity) {
        return factory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(entity.getName()).getLoadCount();
    }

    /** 构造认证上下文以运行真实权限解析。 */
    private void authenticate(UserAccount user, Set<String> roles) {
        AuthContext.set(new AuthUser(user.getId(), user.getUsername(), roles, Set.of("system:user:list"),
            AuthenticationType.TOKEN, null, null));
    }

    /** 创建最小有效用户数据。 */
    private UserAccount user(String username, String displayName, boolean enabled, Department department) {
        UserAccount value = new UserAccount();
        value.setUsername(username);
        value.setDisplayName(displayName);
        value.setPasswordHash("test-password-hash");
        value.setEnabled(enabled);
        value.setDepartment(department);
        entityManager.persist(value);
        return value;
    }

    /** 创建包含密钥敏感字段的测试凭据。 */
    private ApiKeyCredential key(String name, String keyId, boolean enabled) {
        ApiKeyCredential value = new ApiKeyCredential();
        value.setName(name);
        value.setKeyId(keyId);
        value.setOwner(owner);
        value.setEnabled(enabled);
        value.setSecretHash("test-secret-hash");
        value.setSecretEncrypted("test-secret-encrypted");
        entityManager.persist(value);
        return value;
    }

    /** 创建可设置层级关系的部门。 */
    private Department department(String code, Long parentId) {
        Department value = new Department();
        value.setCode(code);
        value.setName(code);
        value.setParentId(parentId);
        entityManager.persist(value);
        return value;
    }

    /** 创建指定数据权限的角色。 */
    private Role role(String code, String scope) {
        Role value = new Role();
        value.setCode(code);
        value.setName(code);
        value.setDataScope(scope);
        entityManager.persist(value);
        return value;
    }

    /** 创建用户关联岗位。 */
    private Position position(String code) {
        Position value = new Position();
        value.setCode(code);
        value.setName(code);
        entityManager.persist(value);
        return value;
    }
}
