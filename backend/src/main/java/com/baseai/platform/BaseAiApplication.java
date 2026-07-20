package com.baseai.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class BaseAiApplication {

    /** 启动 AI 平台后端。 */
    public static void main(String[] args) {
        SpringApplication.run(BaseAiApplication.class, args);
    }
}
