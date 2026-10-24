package com.baseai.platform.service;

import com.baseai.platform.config.OssProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 OSS 前缀和媒体对象格式，避免把媒体写入错误目录或错误扩展名。 */
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
        assertTrue(service.enabled());
    }

    /** 图片、音频和视频必须根据真实 MIME 类型使用可打开的扩展名。 */
    @Test
    void mapsMediaMimeTypesToRealExtensions() {
        assertEquals(".png", OssMediaStorageService.extensionFor("image/png"));
        assertEquals(".jpg", OssMediaStorageService.extensionFor("image/jpeg"));
        assertEquals(".mp3", OssMediaStorageService.extensionFor("audio/mpeg"));
        assertEquals(".wav", OssMediaStorageService.extensionFor("audio/x-wav"));
        assertEquals(".mp4", OssMediaStorageService.extensionFor("video/mp4"));
        assertEquals(".webm", OssMediaStorageService.extensionFor("video/webm; codecs=vp9"));
        assertNull(OssMediaStorageService.extensionFor("application/octet-stream"));
    }

    /** 输入和输出对象均按配置前缀分目录，并按日期和 UUID 组织。 */
    @Test
    void buildsConfiguredMaterialObjectKeys() {
        String key = OssMediaStorageService.objectKey("private/", "image/png", "input", "task/1");
        assertTrue(key.matches("private/materials/input/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.png"), key);
        String output = OssMediaStorageService.objectKey("private/", "audio/mpeg", "output", "task/1");
        assertTrue(output.matches("private/materials/output/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.mp3"), output);
    }
}
