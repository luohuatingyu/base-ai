package com.baseai.platform.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.baseai.platform.config.OssProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 将媒体写入 OSS 的 public 前缀，并提供有限大小的供应商结果下载。 */
@Service
@EnableConfigurationProperties(OssProperties.class)
public class OssMediaStorageService {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private final OssProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** 注入 OSS 运行配置。 */
    public OssMediaStorageService(OssProperties properties) { this.properties = properties; }

    /** 配置完整时启用 OSS；测试和未配置环境继续使用原有临时存储。 */
    public boolean enabled() {
        return nonBlank(properties.getEndpoint()) && nonBlank(properties.getAccessKeyId()) && nonBlank(properties.getAccessKeySecret())
            && nonBlank(properties.getBucketName()) && normalizedPrefix().startsWith("public/");
    }

    /** 上传字节并返回仅位于 public 前缀下的公共地址。 */
    public String upload(byte[] bytes, String contentType, String category, String taskId) {
        if (!enabled() || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        String key = normalizedPrefix() + "ai-generation/" + safe(category) + "/" + safe(taskId) + "/" + UUID.randomUUID() + extension(contentType);
        OSS client = new OSSClientBuilder().build(normalizedEndpoint(), properties.getAccessKeyId(), properties.getAccessKeySecret());
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            if (nonBlank(contentType)) metadata.setContentType(contentType.toLowerCase(Locale.ROOT));
            client.putObject(properties.getBucketName(), key, new ByteArrayInputStream(bytes), metadata);
            return publicUrl(key);
        } finally { client.shutdown(); }
    }

    /** 将 Data URL 上传，失败由调用方决定是否保留原始地址。 */
    public String uploadDataUrl(String dataUrl, String contentType, String category, String taskId) {
        String prefix = "data:" + String.valueOf(contentType).toLowerCase(Locale.ROOT) + ";base64,";
        if (dataUrl == null || !dataUrl.startsWith(prefix)) return null;
        try { return upload(Base64.getDecoder().decode(dataUrl.substring(prefix.length())), contentType, category, taskId); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    /** 下载供应商结果，不跟随重定向，限制协议、响应大小和响应时间。 */
    public Downloaded download(String value, String fallbackContentType) throws IOException, InterruptedException {
        URI uri = URI.create(value == null ? "" : value);
        if (!java.util.Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IOException("media URL is not public HTTP(S)");
        HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2 || response.body().length == 0 || response.body().length > MAX_BYTES) throw new IOException("media download failed");
        String contentType = response.headers().firstValue("content-type").orElse(fallbackContentType);
        return new Downloaded(response.body(), contentType == null ? "application/octet-stream" : contentType.split(";", 2)[0]);
    }

    /** 返回 OSS 对象的 HTTPS 公共地址。 */
    private String publicUrl(String key) { return "https://" + properties.getBucketName() + "." + normalizedEndpoint().substring(normalizedEndpoint().indexOf("://") + 3) + "/" + key; }
    private String normalizedEndpoint() { String value = properties.getEndpoint().trim(); return value.startsWith("http://") || value.startsWith("https://") ? value : "https://" + value; }
    private String normalizedPrefix() { String value = properties.getPrefix() == null ? "public/" : properties.getPrefix().trim(); return value.endsWith("/") ? value : value + "/"; }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static String safe(String value) { return String.valueOf(value).replaceAll("[^A-Za-z0-9_-]", "_"); }
    private static String extension(String contentType) { return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("video") ? ".mp4" : ".bin"; }
    public record Downloaded(byte[] bytes, String contentType) {}
}
