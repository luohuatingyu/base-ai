package com.baseai.platform.repository;
import com.baseai.platform.domain.SystemSetting;import org.springframework.data.jpa.repository.JpaRepository;import java.util.Optional;
public interface SystemSettingRepository extends JpaRepository<SystemSetting,Long>{Optional<SystemSetting> findByConfigKey(String key);}
