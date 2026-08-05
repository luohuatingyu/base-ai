package com.baseai.platform.repository;

import com.baseai.platform.domain.UserAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    boolean existsByUsername(String username);
    @EntityGraph(attributePaths = {"roles", "roles.menus", "department", "positions"})
    Optional<UserAccount> findByUsername(String username);
    @Override
    @EntityGraph(attributePaths = {"roles", "roles.menus", "department", "positions"})
    Optional<UserAccount> findById(Long id);
    @Override @EntityGraph(attributePaths = {"roles", "department", "positions"})
    List<UserAccount> findAll();
    /** 串行化敏感账号变更，避免并发操作同时移除最后的管理员入口。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"roles"})
    @Query("select distinct account from UserAccount account")
    List<UserAccount> findAllForAdminGuard();
}
