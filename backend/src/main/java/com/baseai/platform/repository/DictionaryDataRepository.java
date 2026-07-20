package com.baseai.platform.repository;
import com.baseai.platform.domain.DictionaryData;import org.springframework.data.jpa.repository.JpaRepository;import java.util.List;
public interface DictionaryDataRepository extends JpaRepository<DictionaryData,Long>{List<DictionaryData> findByTypeCodeOrderBySortOrderAscIdAsc(String typeCode);}
