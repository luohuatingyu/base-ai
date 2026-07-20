package com.baseai.platform.repository;
import com.baseai.platform.domain.LlmRoute;import org.springframework.data.jpa.repository.JpaRepository;import java.util.Optional;
public interface LlmRouteRepository extends JpaRepository<LlmRoute,Long>{Optional<LlmRoute> findByFeatureCode(String code);}
