package com.baseai.platform.deviceagent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证通用设备 Agent 包含 IDA 自动化但不引入企业微信业务范围。 */
class DeviceAgentScopeContractTest {
    /** 命令和功能白名单包含通用自动化，但不得包含账号、好友或业务任务。 */
    @Test
    void protocolExcludesAutomationAndBusinessCommands() {
        String values = (DeviceAgentModels.VALID_FEATURES + " " + DeviceAgentModels.VALID_COMMAND_TYPES)
            .toUpperCase();

        assertTrue(values.contains("IDA"));
        assertTrue(values.contains("APPIUM"));
        assertFalse(values.contains("ACCOUNT"));
        assertFalse(values.contains("FRIEND"));
        assertFalse(values.contains("TASK_EXECUTION"));
        assertEquals(Set.of("DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING",
            "DETECT_DEVICE", "SETUP_IDA", "START_IDA", "REGISTRY_ONLINE", "REGISTRY_OFFLINE",
            "REGISTRY_RECREATE", "UPGRADE", "UPDATE_BACKEND_URL"),
            DeviceAgentModels.VALID_COMMAND_TYPES);
    }

    /** 面向用户的本地化消息必须使用 IDA 产品简称，同时保留底层协议键。 */
    @Test
    void userMessagesUseIdaProductName() throws Exception {
        String chinese = readResource("messages_zh_CN.properties");
        String english = readResource("messages_en_US.properties");

        assertTrue(chinese.contains("IDA"));
        assertTrue(english.contains("IDA"));
        assertFalse(chinese.matches("(?s).*=[^\\r\\n]*\\bWDA\\b.*"));
        assertFalse(english.matches("(?s).*=[^\\r\\n]*\\bWDA\\b.*"));
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

        try (var input = new ClassPathResource(
            "db/migration/mysql/V32__add_device_agent_operation_speed.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(migration.contains("operation_speed"));
        assertTrue(migration.contains("'slow', 'standard', 'fast'"));
        assertTrue(migration.contains("automation_device_agent_ida_config"));
        assertFalse(migration.contains("wecom"));
    }

    /** 读取 UTF-8 类路径资源供产品命名契约复用。 */
    private static String readResource(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
