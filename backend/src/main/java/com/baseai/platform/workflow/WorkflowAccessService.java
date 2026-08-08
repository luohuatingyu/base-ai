package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 统一执行工作流、模板和运行记录的资源级访问控制。 */
@Service
public class WorkflowAccessService {
    private final JdbcTemplate jdbcTemplate;

    /** 注入 MySQL 以校验 API Key 的工作流白名单。 */
    public WorkflowAccessService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 当前交互用户只有资源所有者或管理员可以查看和维护工作流。 */
    public void requireOwnerOrAdmin(Long ownerUserId) {
        AuthUser user = AuthContext.require();
        if (user.authenticationType() == AuthenticationType.API_KEY || !isAdmin(user) && !user.id().equals(ownerUserId)) {
            throw BusinessException.forbidden("workflow.forbidden");
        }
    }

    /** 系统模板仅管理员可维护，自定义模板仅创建者或管理员可维护。 */
    public void requireTemplateOwnerOrAdmin(Long createdBy, boolean systemTemplate) {
        AuthUser user = AuthContext.require();
        if (user.authenticationType() == AuthenticationType.API_KEY || systemTemplate && !isAdmin(user)
            || !systemTemplate && !isAdmin(user) && !user.id().equals(createdBy)) {
            throw BusinessException.forbidden("workflow.templateForbidden");
        }
    }

    /** 校验交互用户或 API Key 是否可以执行指定工作流。 */
    public void requireExecute(Long workflowId, Long ownerUserId) {
        AuthUser user = AuthContext.require();
        if (user.authenticationType() != AuthenticationType.API_KEY) {
            if (isAdmin(user) || user.id().equals(ownerUserId)) return;
            throw BusinessException.forbidden("workflow.executeForbidden");
        }
        if (!user.id().equals(ownerUserId) || user.credentialId() == null || !apiKeyAllows(user.credentialId(), workflowId)) {
            throw BusinessException.forbidden("workflow.apiKeyForbidden");
        }
    }

    /** 运行结果对交互用户按所有者隔离，对 API Key 还绑定到创建该运行的同一个 Key。 */
    public void requireRunAccess(Long workflowId, Long ownerUserId, Long runApiKeyId) {
        AuthUser user = AuthContext.require();
        if (user.authenticationType() != AuthenticationType.API_KEY) {
            if (isAdmin(user) || user.id().equals(ownerUserId)) return;
            throw BusinessException.forbidden("workflow.runForbidden");
        }
        if (!user.id().equals(ownerUserId) || user.credentialId() == null || !user.credentialId().equals(runApiKeyId)
            || !apiKeyAllows(user.credentialId(), workflowId)) {
            throw BusinessException.forbidden("workflow.runForbidden");
        }
    }

    /** 判断 API Key 白名单是否包含指定未作废工作流。 */
    private boolean apiKeyAllows(Long apiKeyId, Long workflowId) {
        Integer matches = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM sys_api_key_workflow aw
            JOIN workflow_definition d ON d.id=aw.workflow_id
            WHERE aw.api_key_id=? AND aw.workflow_id=? AND d.voided=false
            """, Integer.class, apiKeyId, workflowId);
        return matches != null && matches == 1;
    }

    /** API Key 即使绑定管理员也不得绕过资源白名单。 */
    private boolean isAdmin(AuthUser user) {
        return user.authenticationType() != AuthenticationType.API_KEY && user.roles().contains("ADMIN");
    }
}
