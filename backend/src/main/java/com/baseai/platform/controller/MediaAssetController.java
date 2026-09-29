package com.baseai.platform.controller;

import com.baseai.platform.service.MediaAssetService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** 提供给视频供应商读取的短期媒体素材 URL；令牌本身是唯一访问凭据。 */
@RestController
@RequestMapping("/api/open/media-assets")
public class MediaAssetController {
    private final MediaAssetService assets;

    /** 注入短期素材仓。 */
    public MediaAssetController(MediaAssetService assets) { this.assets = assets; }

    /** 返回素材字节并禁止缓存，过期令牌统一返回 404。 */
    @GetMapping("/{token}")
    public ResponseEntity<byte[]> get(@PathVariable String token) {
        MediaAssetService.Asset asset = assets.read(token);
        if (asset == null) return ResponseEntity.notFound().build();
        MediaType type;
        try { type = MediaType.parseMediaType(asset.contentType()); }
        catch (Exception ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok().contentType(type).contentLength(asset.bytes().length)
            .cacheControl(CacheControl.noStore().cachePrivate().maxAge(Duration.ZERO)).body(asset.bytes());
    }
}
