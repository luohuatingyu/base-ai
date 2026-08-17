package com.baseai.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台运行配置属性。
 *
 * <p>将 {@code app} 配置树绑定为类型安全的 Java 对象，供数据库、认证、Worker、
 * 日志和 API 触发器等组件共享。</p>
 */
@ConfigurationProperties(prefix = "app")
public class PlatformProperties {
    /** 对外展示的平台基础信息。 */
    private Platform platform = new Platform();
    /** 接口国际化配置。 */
    private I18n i18n = new I18n();
    private String configEncryptionKey;
    /** 可选的带版本配置密钥集合，格式为 key-id=Base64Key,key-id=Base64Key。 */
    private String configEncryptionKeys;
    /** 新写入密文使用的配置密钥编号。 */
    private String configEncryptionActiveKeyId = "legacy";
    /** MySQL 主数据源配置。 */
    private DatabaseProperties mysqlDatabase = new DatabaseProperties();
    /** PostgreSQL 业务数据源配置。 */
    private DatabaseProperties postgresqlDatabase = new DatabaseProperties();
    /** 认证令牌配置。 */
    private Token token = new Token();
    /** 浏览器会话 Cookie 配置。 */
    private SessionCookie sessionCookie = new SessionCookie();
    /** 初始化种子数据配置。 */
    private Seed seed = new Seed();
    /** Python Worker 连接配置。 */
    private PythonWorker pythonWorker = new PythonWorker();
    /** 追踪日志落库配置。 */
    private TraceLog traceLog = new TraceLog();
    /** 追踪切面配置。 */
    private TraceTracking traceTracking = new TraceTracking();
    /** API 触发器配置。 */
    private ApiTrigger apiTrigger = new ApiTrigger();
    /** 工作流执行安全和资源配置。 */
    private Workflow workflow = new Workflow();
    /** API Key 认证配置。 */
    private ApiKey apiKey = new ApiKey();
    /** HTTP 入口资源限制。 */
    private ResourceLimits resourceLimits = new ResourceLimits();
    /** 可信反向代理配置。 */
    private Proxy proxy = new Proxy();
    /** 登录与密码安全配置。 */
    private LoginSecurity loginSecurity = new LoginSecurity();

    public String getConfigEncryptionKey() { return configEncryptionKey; }
    public void setConfigEncryptionKey(String configEncryptionKey) { this.configEncryptionKey = configEncryptionKey; }
    public String getConfigEncryptionKeys() { return configEncryptionKeys; }
    public void setConfigEncryptionKeys(String configEncryptionKeys) { this.configEncryptionKeys = configEncryptionKeys; }
    public String getConfigEncryptionActiveKeyId() { return configEncryptionActiveKeyId; }
    public void setConfigEncryptionActiveKeyId(String value) { this.configEncryptionActiveKeyId = value; }
    public Platform getPlatform() { return platform; }
    public void setPlatform(Platform platform) { this.platform = platform; }
    /** 返回接口国际化配置。 */
    public I18n getI18n() { return i18n; }
    /** 设置接口国际化配置。 */
    public void setI18n(I18n i18n) { this.i18n = i18n; }

