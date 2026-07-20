package com.baseai.platform.job;

import com.baseai.platform.common.BusinessException;

public class JobCancelledException extends BusinessException {
    public JobCancelledException(String jobId) {
        super(409, "任务已取消：" + jobId);
    }
}
