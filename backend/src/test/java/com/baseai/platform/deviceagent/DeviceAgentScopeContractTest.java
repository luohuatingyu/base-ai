package com.baseai.platform.deviceagent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证通用设备 Agent 不重新引入企业微信或设备自动化控制范围。 */
class DeviceAgentScopeContractTest {
    /** 命令和功能白名单不得包含 WDA、Appium、账号、好友或任务执行。 */
    @Test
    void protocolExcludesAutomationAndBusinessCommands() {
        String values = (DeviceAgentModels.VALID_FEATURES + " " + DeviceAgentModels.VALID_COMMAND_TYPES)
            .toUpperCase();

        assertFalse(values.contains("WDA"));
        assertFalse(values.contains("APPIUM"));
        assertFalse(values.contains("ACCOUNT"));
        assertFalse(values.contains("FRIEND"));
        assertFalse(values.contains("TASK_EXECUTION"));
        assertEquals(Set.of("DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING",
            "DETECT_DEVICE", "UPGRADE", "UPDATE_BACKEND_URL"), DeviceAgentModels.VALID_COMMAND_TYPES);
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
    }
}
