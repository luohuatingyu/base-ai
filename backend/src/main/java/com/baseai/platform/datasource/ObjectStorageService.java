package com.baseai.platform.datasource;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.Closeable;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/** 基于数据源配置执行 S3 或 OSS 对象存储操作。 */
@Service
public class ObjectStorageService {

    private static final Set<String> SUPPORTED_OPERATIONS = Set.of("GET", "PUT", "DELETE");
    private static final long MAX_PRESIGN_SECONDS = 3600;
    private static final long DEFAULT_PRESIGN_SECONDS = 300;

    private final WorkflowConnectionService connectionService;

    /** 注入工作流连接服务以读取解密后的数据源配置。 */
    public ObjectStorageService(WorkflowConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /** 上传对象到指定数据源。 */
    public UploadResult upload(Long connectionId, String key, String contentType, long contentLength, InputStream content) {
        WorkflowConnectionService.StoredConnection connection = resolveConnection(connectionId);
        String bucket = bucket(connection);
        validateKeyPrefix(key, keyPrefix(connection));
        String normalizedType = connection.connectionType().toUpperCase(Locale.ROOT);
        return switch (normalizedType) {
            case "S3" -> uploadS3(connection, bucket, key, contentType, contentLength, content);
            case "OSS" -> uploadOss(connection, bucket, key, contentType, contentLength, content);
            default -> throw new BusinessException("workflow.connectionTypeInvalid");
        };
    }

    /** 从指定数据源下载对象。 */
    public DownloadResult download(Long connectionId, String key) {
        WorkflowConnectionService.StoredConnection connection = resolveConnection(connectionId);
        String bucket = bucket(connection);
        validateKeyPrefix(key, keyPrefix(connection));
        String normalizedType = connection.connectionType().toUpperCase(Locale.ROOT);
        return switch (normalizedType) {
            case "S3" -> downloadS3(connection, bucket, key);
            case "OSS" -> downloadOss(connection, bucket, key);
            default -> throw new BusinessException("workflow.connectionTypeInvalid");
        };
    }

    /** 从指定数据源删除对象。 */
    public void delete(Long connectionId, String key) {
        WorkflowConnectionService.StoredConnection connection = resolveConnection(connectionId);
        if (!allowDelete(connection)) throw new BusinessException("workflow.s3DeleteForbidden");
        String bucket = bucket(connection);
        validateKeyPrefix(key, keyPrefix(connection));
        String normalizedType = connection.connectionType().toUpperCase(Locale.ROOT);
        switch (normalizedType) {
            case "S3" -> deleteS3(connection, bucket, key);
            case "OSS" -> deleteOss(connection, bucket, key);
            default -> throw new BusinessException("workflow.connectionTypeInvalid");
        }
    }

    /** 为指定数据源生成预签名 URL。 */
    public PresignResult presign(Long connectionId, String key, String operation, Long expiresInSeconds) {
        WorkflowConnectionService.StoredConnection connection = resolveConnection(connectionId);
        String bucket = bucket(connection);
        validateKeyPrefix(key, keyPrefix(connection));
        String normalizedOperation = operation == null ? "GET" : operation.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_OPERATIONS.contains(normalizedOperation)) throw new BusinessException("objectStorage.operationInvalid");
        long seconds = expiresInSeconds == null ? DEFAULT_PRESIGN_SECONDS : Math.min(expiresInSeconds, MAX_PRESIGN_SECONDS);
        String normalizedType = connection.connectionType().toUpperCase(Locale.ROOT);
        URL url = switch (normalizedType) {
            case "S3" -> presignS3(connection, bucket, key, normalizedOperation, seconds);
            case "OSS" -> presignOss(connection, bucket, key, normalizedOperation, seconds);
            default -> throw new BusinessException("workflow.connectionTypeInvalid");
        };
        return new PresignResult(url.toString(), Instant.now().plusSeconds(seconds));
    }

    /** 校验当前用户是否拥有该数据源，并确认类型为对象存储。 */
    private WorkflowConnectionService.StoredConnection resolveConnection(Long connectionId) {
        WorkflowConnectionService.StoredConnection connection = connectionService.ownedForTest(connectionId);
        String type = connection.connectionType().toUpperCase(Locale.ROOT);
        if (!Set.of("S3", "OSS").contains(type)) throw new BusinessException("workflow.connectionTypeInvalid");
        return connection;
    }

    /** 读取存储桶名称。 */
    private String bucket(WorkflowConnectionService.StoredConnection connection) {
        String bucket = connection.config().path("bucket").asText("");
        if (bucket.isBlank()) throw new BusinessException("workflow.connectionInvalid");
        return bucket;
    }

    /** 读取键前缀。 */
    private String keyPrefix(WorkflowConnectionService.StoredConnection connection) {
        return connection.config().path("keyPrefix").asText("");
    }

    /** 读取是否允许删除。 */
    private boolean allowDelete(WorkflowConnectionService.StoredConnection connection) {
        return connection.config().path("allowDelete").asBoolean(false);
    }

    /** 校验对象键是否落在授权前缀范围内；前缀按目录语义处理，防止绕过。 */
    private void validateKeyPrefix(String key, String prefix) {
        if (prefix == null || prefix.isBlank()) return;
        if (key == null || key.isBlank()) throw new BusinessException("workflow.s3PathForbidden");
        String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
        if (key.equals(prefix)) return;
        if (key.startsWith(normalized)) return;
        throw new BusinessException("workflow.s3PathForbidden");
    }

