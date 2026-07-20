package com.baseai.platform.repository;

import com.baseai.platform.domain.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {}
