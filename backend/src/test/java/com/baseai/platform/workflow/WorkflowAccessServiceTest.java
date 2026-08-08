package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowAccessServiceTest {
    private JdbcTemplate jdbcTemplate;
    private WorkflowAccessService accessService;

    /** 创建 API Key 工作流白名单和定义表。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-access-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE workflow_definition(id BIGINT PRIMARY KEY,voided BOOLEAN)");
        jdbcTemplate.execute("CREATE TABLE sys_api_key_workflow(api_key_id BIGINT,workflow_id BIGINT)");
        jdbcTemplate.update("INSERT INTO workflow_definition VALUES (10,false),(11,false)");
        jdbcTemplate.update("INSERT INTO sys_api_key_workflow VALUES (90,10)");
        accessService = new WorkflowAccessService(jdbcTemplate);
    }

    /** 清理线程认证上下文。 */
    @AfterEach
    void tearDown() { AuthContext.clear(); }

    /** 所有者和管理员可管理，其他交互用户必须被拒绝。 */
    @Test
    void enforcesOwnerOrAdminForInteractiveUsers() {
        authenticate(7L, AuthenticationType.TOKEN, null, Set.of("USER"));
        assertDoesNotThrow(() -> accessService.requireOwnerOrAdmin(7L));
        assertThrows(BusinessException.class, () -> accessService.requireOwnerOrAdmin(8L));
        authenticate(1L, AuthenticationType.TOKEN, null, Set.of("ADMIN"));
        assertDoesNotThrow(() -> accessService.requireOwnerOrAdmin(8L));
    }

    /** API Key 即使绑定管理员角色也必须同时满足所有者和资源白名单。 */
    @Test
    void apiKeyRequiresOwnerAndExplicitWorkflowAllowlist() {
        authenticate(7L, AuthenticationType.API_KEY, 90L, Set.of("ADMIN"));
        assertDoesNotThrow(() -> accessService.requireExecute(10L, 7L));
        assertThrows(BusinessException.class, () -> accessService.requireExecute(11L, 7L));
        assertThrows(BusinessException.class, () -> accessService.requireExecute(10L, 8L));
    }

    /** API Key 只能读取由同一个 Key 创建且仍在白名单内的运行。 */
    @Test
    void apiKeyRunReadIsBoundToOriginatingCredential() {
        authenticate(7L, AuthenticationType.API_KEY, 90L, Set.of("USER"));
        assertDoesNotThrow(() -> accessService.requireRunAccess(10L, 7L, 90L));
        assertThrows(BusinessException.class, () -> accessService.requireRunAccess(10L, 7L, 91L));
        assertThrows(BusinessException.class, () -> accessService.requireRunAccess(11L, 7L, 90L));
    }

    /** 建立指定类型的认证身份。 */
    private void authenticate(Long userId, AuthenticationType type, Long credentialId, Set<String> roles) {
        AuthContext.set(new AuthUser(userId, "user" + userId, roles, Set.of(), type, credentialId, "key"));
    }
}