    /** 使用 S3 SDK 上传对象。 */
    private UploadResult uploadS3(WorkflowConnectionService.StoredConnection connection, String bucket, String key,
                                  String contentType, long contentLength, InputStream content) {
        try (S3Client client = buildS3Client(connection)) {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
            client.putObject(request, RequestBody.fromInputStream(content, contentLength));
            return new UploadResult(bucket, key);
        }
    }

    /** 使用 OSS SDK 上传对象。 */
    private UploadResult uploadOss(WorkflowConnectionService.StoredConnection connection, String bucket, String key,
                                   String contentType, long contentLength, InputStream content) {
        OSS client = buildOssClient(connection);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType == null ? "application/octet-stream" : contentType);
            if (contentLength > 0) metadata.setContentLength(contentLength);
            client.putObject(bucket, key, content, metadata);
            return new UploadResult(bucket, key);
        } finally {
            client.shutdown();
        }
    }

    /** 使用 S3 SDK 下载对象。 */
    private DownloadResult downloadS3(WorkflowConnectionService.StoredConnection connection, String bucket, String key) {
        try (S3Client client = buildS3Client(connection)) {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
            String contentType = response.response().contentType();
            long contentLength = response.response().contentLength();
            return new DownloadResult(bucket, key, contentType, contentLength, response, response);
        }
    }

    /** 使用 OSS SDK 下载对象。 */
    private DownloadResult downloadOss(WorkflowConnectionService.StoredConnection connection, String bucket, String key) {
        OSS client = buildOssClient(connection);
        com.aliyun.oss.model.OSSObject object = client.getObject(bucket, key);
        String contentType = object.getObjectMetadata().getContentType();
        long contentLength = object.getObjectMetadata().getContentLength();
        Closeable cleanup = () -> {
            object.close();
            client.shutdown();
        };
        return new DownloadResult(bucket, key, contentType, contentLength, object.getObjectContent(), cleanup);
    }

    /** 使用 S3 SDK 删除对象。 */
    private void deleteS3(WorkflowConnectionService.StoredConnection connection, String bucket, String key) {
        try (S3Client client = buildS3Client(connection)) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        }
    }

    /** 使用 OSS SDK 删除对象。 */
    private void deleteOss(WorkflowConnectionService.StoredConnection connection, String bucket, String key) {
        OSS client = buildOssClient(connection);
        try {
            client.deleteObject(bucket, key);
        } finally {
            client.shutdown();
        }
    }

    /** 使用 S3 SDK 生成预签名 URL。 */
    private URL presignS3(WorkflowConnectionService.StoredConnection connection, String bucket, String key,
                          String operation, long seconds) {
        try (S3Presigner presigner = buildS3Presigner(connection)) {
            Duration duration = Duration.ofSeconds(seconds);
            return switch (operation) {
                case "GET" -> {
                    GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                        .signatureDuration(duration)
                        .getObjectRequest(req -> req.bucket(bucket).key(key))
                        .build();
                    PresignedGetObjectRequest presigned = presigner.presignGetObject(request);
                    yield presigned.url();
                }
                case "PUT" -> {
                    PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                        .signatureDuration(duration)
                        .putObjectRequest(req -> req.bucket(bucket).key(key))
                        .build();
                    PresignedPutObjectRequest presigned = presigner.presignPutObject(request);
                    yield presigned.url();
                }
                default -> throw new BusinessException("objectStorage.operationInvalid");
            };
        }
    }

    /** 使用 OSS SDK 生成预签名 URL。 */
    private URL presignOss(WorkflowConnectionService.StoredConnection connection, String bucket, String key,
                           String operation, long seconds) {
        OSS client = buildOssClient(connection);
        try {
            Date expiration = new Date(System.currentTimeMillis() + seconds * 1000);
            HttpMethod method = HttpMethod.valueOf(operation);
            return client.generatePresignedUrl(bucket, key, expiration, method);
        } finally {
            client.shutdown();
        }
    }

    /** 根据数据源配置构建 S3 客户端。 */
    private S3Client buildS3Client(WorkflowConnectionService.StoredConnection connection) {
        JsonNode config = connection.config();
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(config.path("region").asText("us-east-1")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                config.path("accessKey").asText(), config.path("secretKey").asText())))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(config.path("pathStyle").asBoolean(true)).build());
        String endpoint = config.path("endpoint").asText("");
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    /** 根据数据源配置构建 S3 预签名器。 */
    private S3Presigner buildS3Presigner(WorkflowConnectionService.StoredConnection connection) {
        JsonNode config = connection.config();
        S3Presigner.Builder builder = S3Presigner.builder()
            .region(Region.of(config.path("region").asText("us-east-1")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                config.path("accessKey").asText(), config.path("secretKey").asText())));
        String endpoint = config.path("endpoint").asText("");
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    /** 根据数据源配置构建 OSS 客户端。 */
    private OSS buildOssClient(WorkflowConnectionService.StoredConnection connection) {
        JsonNode config = connection.config();
        String endpoint = config.path("endpoint").asText("");
        if (endpoint.isBlank()) throw new BusinessException("workflow.connectionInvalid");
        return new OSSClientBuilder().build(endpoint, config.path("accessKey").asText(), config.path("secretKey").asText());
    }

    /** 上传结果。 */
    public record UploadResult(String bucket, String key) {}

    /** 下载结果，包含对象元数据、输入流以及流消费后需要执行的清理逻辑。 */
    public record DownloadResult(String bucket, String key, String contentType, long contentLength,
                                 InputStream content, Closeable cleanup) {}

    /** 预签名结果。 */
    public record PresignResult(String url, Instant expiresAt) {}
}
