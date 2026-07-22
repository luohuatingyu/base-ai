package com.baseai.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 密码编码器等安全基础组件配置。 */
@Configuration
public class SecurityBeans {

    /** 提供统一 BCrypt 密码编码器。 */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
