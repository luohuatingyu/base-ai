package com.baseai.platform.repository;
import com.baseai.platform.domain.LlmModel;import org.springframework.data.jpa.repository.JpaRepository;import java.util.Optional;
public interface LlmModelRepository extends JpaRepository<LlmModel,Long>{Optional<LlmModel> findByCode(String code);}
