package com.baseai.platform.datasource;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** 覆盖对象存储服务的安全校验与基础行为。 */
class ObjectStorageServiceTest {
    private WorkflowConnectionService connectionService;
    private ObjectStorageService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 使用模拟连接服务构建被测对象。 */
    @BeforeEach
    void setUp() {
        connectionService = Mockito.mock(WorkflowConnectionService.class);
        service = new ObjectStorageService(connectionService);
    }

    /** 构造对象存储连接配置。 */
    private ObjectNode config(String type) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("endpoint", type.equals("OSS") ? "https://oss-cn-hangzhou.aliyuncs.com" : "https://s3.example.com");
        config.put("bucket", "test-bucket");
        config.put("accessKey", "ak");
        config.put("secretKey", "sk");
        if ("S3".equals(type)) config.put("region", "us-east-1");
        return config;
    }

    /** 构造内部连接记录。 */
    private WorkflowConnectionService.StoredConnection connection(String type, ObjectNode config, boolean allowDelete, String keyPrefix) {
        config.put("allowDelete", allowDelete);
        if (keyPrefix != null) config.put("keyPrefix", keyPrefix);
        return new WorkflowConnectionService.StoredConnection(9L, "TEST", "Test", type, config, 7L, true, null, null);
    }

    /** 未开启删除权限时直接拒绝，不发起外部请求。 */
    @Test
    void rejectsDeleteWhenPermissionDisabled() {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("S3", config("S3"), false, "workflows/"));

        assertThrows(BusinessException.class, () -> service.delete(9L, "workflows/file.txt"));
    }

    /** 对象键不在授权前缀范围内时拒绝操作。 */
    @ParameterizedTest
    @CsvSource({
        "workflows/, other/file.txt",
        "workflows, workflowsabc",
        "workflows/, workflowsabc"
    })
    void rejectsKeyOutsidePrefix(String prefix, String key) {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("S3", config("S3"), true, prefix));

        assertThrows(BusinessException.class, () -> service.delete(9L, key));
    }

    /** 非对象存储类型不能执行对象存储操作。 */
    @Test
    void rejectsNonObjectStorageConnection() {
        when(connectionService.ownedForTest(9L)).thenReturn(
            new WorkflowConnectionService.StoredConnection(9L, "TEST", "Test", "MYSQL",
                objectMapper.createObjectNode(), 7L, true, null, null));

        assertThrows(BusinessException.class, () -> service.upload(9L, "key", "text/plain", 1, null));
        assertThrows(BusinessException.class, () -> service.download(9L, "key"));
        assertThrows(BusinessException.class, () -> service.delete(9L, "key"));
        assertThrows(BusinessException.class, () -> service.presign(9L, "key", "GET", 300L));
    }

    /** 预签名操作必须是 GET、PUT 或 DELETE。 */
    @ParameterizedTest
    @CsvSource({ "POST", "PATCH", "LIST" })
    void rejectsInvalidPresignOperation(String operation) {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("S3", config("S3"), false, ""));

        assertThrows(BusinessException.class, () -> service.presign(9L, "file.txt", operation, 300L));
    }

    /** 预签名操作未提供时默认 GET，空字符串视为无效。 */
    @Test
    void rejectsBlankPresignOperation() {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("S3", config("S3"), false, ""));

        assertThrows(BusinessException.class, () -> service.presign(9L, "file.txt", "", 300L));
    }

    /** 空对象键在前缀启用时被拒绝。 */
    @Test
    void rejectsBlankKeyWhenPrefixConfigured() {
        when(connectionService.ownedForTest(9L)).thenReturn(connection("OSS", config("OSS"), false, "workflows/"));

        assertThrows(BusinessException.class, () -> service.upload(9L, "", "text/plain", 1, null));
    }
}
