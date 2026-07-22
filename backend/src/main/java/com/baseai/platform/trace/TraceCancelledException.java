package com.baseai.platform.trace;

import com.baseai.platform.common.BusinessException;

public class TraceCancelledException extends BusinessException {
    public TraceCancelledException(String traceId) {
        super(409, "任务已取消：" + traceId);
    }
}
