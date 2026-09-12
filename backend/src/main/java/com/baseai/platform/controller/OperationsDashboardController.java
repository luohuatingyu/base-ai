package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 提供运营首页使用的任务统计和关键依赖状态。 */
@RestController
@RequestMapping("/api/operations/dashboard")
public class OperationsDashboardController {
    private final JdbcTemplate mysql;

    /** 注入系统数据库查询模板。 */
    public OperationsDashboardController(JdbcTemplate mysql) { this.mysql = mysql; }

    /** 返回最近 24 小时任务汇总，查询失败时由统一异常处理器处理。 */
    @GetMapping
    @RequiredPermission("operations:dashboard:view")
    public Map<String, Object> dashboard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks24h", scalar("SELECT COUNT(*) FROM task_trace WHERE created_at >= NOW() - INTERVAL 24 HOUR"));
        result.put("success24h", scalar("SELECT COUNT(*) FROM task_trace WHERE created_at >= NOW() - INTERVAL 24 HOUR AND status='SUCCESS'"));
        result.put("failed24h", scalar("SELECT COUNT(*) FROM task_trace WHERE created_at >= NOW() - INTERVAL 24 HOUR AND status='FAILED'"));
        result.put("running", scalar("SELECT COUNT(*) FROM task_trace WHERE status IN ('RUNNING','WAITING','CANCEL_REQUESTED')"));
        result.put("alerts", scalar("SELECT COUNT(*) FROM task_trace WHERE status='FAILED' AND finished_at >= NOW() - INTERVAL 1 HOUR"));
        return result;
    }

    /** 将聚合查询限制为数值结果，避免把数据库异常细节暴露给前端。 */
    private long scalar(String sql) { Long value = mysql.queryForObject(sql, Long.class); return value == null ? 0L : value; }
}
