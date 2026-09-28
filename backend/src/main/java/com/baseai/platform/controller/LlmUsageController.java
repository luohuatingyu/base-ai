package com.baseai.platform.controller;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.LlmUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** 提供管理员使用的模型调用汇总和明细查询接口。 */
@RestController
@RequestMapping("/api/operations/llm-usage")
@RequiredPermission("operations:llm-usage:view")
public class LlmUsageController {
    private final LlmUsageService service;

    /** 注入模型使用查询服务。 */
    public LlmUsageController(LlmUsageService service) { this.service = service; }

    /** 返回时间窗口内的总体、趋势、模型排行和错误排行。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String providerCode,
                                       @RequestParam(required = false) String modelName,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) String status) {
        AuthContext.requireAdmin();
        return service.summary(query(from, to, providerCode, modelName, null, source, status));
    }

    /** 返回管理员可见的模型使用明细，统一限制分页大小。 */
    @GetMapping("/records")
    public LlmUsageService.Page records(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String providerCode,
                                        @RequestParam(required = false) String modelName,
                                        @RequestParam(required = false) String source,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        AuthContext.requireAdmin();
        return service.records(query(from, to, providerCode, modelName, null, source, status), page, pageSize);
    }

    /** 将前端时间参数转换为受限查询对象。 */
    private LlmUsageService.Query query(String from, String to, String providerCode, String modelName,
                                        String modelType, String source, String status) {
        LlmUsageService.Query defaults = LlmUsageService.Query.last24Hours();
        try {
            Instant start = from == null || from.isBlank() ? defaults.from() : Instant.parse(from);
            Instant end = to == null || to.isBlank() ? defaults.to() : Instant.parse(to);
            return new LlmUsageService.Query(start, end, providerCode, modelName, modelType, source, status).validated();
        } catch (DateTimeParseException exception) {
            throw new BusinessException("error.badRequest");
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("error.badRequest");
        }
    }
}
