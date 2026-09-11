package com.baseai.platform.datasource;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.baseai.platform.workflow.WorkflowConnectionTester;
import com.baseai.platform.workflow.WorkflowModels;
import com.baseai.platform.workflow.WorkflowNodeMarketplaceService;
import com.baseai.platform.workflow.WorkflowPluginOAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 提供全部受管数据源的配置、测试和插件 OAuth 接口。 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {
    private final WorkflowConnectionService connectionService;
    private final WorkflowConnectionTester connectionTester;
    private final WorkflowNodeMarketplaceService marketplaceService;
    private final WorkflowPluginOAuthService pluginOAuthService;
    private final ObjectStorageService objectStorageService;

    /** 注入受管连接、连通性测试、插件目录、OAuth 和对象存储服务。 */
    public DataSourceController(WorkflowConnectionService connectionService,
                                WorkflowConnectionTester connectionTester,
                                WorkflowNodeMarketplaceService marketplaceService,
                                WorkflowPluginOAuthService pluginOAuthService,
                                ObjectStorageService objectStorageService) {
        this.connectionService = connectionService;
        this.connectionTester = connectionTester;
        this.marketplaceService = marketplaceService;
        this.pluginOAuthService = pluginOAuthService;
        this.objectStorageService = objectStorageService;
    }

    /** 查询当前用户可见的脱敏数据源。 */
    @GetMapping
    @RequiredPermission("operations:data-source:list")
    public List<WorkflowModels.ConnectionView> list() { return connectionService.connections(); }

    /** 创建由当前用户拥有的受管数据源。 */
    @PostMapping
    @RequiredPermission("operations:data-source:create")
    public WorkflowModels.ConnectionView create(@RequestBody WorkflowModels.ConnectionCommand command) {
        return connectionService.create(command);
    }

    /** 更新当前用户拥有的数据源并保留未改动的脱敏凭据。 */
    @PutMapping("/{id}")
    @RequiredPermission("operations:data-source:update")
    public WorkflowModels.ConnectionView update(@PathVariable Long id,
                                                @RequestBody WorkflowModels.ConnectionCommand command) {
        return connectionService.update(id, command);
    }

    /** 删除未被工作流版本或有效同步计划引用的数据源。 */
    @DeleteMapping("/{id}")
    @RequiredPermission("operations:data-source:delete")
    public void delete(@PathVariable Long id) { connectionService.delete(id); }

    /** 对当前用户拥有的数据源执行无副作用连通性测试。 */
    @PostMapping("/{id}/test")
    @RequiredPermission("operations:data-source:test")
    public Map<String, Object> test(@PathVariable Long id) { return connectionTester.test(id); }

    /** 实时探测数据源状态并返回轻量指标，同时刷新页面展示的最近检测结果。 */
    @GetMapping("/{id}/status")
    @RequiredPermission("operations:data-source:test")
    public Map<String, Object> status(@PathVariable Long id) { return connectionTester.test(id); }

    /** 查询已安装且可用于插件数据源配置的组件。 */
    @GetMapping("/plugin-component-options")
    @RequiredPermission("operations:data-source:list")
    public List<WorkflowModels.PluginComponentOption> pluginComponentOptions() {
        return marketplaceService.componentOptions();
    }

    /** 为当前用户拥有的插件数据源创建一次性 OAuth 授权请求。 */
    @PostMapping("/{id}/oauth/authorize")
    @RequiredPermission("operations:data-source:update")
    public WorkflowModels.PluginOAuthAuthorization authorizePlugin(
        @PathVariable Long id, @RequestBody WorkflowModels.PluginOAuthAuthorizeCommand command) {
        return pluginOAuthService.authorize(id, command);
    }

    /** 消费一次性 OAuth state 并把交换结果加密写回插件数据源。 */
    @PostMapping("/plugin-oauth/callback")
    @RequiredPermission("operations:data-source:update")
    public WorkflowModels.PluginOAuthCallbackResult pluginOAuthCallback(
        @RequestBody WorkflowModels.PluginOAuthCallbackCommand command) {
        return pluginOAuthService.callback(command);
    }

    /** 上传对象到对象存储数据源。 */
    @PostMapping(value = "/{id}/object-storage/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiredPermission("operations:data-source:update")
    public Map<String, Object> uploadObject(@PathVariable Long id,
                                              @RequestParam String key,
                                              @RequestParam(required = false) String contentType,
                                              @RequestPart MultipartFile file) throws IOException {
        String type = contentType == null || contentType.isBlank() ? file.getContentType() : contentType;
        try (InputStream stream = file.getInputStream()) {
            ObjectStorageService.UploadResult result = objectStorageService.upload(id, key, type, file.getSize(), stream);
            return Map.of("bucket", result.bucket(), "key", result.key());
        }
    }

    /** 从对象存储数据源下载对象。 */
    @GetMapping("/{id}/object-storage/download")
    @RequiredPermission("operations:data-source:list")
    public ResponseEntity<StreamingResponseBody> downloadObject(@PathVariable Long id,
                                                                @RequestParam String key,
                                                                @RequestParam(required = false) String downloadName) {
        ObjectStorageService.DownloadResult result = objectStorageService.download(id, key);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(result.contentType() == null ? "application/octet-stream" : result.contentType()));
        if (result.contentLength() > 0) headers.setContentLength(result.contentLength());
        String filename = downloadName == null || downloadName.isBlank() ? key.substring(key.lastIndexOf('/') + 1) : downloadName;
        headers.setContentDispositionFormData("attachment", encodeFilename(filename));
        StreamingResponseBody body = out -> {
            try (InputStream in = result.content(); java.io.Closeable cleanup = result.cleanup()) {
                in.transferTo(out);
            }
        };
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    /** 从对象存储数据源删除对象。 */
    @DeleteMapping("/{id}/object-storage")
    @RequiredPermission("operations:data-source:update")
    public Map<String, Object> deleteObject(@PathVariable Long id, @RequestParam String key) {
        objectStorageService.delete(id, key);
        return Map.of("deleted", true);
    }

    /** 为对象存储数据源生成预签名 URL。 */
    @PostMapping("/{id}/object-storage/presign")
    @RequiredPermission("operations:data-source:list")
    public Map<String, Object> presignObject(@PathVariable Long id, @RequestBody Map<String, Object> command) {
        String key = command.get("key") == null ? "" : command.get("key").toString();
        String operation = command.get("operation") == null ? "GET" : command.get("operation").toString();
        Long expiresInSeconds = command.get("expiresInSeconds") instanceof Number number ? number.longValue() : null;
        ObjectStorageService.PresignResult result = objectStorageService.presign(id, key, operation, expiresInSeconds);
        return Map.of("url", result.url(), "expiresAt", result.expiresAt().toString());
    }

    /** 对下载文件名进行 URL 编码，避免中文或特殊字符导致头信息异常。 */
    private String encodeFilename(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
