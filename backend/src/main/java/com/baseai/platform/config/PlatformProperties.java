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
        private int maxNodes = 100;
        private int maxIterations = 100;
        private int maxAgentSteps = 20;
        private int maxRecursionDepth = 5;
        private int maxPayloadBytes = 1024 * 1024;
        private int maxWaitSeconds = 3600;
        /** 读取执行线程数量。 */
        public int getExecutorPoolSize() { return executorPoolSize; }
        /** 设置执行线程数量。 */
        public void setExecutorPoolSize(int value) { executorPoolSize = value; }
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
    }
}
