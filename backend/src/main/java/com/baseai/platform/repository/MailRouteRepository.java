package com.baseai.platform.repository;

import com.baseai.platform.domain.MailRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 邮件业务路由数据访问接口。 */
public interface MailRouteRepository extends JpaRepository<MailRoute, Long> {
    Optional<MailRoute> findByBusinessCode(String businessCode);
    List<MailRoute> findByAccountId(Long accountId);
}
