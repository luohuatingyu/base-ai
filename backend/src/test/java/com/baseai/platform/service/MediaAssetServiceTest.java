package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证浏览器素材转换为供应商可访问临时 URL 的业务边界。 */
class MediaAssetServiceTest {
    /** 配置启用时输入 Data URL 优先写入 OSS，并把公共地址交给供应商。 */
    @Test
    void materializesDataUrlToOssWhenAvailable() {
        OssMediaStorageService oss = org.mockito.Mockito.mock(OssMediaStorageService.class);
        org.mockito.Mockito.when(oss.uploadDataUrl("data:image/png;base64,YQ==", "image/png", "input", "first.png"))
            .thenReturn("https://xyzc.oss-cn-shanghai.aliyuncs.com/public/ai-generation/input/first.png");
        MediaAssetService service = new MediaAssetService(oss);
        var input = new MediaGenerationService.MediaInput("IMAGE", "first.png", "image/png", "data:image/png;base64,YQ==", "first_frame");
        assertEquals("https://xyzc.oss-cn-shanghai.aliyuncs.com/public/ai-generation/input/first.png", service.materialize(List.of(input), "https://media.example").get(0).dataUrl());
    }
    /** Data URL 会被保存为临时素材，返回地址可按令牌读取并保留 MIME 类型。 */
    @Test
    void materializesDataUrlAsReadableTemporaryAsset() {
        MediaAssetService service = new MediaAssetService();
        var input = new MediaGenerationService.MediaInput("IMAGE", "first.png", "image/png",
            "data:image/png;base64,YQ==", "first_frame");

        var materialized = service.materialize(List.of(input), "https://media.example/");
        String url = materialized.get(0).dataUrl();

        assertTrue(url.matches("https://media\\.example/api/open/media-assets/[A-Za-z0-9_-]{43}"));
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertEquals("image/png", service.read(token).contentType());
        assertArrayEquals("a".getBytes(StandardCharsets.UTF_8), service.read(token).bytes());
        assertEquals("first_frame", materialized.get(0).role());
        assertNull(service.read("invalid-token"));
    }

    /** 已有 URL 或素材 ID 不应被重复存储，临时 URL 必须使用合法的平台来源。 */
    @Test
    void preservesExistingReferencesAndRejectsInvalidOrigin() {
        MediaAssetService service = new MediaAssetService();
        var remote = new MediaGenerationService.MediaInput("IMAGE", "remote.png", "image/png",
            "https://cdn.example/remote.png", "first_frame");
        var task = new MediaGenerationService.MediaInput("TASK", "task.txt", "text/plain", "draft_123", "");

        var preserved = service.materialize(List.of(remote, task), "https://media.example");
        assertEquals("https://cdn.example/remote.png", preserved.get(0).dataUrl());
        assertEquals("draft_123", preserved.get(1).dataUrl());
        assertThrows(BusinessException.class, () -> service.materialize(List.of(
            new MediaGenerationService.MediaInput("IMAGE", "x.png", "image/png", "data:image/png;base64,YQ==", "last_frame")),
            "http://localhost:8080/path"));
    }
}
