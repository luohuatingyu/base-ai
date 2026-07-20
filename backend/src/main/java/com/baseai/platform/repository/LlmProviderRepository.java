package com.baseai.platform.repository;
import com.baseai.platform.domain.LlmProvider;import org.springframework.data.jpa.repository.JpaRepository;import java.util.Optional;
public interface LlmProviderRepository extends JpaRepository<LlmProvider,Long>{Optional<LlmProvider> findByCode(String code);}
