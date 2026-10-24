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
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/** 将输入和生成结果写入可配置的 OSS 前缀，并提供有限大小的供应商结果下载。 */
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
        String prefix = normalizedPrefix();
        return nonBlank(properties.getEndpoint()) && nonBlank(properties.getAccessKeyId()) && nonBlank(properties.getAccessKeySecret())
            && nonBlank(properties.getBucketName()) && !prefix.contains("..") && !prefix.startsWith("/") && !prefix.contains("\\");
    }

    /** 上传字节并返回配置前缀下的对象地址。 */
    public String upload(byte[] bytes, String contentType, String category, String taskId) {
        if (!enabled() || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        String extension = extensionFor(contentType);
        if (extension == null) return null;
        String key = objectKey(normalizedPrefix(), contentType, category, taskId);
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
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) if (restricted(address)) throw new IOException("media URL targets a private address");
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
    /** 根据真实 MIME 类型返回可打开的文件扩展名，未知类型不允许伪装成 bin 文件。 */
    static String extensionFor(String contentType) {
        if (contentType == null || contentType.isBlank()) return null;
        String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "image/tiff" -> ".tif";
            case "image/heic" -> ".heic";
            case "image/heif" -> ".heif";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "video/x-m4v" -> ".m4v";
            case "video/mpeg" -> ".mpeg";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/ogg" -> ".ogg";
            case "audio/opus" -> ".opus";
            case "audio/aac" -> ".aac";
            case "audio/flac" -> ".flac";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            default -> null;
        };
    }

    /** 构造输入或输出对象键，按类别和日期分层，避免原文件名污染路径。 */
    static String objectKey(String prefix, String contentType, String category, String taskId) {
        String extension = extensionFor(contentType);
        if (extension == null) return null;
        LocalDate today = LocalDate.now();
        return normalizePrefix(prefix) + "materials/" + safe(category) + "/"
            + today.getYear() + "/" + String.format(Locale.ROOT, "%02d", today.getMonthValue()) + "/"
            + String.format(Locale.ROOT, "%02d", today.getDayOfMonth()) + "/" + UUID.randomUUID() + extension;
    }

    private static String normalizePrefix(String value) {
        String prefix = value == null || value.isBlank() ? "public/" : value.trim();
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
    /** 拒绝回环、私网、链路本地和组播地址，避免供应商结果触发 SSRF。 */
    private static boolean restricted(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        boolean sharedIpv4 = bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalIpv6 || sharedIpv4;
    }
    public record Downloaded(byte[] bytes, String contentType) {}
}
