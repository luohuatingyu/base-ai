package com.baseai.platform.repository;
import com.baseai.platform.domain.DictionaryType;import org.springframework.data.jpa.repository.JpaRepository;import java.util.Optional;
public interface DictionaryTypeRepository extends JpaRepository<DictionaryType,Long>{Optional<DictionaryType> findByCode(String code);}
