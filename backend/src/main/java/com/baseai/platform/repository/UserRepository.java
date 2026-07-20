package com.baseai.platform.repository;

import com.baseai.platform.domain.UserAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    boolean existsByUsername(String username);
    @EntityGraph(attributePaths = {"roles", "roles.menus"})
    Optional<UserAccount> findByUsername(String username);
    @Override
    @EntityGraph(attributePaths = {"roles", "roles.menus"})
    Optional<UserAccount> findById(Long id);
    @Override @EntityGraph(attributePaths = "roles")
    List<UserAccount> findAll();
}
