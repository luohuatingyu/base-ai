package com.baseai.platform.automation;

import com.baseai.platform.trace.TraceType;
import org.springframework.stereotype.Service;

@Service
public class ApiTriggerTrackedExecutionService {
    private final ApiTriggerService service;

    public ApiTriggerTrackedExecutionService(ApiTriggerService service) { this.service = service; }

    /** Redis 锁获取成功后，通过 AOP 建立唯一 Cron 系统任务。 */
    @TraceType(value = "接口定时触发", triggerEntry = "CRON", ownerIdParameter = "ownerUserId", captureRequest = false)
    public void execute(Long configId, Long ownerUserId) {
        service.execute(configId, "CRON");
    }
}
