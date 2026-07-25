package com.baseai.platform.repository;

import com.baseai.platform.domain.ApiKeyCredential;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyCredentialRepository extends JpaRepository<ApiKeyCredential, Long> {
    @EntityGraph(attributePaths = {"owner", "owner.roles", "owner.roles.menus", "endpointCodes", "allowedCidrs"})
    Optional<ApiKeyCredential> findByKeyIdAndRevokedAtIsNull(String keyId);

    @EntityGraph(attributePaths = {"owner", "endpointCodes", "allowedCidrs"})
    Optional<ApiKeyCredential> findByIdAndRevokedAtIsNull(Long id);

    @EntityGraph(attributePaths = {"owner", "endpointCodes", "allowedCidrs"})
    Page<ApiKeyCredential> findByRevokedAtIsNull(Pageable pageable);
}
