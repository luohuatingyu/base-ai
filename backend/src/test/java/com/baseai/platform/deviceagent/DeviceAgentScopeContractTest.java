package com.baseai.platform.deviceagent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证通用设备 Agent 包含 WDA 自动化但不引入企业微信业务范围。 */
class DeviceAgentScopeContractTest {
    /** 命令和功能白名单包含通用自动化，但不得包含账号、好友或业务任务。 */
    @Test
    void protocolExcludesAutomationAndBusinessCommands() {
        String values = (DeviceAgentModels.VALID_FEATURES + " " + DeviceAgentModels.VALID_COMMAND_TYPES)
            .toUpperCase();

        assertTrue(values.contains("WDA"));
        assertTrue(values.contains("APPIUM"));
        assertFalse(values.contains("ACCOUNT"));
        assertFalse(values.contains("FRIEND"));
        assertFalse(values.contains("TASK_EXECUTION"));
        assertEquals(Set.of("DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING",
            "DETECT_DEVICE", "SETUP_WDA", "START_WDA", "REGISTRY_ONLINE", "REGISTRY_OFFLINE",
            "REGISTRY_RECREATE", "UPGRADE", "UPDATE_BACKEND_URL"),
            DeviceAgentModels.VALID_COMMAND_TYPES);
    }

    /** MySQL 迁移只创建 Agent、配对、状态、命令、设备和审计表。 */
    @Test
    void mysqlSchemaContainsOnlyGenericDeviceManagementTables() throws Exception {
        String migration;
        try (var input = new ClassPathResource(
            "db/migration/mysql/V27__add_device_agent_management.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(migration.contains("automation_device_agent_registration"));
        assertTrue(migration.contains("automation_device_agent_device"));
        assertFalse(migration.contains("wecom"));
        assertFalse(migration.contains("wda"));
        assertFalse(migration.contains("appium"));
        assertFalse(migration.contains("friend"));
        assertFalse(migration.contains("account_id"));

        try (var input = new ClassPathResource(
            "db/migration/mysql/V31__add_device_agent_automation.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(migration.contains("signing_config_encrypted"));
        assertTrue(migration.contains("automation_device_agent_registry"));
        assertFalse(migration.contains("raw_udid"));
        assertFalse(migration.contains("wecom"));
    }
}
