package com.baseai.platform.automation;

import com.baseai.platform.trace.TraceType;
import org.springframework.stereotype.Service;

@Service
public class ApiTriggerTrackedExecutionService {
    private final ApiTriggerService service;

    public ApiTriggerTrackedExecutionService(ApiTriggerService service) { this.service = service; }

    /** Redis 锁获取成功后，通过 AOP 建立唯一 Cron 系统任务。 */
    @TraceType(value = "API_TRIGGER_CRON", triggerEntry = "CRON", ownerIdParameter = "ownerUserId", captureRequest = false)
    public void execute(Long configId, Long ownerUserId) {
        // Cron 调度器已经从可信的数据库配置生成任务，不走外部 HTTP 调用者的所有权校验。
        service.execute(configId, "CRON");
    }
}
