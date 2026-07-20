package com.baseai.platform.repository;

import com.baseai.platform.domain.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    @EntityGraph(attributePaths = "menus")
    Optional<Role> findByCode(String code);
    @Override @EntityGraph(attributePaths = "menus")
    List<Role> findAll();
}
