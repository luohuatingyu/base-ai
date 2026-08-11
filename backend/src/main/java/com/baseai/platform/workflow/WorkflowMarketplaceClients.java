package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 封装 n8n 与 Dify 官方市场的不稳定外部契约，并统一超时、缓存和响应限制。 */
@Component
public class WorkflowMarketplaceClients {
    private static final int MAX_CATALOG_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI n8nBase;
    private final URI n8nApiBase;
    private final URI npmRegistryBase;
    private final URI difyBase;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final int maxPackageBytes;
    private final Map<String, CacheValue<SearchResult>> cache = new ConcurrentHashMap<>();

    /** 创建只允许 HTTPS 官方市场根地址的短超时客户端。 */
    public WorkflowMarketplaceClients(ObjectMapper objectMapper, PlatformProperties properties) {
        this.objectMapper = objectMapper;
        PlatformProperties.Workflow workflow = properties.getWorkflow();
        n8nBase = secureBase(workflow.getMarketplaceN8nUrl(), "n8n.io");
        n8nApiBase = secureBase(workflow.getMarketplaceN8nApiUrl(), "api.n8n.io");
        npmRegistryBase = secureBase(workflow.getMarketplaceNpmRegistryUrl(), "registry.npmjs.org");
        difyBase = secureBase(workflow.getMarketplaceDifyUrl(), "marketplace.dify.ai");
        timeout = Duration.ofSeconds(Math.max(1, workflow.getMarketplaceTimeoutSeconds()));
        cacheTtl = Duration.ofSeconds(Math.max(1, workflow.getMarketplaceCacheSeconds()));
        maxPackageBytes = Math.max(1, workflow.getMarketplaceMaxPackageBytes());
        httpClient = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 查询 n8n 官方 Integrations 目录并在本地执行检索和分页。 */
    public SearchResult searchN8n(String query, int page, int pageSize) {
        SearchResult catalog = cached("n8n", this::loadN8n);
        String needle = normalized(query);
        List<MarketplaceEntry> filtered = catalog.items().stream().filter(item -> needle.isBlank()
            || normalized(item.name()).contains(needle) || normalized(item.externalId()).contains(needle)).toList();
        return page(filtered, page, pageSize);
    }

    /** 按稳定节点 ID 查找 n8n 市场条目。 */
    public Optional<MarketplaceEntry> findN8n(String externalId) {
        return cached("n8n", this::loadN8n).items().stream().filter(item -> item.externalId().equals(externalId)).findFirst();
    }

    /** 从 npm 官方注册表下载固定版本 n8n 插件并校验注册表完整性。 */
    public PackageDownload downloadN8nPackage(MarketplaceEntry entry) {
        String packageName = text(entry.raw(), "packageName");
        String version = entry.version();
        if (!safePackageName(packageName) || !safeSegment(version)) {
            throw new BusinessException("workflow.marketplaceNodeNotFound");
        }
        String encoded = URLEncoder.encode(packageName, StandardCharsets.UTF_8).replace("+", "%20");
        JsonNode metadata = getJson(npmRegistryBase.resolve("/" + encoded));
        JsonNode distribution = metadata.path("versions").path(version).path("dist");
        String tarball = distribution.path("tarball").asText("");
        String integrity = distribution.path("integrity").asText("");
        if (tarball.isBlank() || integrity.isBlank()) throw new BusinessException("workflow.marketplacePackageInvalid");
        URI uri;
        try { uri = URI.create(tarball); }
        catch (Exception exception) { throw new BusinessException("workflow.marketplacePackageInvalid"); }
        if (!allowedNpmDownload(uri)) throw new BusinessException("workflow.marketplacePackageInvalid");
        HttpPayload response = sendBytes(uri);
        if (response.statusCode() / 100 != 2 || response.body().length == 0 || !matchesIntegrity(response.body(), integrity)) {
            throw new BusinessException("workflow.marketplacePackageInvalid");
        }
        return new PackageDownload(response.body(), sha256(response.body()));
    }

    /** 使用 Dify Marketplace 高级检索接口查询全部类型插件。 */
    public SearchResult searchDify(String query, String category, int page, int pageSize) {
        String key = "dify|" + normalized(query) + "|" + normalized(category) + "|" + page + "|" + pageSize;
        return cached(key, () -> loadDify(query, category, page, pageSize));
    }

    /** 按插件 ID 从官方市场重新确认当前版本。 */
    public Optional<MarketplaceEntry> findDify(String pluginId) {
        String name = pluginId == null ? "" : pluginId.substring(pluginId.lastIndexOf('/') + 1);
        return searchDify(name, "", 1, 100).items().stream().filter(item -> item.externalId().equals(pluginId)).findFirst();
    }

    /** 下载 Dify 官方市场包，只允许官方域名或官方 R2 存储重定向。 */
    public byte[] downloadDifyPackage(String pluginId, String version) {
        String[] parts = pluginId == null ? new String[0] : pluginId.split("/", -1);
        if (parts.length != 2 || !safeSegment(parts[0]) || !safeSegment(parts[1]) || !safeSegment(version)) {
            throw new BusinessException("workflow.marketplaceNodeNotFound");
        }
        URI uri = difyBase.resolve("/api/v1/plugins/" + parts[0] + "/" + parts[1] + "/" + version + "/download");
        for (int redirect = 0; redirect < 3; redirect++) {
            HttpPayload response = sendBytes(uri);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new BusinessException("workflow.marketplaceUnavailable"));
                URI next = uri.resolve(location);
                if (!allowedDifyDownload(next)) throw new BusinessException("workflow.marketplaceUnavailable");
                uri = next;
                continue;
            }
            if (response.statusCode() / 100 != 2 || response.body().length == 0) {
                throw new BusinessException("workflow.marketplacePackageInvalid");
            }
            return response.body();
        }
        throw new BusinessException("workflow.marketplaceUnavailable");
    }

    /** 从 n8n 站点的官方搜索过滤目录读取全部市场节点。 */
    private SearchResult loadN8n() {
        List<MarketplaceEntry> items = new ArrayList<>();
        int page = 1;
        int pages;
        do {
            String query = "/api/community-nodes?maxAiNodeSdk=999&pagination%5Bpage%5D=" + page
                + "&pagination%5BpageSize%5D=100&fields%5B0%5D=authorName&fields%5B1%5D=description"
                + "&fields%5B2%5D=displayName&fields%5B3%5D=name&fields%5B4%5D=packageName"
                + "&fields%5B5%5D=npmVersion&fields%5B6%5D=checksum&fields%5B7%5D=isOfficialNode"
                + "&fields%5B8%5D=companyName&fields%5B9%5D=numberOfDownloads";
            JsonNode root = getJson(n8nApiBase.resolve(query));
            SearchResult parsed = parseN8n(root);
            items.addAll(parsed.items());
            pages = Math.max(1, root.path("meta").path("pagination").path("pageCount").asInt(1));
            page++;
            if (page > 100) throw new BusinessException("workflow.marketplaceResponseInvalid");
        } while (page <= pages);
        return new SearchResult(List.copyOf(items), items.size());
    }

    /** 解析 n8n 官方目录响应，供录制契约测试复用。 */
    SearchResult parseN8n(JsonNode root) {
        JsonNode data = root.isArray() ? root : root.path("data");
        if (!data.isArray()) throw new BusinessException("workflow.marketplaceResponseInvalid");
        List<MarketplaceEntry> items = new ArrayList<>();
        for (JsonNode node : data) {
            JsonNode item = node.path("attributes").isObject() ? node.path("attributes") : node;
            String id = text(item, "name");
            if (id.isBlank()) id = text(item, "id");
            String name = text(item, "displayName");
            if (name.isBlank()) name = text(item, "label");
            if (!id.isBlank() && !name.isBlank()) {
                String packageName = text(item, "packageName");
                String publisher = text(item, "companyName");
                if (publisher.isBlank()) publisher = text(item, "authorName");
                if (publisher.isBlank()) publisher = publisher(packageName.isBlank() ? id : packageName);
                items.add(new MarketplaceEntry(id, name, text(item, "description"), text(item, "npmVersion"),
                    publisher, "community-node", item.path("isOfficialNode").asBoolean(false) ? "verified" : "community", item));
            }
        }
        long total = root.isArray() ? items.size() : root.path("meta").path("pagination").path("total").asLong(items.size());
        return new SearchResult(List.copyOf(items), total);
    }

    /** 调用 Dify 高级检索并最小化保存市场元数据。 */
    private SearchResult loadDify(String query, String category, int page, int pageSize) {
        ObjectNode body = objectMapper.createObjectNode().put("query", query == null ? "" : query)
            .put("page", page).put("page_size", pageSize).put("sort_by", "install_count").put("sort_order", "DESC");
        if (category != null && !category.isBlank()) body.put("category", category);
        JsonNode root = postJson(difyBase.resolve("/api/v1/plugins/search/advanced"), body);
        return parseDify(root);
    }

    /** 解析 Dify 官方高级检索响应，供录制契约测试复用。 */
    SearchResult parseDify(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.path("plugins").isArray()) throw new BusinessException("workflow.marketplaceResponseInvalid");
        List<MarketplaceEntry> items = new ArrayList<>();
        for (JsonNode plugin : data.path("plugins")) {
            String id = text(plugin, "plugin_id");
            String name = localized(plugin.path("label"), text(plugin, "name"));
            if (!id.isBlank() && !name.isBlank()) {
                items.add(new MarketplaceEntry(id, name, localizedValue(plugin.path("brief")),
                    text(plugin, "latest_version"), text(plugin, "org"), text(plugin, "category"),
                    plugin.path("verification").path("authorized_category").asText("community"), plugin));
            }
        }
        return new SearchResult(List.copyOf(items), data.path("total").asLong(items.size()));
    }

    /** 发送 JSON GET 并检查状态和体积。 */
    private JsonNode getJson(URI uri) {
        return json(send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build()));
    }

    /** 发送 JSON POST 并检查状态和体积。 */
    private JsonNode postJson(URI uri, JsonNode body) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return json(send(request));
    }

    /** 执行目录请求并把网络错误收敛为业务错误。 */
    private HttpPayload send(HttpRequest request) {
        try {
            HttpPayload response = limited(request, MAX_CATALOG_BYTES, "workflow.marketplaceResponseInvalid");
            if (response.statusCode() / 100 != 2) throw new BusinessException("workflow.marketplaceUnavailable");
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("workflow.marketplaceUnavailable");
        } catch (Exception exception) {
            throw new BusinessException("workflow.marketplaceUnavailable");
        }
    }

    /** 执行插件包请求并保留重定向状态供调用方校验。 */
    private HttpPayload sendBytes(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            return limited(request, maxPackageBytes, "workflow.marketplacePackageInvalid");
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("workflow.marketplaceUnavailable");
        } catch (Exception exception) {
            throw new BusinessException("workflow.marketplaceUnavailable");
        }
    }

    /** 解析受限大小的 JSON 响应。 */
    private JsonNode json(HttpPayload response) {
        try { return objectMapper.readTree(response.body()); }
        catch (Exception exception) { throw new BusinessException("workflow.marketplaceResponseInvalid"); }
    }

    /** 流式读取至配置上限，多一个字节即终止，避免大响应先完整进入堆内存。 */
    private HttpPayload limited(HttpRequest request, int maximum, String limitError) throws Exception {
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (declared > maximum) {
            try (InputStream ignored = response.body()) { /* 立即关闭超限响应。 */ }
            throw new BusinessException(limitError);
        }
        try (InputStream input = response.body()) {
            byte[] body = input.readNBytes(maximum + 1);
            if (body.length > maximum) throw new BusinessException(limitError);
            return new HttpPayload(response.statusCode(), response.headers(), body);
        }
    }

    /** 返回未过期缓存，否则同步刷新一次。 */
    private SearchResult cached(String key, java.util.function.Supplier<SearchResult> loader) {
        CacheValue<SearchResult> existing = cache.get(key);
        if (existing != null && existing.expiresAt().isAfter(Instant.now())) return existing.value();
        SearchResult loaded = loader.get();
        cache.put(key, new CacheValue<>(loaded, Instant.now().plus(cacheTtl)));
        return loaded;
    }

    /** 对内存列表执行稳定分页。 */
    private SearchResult page(List<MarketplaceEntry> items, int rawPage, int rawPageSize) {
        int page = Math.max(1, rawPage);
        int pageSize = Math.min(50, Math.max(1, rawPageSize));
        int from = Math.min(items.size(), (page - 1) * pageSize);
        int to = Math.min(items.size(), from + pageSize);
        return new SearchResult(List.copyOf(items.subList(from, to)), items.size());
    }

    /** 验证市场根地址只使用 HTTPS。 */
    private URI secureBase(String value, String expectedHost) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || !expectedHost.equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception exception) {
            throw new IllegalStateException("工作流市场地址必须是 HTTPS 根地址", exception);
        }
    }

    /** 仅允许 Dify 市场和其官方 R2 包存储域名。 */
    private boolean allowedDifyDownload(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equalsIgnoreCase(uri.getScheme())
            && (host.equals(difyBase.getHost().toLowerCase(Locale.ROOT)) || host.endsWith(".r2.cloudflarestorage.com"));
    }

    /** 只允许 npm 官方注册表及其子域返回插件压缩包。 */
    private boolean allowedNpmDownload(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equalsIgnoreCase(uri.getScheme())
            && (host.equals(npmRegistryBase.getHost().toLowerCase(Locale.ROOT)) || host.endsWith(".npmjs.org"));
    }

    /** 校验 npm Subresource Integrity 摘要。 */
    private boolean matchesIntegrity(byte[] bytes, String integrity) {
        try {
            String candidate = integrity.trim().split("\\s+", 2)[0];
            int separator = candidate.indexOf('-');
            if (separator <= 0) return false;
            String algorithm = candidate.substring(0, separator).toUpperCase(Locale.ROOT).replace("SHA", "SHA-");
            byte[] expected = Base64.getDecoder().decode(candidate.substring(separator + 1));
            byte[] actual = MessageDigest.getInstance(algorithm).digest(bytes);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    /** 计算传给插件 Worker 的稳定 SHA-256 摘要。 */
    private String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    /** 校验 URL 路径段避免构造外部路径。 */
    private boolean safeSegment(String value) { return value != null && value.matches("[A-Za-z0-9_.-]{1,120}"); }

    /** 校验社区包名，禁止构造任意注册表路径。 */
    private boolean safePackageName(String value) {
        return value != null && value.matches("(?:@[a-z0-9._-]{1,80}/)?n8n-nodes-[a-z0-9._-]{1,100}");
    }

    /** 返回市场发布者或 npm 包作用域。 */
    private String publisher(String id) {
        if (id.startsWith("@") && id.contains("/")) return id.substring(1, id.indexOf('/'));
        int separator = id.indexOf('.');
        return separator > 0 ? id.substring(0, separator) : "n8n";
    }

    /** 读取对象文本字段。 */
    private String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }

    /** 优先使用中文市场文案并回退英文。 */
    private String localized(JsonNode node, String fallback) {
        String zh = node.path("zh_Hans").asText("").trim();
        String en = node.path("en_US").asText("").trim();
        return !zh.isBlank() ? zh : !en.isBlank() ? en : fallback;
    }

    /** 兼容 Dify 文案既可能为字符串也可能为本地化对象。 */
    private String localizedValue(JsonNode node) { return node.isTextual() ? node.asText("") : localized(node, ""); }

    /** 生成大小写无关的搜索文本。 */
    private String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    public record MarketplaceEntry(String externalId, String name, String description, String version,
                                   String publisher, String category, String trustLevel, JsonNode raw) {}
    public record SearchResult(List<MarketplaceEntry> items, long total) {}
    public record PackageDownload(byte[] bytes, String fingerprint) {}
    private record HttpPayload(int statusCode, HttpHeaders headers, byte[] body) {}
    private record CacheValue<T>(T value, Instant expiresAt) {}
}
