package com.baseai.platform.repository;

import com.baseai.platform.domain.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {
    Optional<Menu> findByPermission(String permission);
}
