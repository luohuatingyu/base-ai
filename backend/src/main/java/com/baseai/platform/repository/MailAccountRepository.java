package com.baseai.platform.repository;

import com.baseai.platform.domain.MailAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** SMTP 邮箱账户数据访问接口。 */
public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {
    Optional<MailAccount> findByCode(String code);
}