    public DatabaseProperties getMysqlDatabase() { return mysqlDatabase; }
    public void setMysqlDatabase(DatabaseProperties mysqlDatabase) { this.mysqlDatabase = mysqlDatabase; }
    public DatabaseProperties getPostgresqlDatabase() { return postgresqlDatabase; }
    public void setPostgresqlDatabase(DatabaseProperties postgresqlDatabase) { this.postgresqlDatabase = postgresqlDatabase; }
    public Token getToken() { return token; }
    public void setToken(Token token) { this.token = token; }
    /** 返回浏览器会话 Cookie 配置。 */
    public SessionCookie getSessionCookie() { return sessionCookie; }
    /** 设置浏览器会话 Cookie 配置。 */
    public void setSessionCookie(SessionCookie sessionCookie) { this.sessionCookie = sessionCookie; }
    public Seed getSeed() { return seed; }
    public void setSeed(Seed seed) { this.seed = seed; }
    public PythonWorker getPythonWorker() { return pythonWorker; }
    public void setPythonWorker(PythonWorker pythonWorker) { this.pythonWorker = pythonWorker; }
    public TraceLog getTraceLog() { return traceLog; }
    public void setTraceLog(TraceLog traceLog) { this.traceLog = traceLog; }
    public TraceTracking getTraceTracking() { return traceTracking; }
    public void setTraceTracking(TraceTracking traceTracking) { this.traceTracking = traceTracking; }
    public ApiTrigger getApiTrigger() { return apiTrigger; }
    public void setApiTrigger(ApiTrigger apiTrigger) { this.apiTrigger = apiTrigger; }
    /** 读取工作流资源限制。 */
    public Workflow getWorkflow() { return workflow; }
    /** 设置工作流资源限制。 */
    public void setWorkflow(Workflow workflow) { this.workflow = workflow; }
    public ApiKey getApiKey() { return apiKey; }
    public void setApiKey(ApiKey apiKey) { this.apiKey = apiKey; }
    public ResourceLimits getResourceLimits() { return resourceLimits; }
    public void setResourceLimits(ResourceLimits resourceLimits) { this.resourceLimits = resourceLimits; }
    public Proxy getProxy() { return proxy; }
    public void setProxy(Proxy proxy) { this.proxy = proxy; }
    public LoginSecurity getLoginSecurity() { return loginSecurity; }
    public void setLoginSecurity(LoginSecurity loginSecurity) { this.loginSecurity = loginSecurity; }

    public static class Token {
        private String secret;
        private long expireMinutes = 720;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpireMinutes() { return expireMinutes; }
        public void setExpireMinutes(long expireMinutes) { this.expireMinutes = expireMinutes; }
    }

    /** 控制浏览器会话 Cookie 的传输安全属性。 */
    public static class SessionCookie {
        private boolean secure = false;
        /** 返回是否仅允许通过 HTTPS 发送 Cookie。 */
        public boolean isSecure() { return secure; }
        /** 设置是否仅允许通过 HTTPS 发送 Cookie。 */
        public void setSecure(boolean secure) { this.secure = secure; }
    }

    public static class ApiKey {
        private String hashSecret;
        public String getHashSecret() { return hashSecret; }
        public void setHashSecret(String hashSecret) { this.hashSecret = hashSecret; }
    }

    public static class Proxy {
        private java.util.List<String> trustedCidrs = java.util.List.of();
        public java.util.List<String> getTrustedCidrs() { return trustedCidrs; }
        public void setTrustedCidrs(java.util.List<String> trustedCidrs) { this.trustedCidrs = trustedCidrs; }
    }

    public static class LoginSecurity {
        private int accountIpFailures = 5;
        private int ipFailures = 20;
        private int windowMinutes = 5;
        private int blockMinutes = 15;
        private int passwordMinLength = 12;
        public int getAccountIpFailures() { return accountIpFailures; }
        public void setAccountIpFailures(int value) { accountIpFailures = value; }
        public int getIpFailures() { return ipFailures; }
        public void setIpFailures(int value) { ipFailures = value; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int value) { windowMinutes = value; }
        public int getBlockMinutes() { return blockMinutes; }
        public void setBlockMinutes(int value) { blockMinutes = value; }
        public int getPasswordMinLength() { return passwordMinLength; }
        public void setPasswordMinLength(int value) { passwordMinLength = value; }
    }

    public static class ResourceLimits {
        private int requestMaxBytes = 20 * 1024 * 1024;
        public int getRequestMaxBytes() { return requestMaxBytes; }
        public void setRequestMaxBytes(int value) { requestMaxBytes = value; }
    }

    public static class Platform {
        private String code = "ai-platform";
        private String nameEn = "AI Platform";
        private String nameZh = "AI平台";
        private String shortName = "AI";
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getNameEn() { return nameEn; }
        public void setNameEn(String nameEn) { this.nameEn = nameEn; }
        public String getNameZh() { return nameZh; }
        public void setNameZh(String nameZh) { this.nameZh = nameZh; }
        public String getShortName() { return shortName; }
        public void setShortName(String shortName) { this.shortName = shortName; }
    }

