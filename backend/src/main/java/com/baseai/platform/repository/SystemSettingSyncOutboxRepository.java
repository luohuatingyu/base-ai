package com.baseai.platform.repository;

import com.baseai.platform.domain.SystemSettingSyncOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 系统配置缓存同步任务仓储。 */
public interface SystemSettingSyncOutboxRepository extends JpaRepository<SystemSettingSyncOutbox, Long> {
    List<SystemSettingSyncOutbox> findTop100ByProcessedAtIsNullOrderByCreatedAtAsc();
}
