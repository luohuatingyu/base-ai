package com.baseai.platform.datasync;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 覆盖同步计划的非法参数、危险操作确认和标识符边界。 */
class DataSyncServiceValidationTest {
    private final DataSyncService service = new DataSyncService(Mockito.mock(JdbcTemplate.class),
        Mockito.mock(WorkflowConnectionService.class), new ObjectMapper(), Mockito.mock(StringRedisTemplate.class),
        Mockito.mock(ThreadPoolTaskExecutor.class), Mockito.mock(TaskTraceService.class));

    /** 空计划必须在访问数据库前被拒绝。 */
    @Test
    void rejectsEmptyPlan() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.create(null));
        assertEquals("dataSync.planInvalid", exception.getMessageKey());
    }

    /** 全量替换必须显式确认，防止误清空目标表。 */
    @Test
    void requiresDestructiveConfirmation() {
        DataSyncModels.PlanCommand command = command("FULL_REPLACE", false, "orders");
        BusinessException exception = assertThrows(BusinessException.class, () -> service.create(command));
        assertEquals("dataSync.destructiveConfirmationRequired", exception.getMessageKey());
    }

    /** 表名注入和重复映射必须被拒绝。 */
    @Test
    void rejectsUnsafeOrDuplicateTables() {
        DataSyncModels.PlanCommand unsafe = command("UPSERT", false, "orders;DROP");
        assertEquals("dataSync.tableNameInvalid", assertThrows(BusinessException.class, () -> service.create(unsafe)).getMessageKey());
        DataSyncModels.TableMapping table = new DataSyncModels.TableMapping("", "orders", "", "orders", List.of());
        DataSyncModels.PlanCommand duplicate = new DataSyncModels.PlanCommand("copy", 1L, 2L, "UPSERT", "", true, false, List.of(table, table));
        assertEquals("dataSync.duplicateTable", assertThrows(BusinessException.class, () -> service.create(duplicate)).getMessageKey());
    }

    /** 重复字段、非法字段和非法 Cron 必须在访问连接前被拒绝。 */
    @Test
    void rejectsInvalidColumnsAndCron() {
        DataSyncModels.TableMapping duplicateColumns = new DataSyncModels.TableMapping("", "orders", "", "orders", List.of("id", "ID"));
        DataSyncModels.PlanCommand duplicate = new DataSyncModels.PlanCommand("copy", 1L, 2L, "UPSERT", "", true, false, List.of(duplicateColumns));
        assertEquals("dataSync.columnsInvalid",
            assertThrows(BusinessException.class, () -> service.create(duplicate)).getMessageKey());
        DataSyncModels.PlanCommand cron = new DataSyncModels.PlanCommand("copy", 1L, 2L, "UPSERT", "invalid", true, false,
            List.of(new DataSyncModels.TableMapping("", "orders", "", "orders", List.of())));
        assertEquals("dataSync.scheduleInvalid",
            assertThrows(BusinessException.class, () -> service.create(cron)).getMessageKey());
    }

    /** 构造仅用于参数验证的计划命令。 */
    private DataSyncModels.PlanCommand command(String strategy, boolean confirmed, String table) {
        return new DataSyncModels.PlanCommand("copy", 1L, 2L, strategy, "", true, confirmed,
            List.of(new DataSyncModels.TableMapping("", table, "", table, List.of())));
    }
}
