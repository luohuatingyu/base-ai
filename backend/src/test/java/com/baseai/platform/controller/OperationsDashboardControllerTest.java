package com.baseai.platform.controller;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OperationsDashboardControllerTest {
    /** 同时存在业务与审计数据库时，首页必须成功创建且只查询业务数据库。 */
    @Test
    void selectsBusinessDatabaseWhenAuditDatabaseAlsoExists() {
        JdbcTemplate mysql = mock(JdbcTemplate.class);
        JdbcTemplate audit = mock(JdbcTemplate.class);
        when(mysql.queryForObject(anyString(), eq(Long.class))).thenReturn(8L, 5L, 2L, 1L, 2L);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("mysqlJdbcTemplate", JdbcTemplate.class, () -> mysql);
            context.registerBean("auditJdbcTemplate", JdbcTemplate.class, () -> audit);
            context.register(OperationsDashboardController.class);
            context.refresh();
            clearInvocations(audit);
            assertEquals(Map.of("tasks24h", 8L, "success24h", 5L, "failed24h", 2L,
                "running", 1L, "alerts", 2L), context.getBean(OperationsDashboardController.class).dashboard());
            verifyNoInteractions(audit);
        }
    }
}
