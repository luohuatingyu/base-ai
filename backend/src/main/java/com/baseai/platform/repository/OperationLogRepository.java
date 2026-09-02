package com.baseai.platform.repository;

import com.baseai.platform.domain.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    /** 删除早于保留截止时间的操作审计记录。 */
    long deleteByOperatedAtBefore(Instant cutoff);
}
