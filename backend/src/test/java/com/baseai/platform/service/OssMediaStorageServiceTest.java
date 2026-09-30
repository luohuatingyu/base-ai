package com.baseai.platform.service;

import com.baseai.platform.config.OssProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 OSS 仅接受 public 前缀，避免把媒体写入未授权目录。 */
class OssMediaStorageServiceTest {
    /** 缺少凭据或前缀不在 public 下时禁用持久化。 */
    @Test
    void requiresCredentialsAndPublicPrefix() {
        OssProperties properties = new OssProperties();
        OssMediaStorageService service = new OssMediaStorageService(properties);
        assertFalse(service.enabled());
        properties.setEndpoint("oss-cn-shanghai.aliyuncs.com");
        properties.setAccessKeyId("id");
        properties.setAccessKeySecret("secret");
        properties.setBucketName("xyzc");
        properties.setPrefix("private/");
        assertFalse(service.enabled());
        properties.setPrefix("public/");
        assertTrue(service.enabled());
    }
}