    public static class I18n {
        private String defaultLocale = "en-US";
        /** 返回无请求语言时使用的默认 Locale 标签。 */
        public String getDefaultLocale() { return defaultLocale; }
        /** 设置无请求语言时使用的默认 Locale 标签。 */
        public void setDefaultLocale(String defaultLocale) { this.defaultLocale = defaultLocale; }
    }

    public static class Seed {
        private String adminUsername = "admin";
        private String adminPassword;
        /** 是否在应用启动时将已有管理员密码同步为种子密码。 */
        private boolean adminPasswordSyncEnabled = false;
        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
        public boolean isAdminPasswordSyncEnabled() { return adminPasswordSyncEnabled; }
        public void setAdminPasswordSyncEnabled(boolean adminPasswordSyncEnabled) {
            this.adminPasswordSyncEnabled = adminPasswordSyncEnabled;
        }
    }

    public static class PythonWorker {
        private String url;
        private String internalToken;
        private String javaInstanceId = "java-backend-1";
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getInternalToken() { return internalToken; }
        public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
        public String getJavaInstanceId() { return javaInstanceId; }
        public void setJavaInstanceId(String javaInstanceId) { this.javaInstanceId = javaInstanceId; }
    }

    public static class TraceLog {
        private int queueCapacity = 10000;
        private int batchSize = 100;
        private long flushIntervalMs = 500;
        private String persistLevel = "INFO";
        private int retentionDays = 7;
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
        public String getPersistLevel() { return persistLevel; }
        public void setPersistLevel(String persistLevel) { this.persistLevel = persistLevel; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    public static class TraceTracking {
        private java.util.List<String> excludedMethods = java.util.List.of("GET", "OPTIONS");
        private java.util.List<String> excludedPaths = java.util.List.of(
            "/api/auth/**", "/api/open/**", "/api/internal/**", "/api/system/tasks/**", "/api/mail/**",
            "/api/workflow-hooks/**"
        );
        public java.util.List<String> getExcludedMethods() { return excludedMethods; }
        public void setExcludedMethods(java.util.List<String> excludedMethods) { this.excludedMethods = excludedMethods; }
        public java.util.List<String> getExcludedPaths() { return excludedPaths; }
        public void setExcludedPaths(java.util.List<String> excludedPaths) { this.excludedPaths = excludedPaths; }
    }

    public static class ApiTrigger {
        private int schedulerPoolSize = 4;
        private int lockSeconds = 300;
        private int resultMaxLength = 2000;
        private int responseMaxBytes = 2 * 1024 * 1024;
        private int requestBodyMaxBytes = 1024 * 1024;
        private int metadataMaxLength = 64 * 1024;
        private String caddyCaFile = "";
        public int getSchedulerPoolSize() { return schedulerPoolSize; }
        public void setSchedulerPoolSize(int schedulerPoolSize) { this.schedulerPoolSize = schedulerPoolSize; }
        public int getLockSeconds() { return lockSeconds; }
        public void setLockSeconds(int lockSeconds) { this.lockSeconds = lockSeconds; }
        public int getResultMaxLength() { return resultMaxLength; }
        public void setResultMaxLength(int resultMaxLength) { this.resultMaxLength = resultMaxLength; }
        public int getResponseMaxBytes() { return responseMaxBytes; }
        public void setResponseMaxBytes(int value) { responseMaxBytes = value; }
        public int getRequestBodyMaxBytes() { return requestBodyMaxBytes; }
        public void setRequestBodyMaxBytes(int value) { requestBodyMaxBytes = value; }
        public int getMetadataMaxLength() { return metadataMaxLength; }
        public void setMetadataMaxLength(int value) { metadataMaxLength = value; }
        public String getCaddyCaFile() { return caddyCaFile; }
        public void setCaddyCaFile(String caddyCaFile) { this.caddyCaFile = caddyCaFile; }
    }

    /** 限制画布和执行器资源占用，所有循环和 Agent 调用均有硬上限。 */
    public static class Workflow {
        private int executorPoolSize = 4;
        private int executorQueueCapacity = 400;
        private int maxNodes = 100;
        private int maxIterations = 100;
        private int maxAgentSteps = 20;
        private int maxRecursionDepth = 5;
        private int maxPayloadBytes = 1024 * 1024;
        private int maxWaitSeconds = 3600;
        private int maxExecutionSteps = 1000;
        private int maxRunDurationSeconds = 86_400;
        private long maxRunLogBytes = 10L * 1024 * 1024;
        private int leaseSeconds = 60;
        private int webhookMaxBodyBytes = 1024 * 1024;
        private int webhookRateLimitPerMinute = 60;
        private int triggerDeliveryRetentionDays = 30;
        private int runRetentionDays = 30;
        private String marketplaceN8nUrl = "https://n8n.io";
        private String marketplaceN8nApiUrl = "https://api.n8n.io";
        private String marketplaceNpmRegistryUrl = "https://registry.npmjs.org";
        private String marketplaceDifyUrl = "https://marketplace.dify.ai";
        private int marketplaceTimeoutSeconds = 8;
        private int marketplaceCacheSeconds = 300;
        private int marketplaceMaxPackageBytes = 5 * 1024 * 1024;
        private int marketplaceMaxUnpackedBytes = 10 * 1024 * 1024;
        private int marketplaceMaxPackageFiles = 512;
        private String difyPluginWorkerUrl = "http://dify-plugin-worker:8101";
        private String n8nPluginWorkerUrl = "http://n8n-plugin-worker:8102";
        private String difyPluginWorkerInternalToken = "";
        private String n8nPluginWorkerInternalToken = "";
        private String adapterManagerUrl = "http://adapter-manager:8090";
        private String adapterManagerInternalToken = "";
        private int pluginWorkerTimeoutSeconds = 240;
        private int marketplaceProbeConcurrency = 4;
        private int marketplaceProbeQueueCapacity = 100;
        private int marketplaceProbeMaxAttempts = 3;
        private int marketplaceProbeRetentionHours = 168;
        /** 读取执行线程数量。 */
        public int getExecutorPoolSize() { return executorPoolSize; }
        /** 设置执行线程数量。 */
        public void setExecutorPoolSize(int value) { executorPoolSize = value; }
        /** 读取执行等待队列容量。 */
        public int getExecutorQueueCapacity() { return executorQueueCapacity; }
        /** 设置执行等待队列容量。 */
        public void setExecutorQueueCapacity(int value) { executorQueueCapacity = value; }
        /** 读取单层画布节点上限。 */
        public int getMaxNodes() { return maxNodes; }
        /** 设置单层画布节点上限。 */
        public void setMaxNodes(int value) { maxNodes = value; }
        /** 读取迭代和循环次数上限。 */
        public int getMaxIterations() { return maxIterations; }
        /** 设置迭代和循环次数上限。 */
        public void setMaxIterations(int value) { maxIterations = value; }
        /** 读取 Agent 决策步数上限。 */
        public int getMaxAgentSteps() { return maxAgentSteps; }
        /** 设置 Agent 决策步数上限。 */
        public void setMaxAgentSteps(int value) { maxAgentSteps = value; }
        /** 读取子画布和子工作流递归深度上限。 */
        public int getMaxRecursionDepth() { return maxRecursionDepth; }
        /** 设置子画布和子工作流递归深度上限。 */
        public void setMaxRecursionDepth(int value) { maxRecursionDepth = value; }
        /** 读取输入输出负载字节上限。 */
        public int getMaxPayloadBytes() { return maxPayloadBytes; }
        /** 设置输入输出负载字节上限。 */
        public void setMaxPayloadBytes(int value) { maxPayloadBytes = value; }
        /** 读取单个等待节点允许的最长秒数。 */
        public int getMaxWaitSeconds() { return maxWaitSeconds; }
        /** 设置单个等待节点允许的最长秒数。 */
        public void setMaxWaitSeconds(int value) { maxWaitSeconds = value; }
        /** 读取单次运行累计节点执行上限。 */
        public int getMaxExecutionSteps() { return maxExecutionSteps; }
        /** 设置单次运行累计节点执行上限。 */
        public void setMaxExecutionSteps(int value) { maxExecutionSteps = value; }
        /** 读取单次运行从入队到终态允许的最长秒数。 */
        public int getMaxRunDurationSeconds() { return maxRunDurationSeconds; }
        /** 设置单次运行从入队到终态允许的最长秒数。 */
        public void setMaxRunDurationSeconds(int value) { maxRunDurationSeconds = value; }
        /** 读取单次运行节点日志明文累计字节上限。 */
        public long getMaxRunLogBytes() { return maxRunLogBytes; }
        /** 设置单次运行节点日志明文累计字节上限。 */
        public void setMaxRunLogBytes(long value) { maxRunLogBytes = value; }
        /** 读取工作流实例租约秒数。 */
        public int getLeaseSeconds() { return leaseSeconds; }
        /** 设置工作流实例租约秒数。 */
        public void setLeaseSeconds(int value) { leaseSeconds = value; }
        /** 读取公开 Webhook 正文字节上限。 */
        public int getWebhookMaxBodyBytes() { return webhookMaxBodyBytes; }
        /** 设置公开 Webhook 正文字节上限。 */
        public void setWebhookMaxBodyBytes(int value) { webhookMaxBodyBytes = value; }
        /** 读取单个 Webhook 每分钟请求上限。 */
        public int getWebhookRateLimitPerMinute() { return webhookRateLimitPerMinute; }
        /** 设置单个 Webhook 每分钟请求上限。 */
        public void setWebhookRateLimitPerMinute(int value) { webhookRateLimitPerMinute = value; }
        /** 读取触发幂等记录保留天数。 */
        public int getTriggerDeliveryRetentionDays() { return triggerDeliveryRetentionDays; }
        /** 设置触发幂等记录保留天数。 */
        public void setTriggerDeliveryRetentionDays(int value) { triggerDeliveryRetentionDays = value; }
        /** 读取工作流运行及节点日志保留天数。 */
        public int getRunRetentionDays() { return runRetentionDays; }
        /** 设置工作流运行及节点日志保留天数。 */
        public void setRunRetentionDays(int value) { runRetentionDays = value; }
        /** 读取 n8n 官方市场根地址。 */
        public String getMarketplaceN8nUrl() { return marketplaceN8nUrl; }
        /** 设置 n8n 官方市场根地址。 */
        public void setMarketplaceN8nUrl(String value) { marketplaceN8nUrl = value; }
        /** 读取 n8n 认证社区节点 API 根地址。 */
        public String getMarketplaceN8nApiUrl() { return marketplaceN8nApiUrl; }
        /** 设置 n8n 认证社区节点 API 根地址。 */
        public void setMarketplaceN8nApiUrl(String value) { marketplaceN8nApiUrl = value; }
        /** 读取只允许下载 npm 插件包的注册表根地址。 */
        public String getMarketplaceNpmRegistryUrl() { return marketplaceNpmRegistryUrl; }
        /** 设置只允许下载 npm 插件包的注册表根地址。 */
        public void setMarketplaceNpmRegistryUrl(String value) { marketplaceNpmRegistryUrl = value; }
        /** 读取 Dify 官方市场根地址。 */
        public String getMarketplaceDifyUrl() { return marketplaceDifyUrl; }
        /** 设置 Dify 官方市场根地址。 */
        public void setMarketplaceDifyUrl(String value) { marketplaceDifyUrl = value; }
        /** 读取市场请求超时秒数。 */
        public int getMarketplaceTimeoutSeconds() { return marketplaceTimeoutSeconds; }
        /** 设置市场请求超时秒数。 */
        public void setMarketplaceTimeoutSeconds(int value) { marketplaceTimeoutSeconds = value; }
        /** 读取市场目录缓存秒数。 */
        public int getMarketplaceCacheSeconds() { return marketplaceCacheSeconds; }
        /** 设置市场目录缓存秒数。 */
        public void setMarketplaceCacheSeconds(int value) { marketplaceCacheSeconds = value; }
        /** 读取 Dify 插件压缩包字节上限。 */
        public int getMarketplaceMaxPackageBytes() { return marketplaceMaxPackageBytes; }
        /** 设置 Dify 插件压缩包字节上限。 */
        public void setMarketplaceMaxPackageBytes(int value) { marketplaceMaxPackageBytes = value; }
        /** 读取 Dify 插件解压后字节上限。 */
        public int getMarketplaceMaxUnpackedBytes() { return marketplaceMaxUnpackedBytes; }
        /** 设置 Dify 插件解压后字节上限。 */
        public void setMarketplaceMaxUnpackedBytes(int value) { marketplaceMaxUnpackedBytes = value; }
        /** 读取 Dify 插件压缩包文件数量上限。 */
        public int getMarketplaceMaxPackageFiles() { return marketplaceMaxPackageFiles; }
        /** 设置 Dify 插件压缩包文件数量上限。 */
        public void setMarketplaceMaxPackageFiles(int value) { marketplaceMaxPackageFiles = value; }
        /** 读取 Base AI 自研 Dify ABI Worker 地址。 */
        public String getDifyPluginWorkerUrl() { return difyPluginWorkerUrl; }
        /** 设置 Base AI 自研 Dify ABI Worker 地址。 */
        public void setDifyPluginWorkerUrl(String value) { difyPluginWorkerUrl = value; }
        /** 读取 Base AI 自研 n8n ABI Worker 地址。 */
        public String getN8nPluginWorkerUrl() { return n8nPluginWorkerUrl; }
        /** 设置 Base AI 自研 n8n ABI Worker 地址。 */
        public void setN8nPluginWorkerUrl(String value) { n8nPluginWorkerUrl = value; }
        /** 读取 Dify Worker 独立内部鉴权令牌。 */
        public String getDifyPluginWorkerInternalToken() { return difyPluginWorkerInternalToken; }
        /** 设置 Dify Worker 独立内部鉴权令牌。 */
        public void setDifyPluginWorkerInternalToken(String value) { difyPluginWorkerInternalToken = value; }
        /** 读取 n8n Worker 独立内部鉴权令牌。 */
        public String getN8nPluginWorkerInternalToken() { return n8nPluginWorkerInternalToken; }
        /** 设置 n8n Worker 独立内部鉴权令牌。 */
        public void setN8nPluginWorkerInternalToken(String value) { n8nPluginWorkerInternalToken = value; }
        /** 读取按需启停插件 Worker 的隔离控制服务地址。 */
        public String getAdapterManagerUrl() { return adapterManagerUrl; }
        /** 设置按需启停插件 Worker 的隔离控制服务地址。 */
        public void setAdapterManagerUrl(String value) { adapterManagerUrl = value; }
        /** 读取隔离控制服务内部鉴权令牌。 */
        public String getAdapterManagerInternalToken() { return adapterManagerInternalToken; }
        /** 设置隔离控制服务内部鉴权令牌。 */
        public void setAdapterManagerInternalToken(String value) { adapterManagerInternalToken = value; }
        /** 读取插件 Worker 探测与调用硬超时秒数。 */
        public int getPluginWorkerTimeoutSeconds() { return pluginWorkerTimeoutSeconds; }
        /** 设置插件 Worker 探测与调用硬超时秒数。 */
        public void setPluginWorkerTimeoutSeconds(int value) { pluginWorkerTimeoutSeconds = value; }
        /** 读取市场插件后台探测并发数。 */
        public int getMarketplaceProbeConcurrency() { return marketplaceProbeConcurrency; }
        /** 设置市场插件后台探测并发数。 */
        public void setMarketplaceProbeConcurrency(int value) { marketplaceProbeConcurrency = value; }
        /** 读取后台探测线程池的有界等待容量。 */
        public int getMarketplaceProbeQueueCapacity() { return marketplaceProbeQueueCapacity; }
        /** 设置后台探测线程池的有界等待容量。 */
        public void setMarketplaceProbeQueueCapacity(int value) { marketplaceProbeQueueCapacity = value; }
        /** 读取单个固定版本允许的最大探测次数。 */
        public int getMarketplaceProbeMaxAttempts() { return marketplaceProbeMaxAttempts; }
        /** 设置单个固定版本允许的最大探测次数。 */
        public void setMarketplaceProbeMaxAttempts(int value) { marketplaceProbeMaxAttempts = value; }
        /** 读取未安装探测包的缓存保留小时数。 */
        public int getMarketplaceProbeRetentionHours() { return marketplaceProbeRetentionHours; }
        /** 设置未安装探测包的缓存保留小时数。 */
        public void setMarketplaceProbeRetentionHours(int value) { marketplaceProbeRetentionHours = value; }
    }
}
