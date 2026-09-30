package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 保存媒体生成输入的短期素材，并将浏览器文件转换为供应商可访问的公网 URL。 */
@Service
public class MediaAssetService {
    private static final long TTL_SECONDS = 20 * 60;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final Set<String> URL_SCHEMES = Set.of("http", "https");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private final Map<String, Asset> assets = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final OssMediaStorageService oss;

    /** 使用 OSS 持久化供应商需要长期读取的输入素材。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MediaAssetService(OssMediaStorageService oss) { this.oss = oss; }

    /** 保持单元测试和未启用 OSS 的兼容构造方式。 */
    public MediaAssetService() { this.oss = null; }

    /** 将 Data URL 素材转换为短期公网 URL；已有公网 URL 或素材 ID 原样保留。 */
    public List<MediaGenerationService.MediaInput> materialize(List<MediaGenerationService.MediaInput> inputs, String publicOrigin) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        List<MediaGenerationService.MediaInput> result = new ArrayList<>(inputs.size());
        for (MediaGenerationService.MediaInput input : inputs) {
            if (input == null || !Set.of("IMAGE", "VIDEO", "AUDIO").contains(input.kind())
                || input.dataUrl() == null || !input.dataUrl().startsWith("data:")) {
                result.add(input);
                continue;
            }
            String durable = oss == null ? null : oss.uploadDataUrl(input.dataUrl(), input.contentType(), "input", input.fileName());
            result.add(new MediaGenerationService.MediaInput(input.kind(), input.fileName(), input.contentType(),
                durable == null ? store(publicOrigin, input.contentType(), input.dataUrl()) : durable, input.role()));
        }
        return List.copyOf(result);
    }

    /** 读取未过期的临时素材，过期或不存在时返回空值。 */
    public Asset read(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{40,64}")) return null;
        Asset asset = assets.get(token);
        if (asset == null || asset.expiresAt().isBefore(Instant.now())) {
            if (asset != null) remove(token, asset);
            return null;
        }
        return asset;
    }

    /** 定时清理过期内容，避免临时素材长期占用内存。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanup() {
        Instant now = Instant.now();
        assets.forEach((token, asset) -> { if (asset.expiresAt().isBefore(now)) remove(token, asset); });
    }

    /** 将合法 Data URL 保存并返回公开地址。 */
    private String store(String publicOrigin, String contentType, String dataUrl) {
        String origin = normalizeOrigin(publicOrigin);
        String prefix = "data:" + contentType.toLowerCase(Locale.ROOT) + ";base64,";
        if (!dataUrl.startsWith(prefix)) throw new BusinessException("llm.generationRequestInvalid");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(dataUrl.substring(prefix.length())); }
        catch (IllegalArgumentException exception) { throw new BusinessException("llm.generationRequestInvalid"); }
        if (bytes.length == 0 || bytes.length > 50 * 1024 * 1024) throw new BusinessException("llm.generationRequestInvalid");
        cleanup();
        if (totalBytes.get() + bytes.length > MAX_TOTAL_BYTES) throw new BusinessException("llm.generationAssetStorageUnavailable");
        String token = randomToken();
        Asset asset = new Asset(bytes, contentType, Instant.now().plusSeconds(TTL_SECONDS));
        assets.put(token, asset); totalBytes.addAndGet(bytes.length);
        return origin + "/api/open/media-assets/" + token;
    }

    /** 校验公网来源，防止把任意 Host 注入供应商请求。 */
    private String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("llm.generationAssetUrlUnavailable");
        try {
            URI uri = URI.create(value.trim());
            if (!URL_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())))
                throw new IllegalArgumentException();
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
        } catch (Exception exception) { throw new BusinessException("llm.generationAssetUrlUnavailable"); }
    }

    /** 生成不可预测的 URL 令牌。 */
    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 原子移除素材并释放内存计数。 */
    private void remove(String token, Asset asset) {
        if (assets.remove(token, asset)) totalBytes.addAndGet(-asset.bytes().length);
    }

    /** 临时素材响应内容。 */
    public record Asset(byte[] bytes, String contentType, Instant expiresAt) {}
}
