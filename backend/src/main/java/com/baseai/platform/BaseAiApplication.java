package com.baseai.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.Security;

@EnableScheduling
@SpringBootApplication
public class BaseAiApplication {

    /** 启动 AI 平台后端。 */
    public static void main(String[] args) {
        enforceProcessDnsPinning();
        SpringApplication.run(BaseAiApplication.class, args);
    }

    /** 在任何网络客户端初始化前固定成功解析结果，阻断校验与连接之间的 DNS 变更。 */
    static void enforceProcessDnsPinning() {
        Security.setProperty("networkaddress.cache.ttl", "-1");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }
}
