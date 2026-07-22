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
    private String configEncryptionKey;
    /** MySQL 主数据源配置。 */
    private DatabaseProperties mysqlDatabase = new DatabaseProperties();
    /** PostgreSQL 业务数据源配置。 */
    private DatabaseProperties postgresqlDatabase = new DatabaseProperties();
    /** 认证令牌配置。 */
    private Token token = new Token();
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

    public String getConfigEncryptionKey() { return configEncryptionKey; }
    public void setConfigEncryptionKey(String configEncryptionKey) { this.configEncryptionKey = configEncryptionKey; }
    public Platform getPlatform() { return platform; }
    public void setPlatform(Platform platform) { this.platform = platform; }

    public DatabaseProperties getMysqlDatabase() { return mysqlDatabase; }
    public void setMysqlDatabase(DatabaseProperties mysqlDatabase) { this.mysqlDatabase = mysqlDatabase; }
    public DatabaseProperties getPostgresqlDatabase() { return postgresqlDatabase; }
    public void setPostgresqlDatabase(DatabaseProperties postgresqlDatabase) { this.postgresqlDatabase = postgresqlDatabase; }
    public Token getToken() { return token; }
    public void setToken(Token token) { this.token = token; }
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

    public static class Token {
        private String secret;
        private long expireMinutes = 720;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpireMinutes() { return expireMinutes; }
        public void setExpireMinutes(long expireMinutes) { this.expireMinutes = expireMinutes; }
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

    public static class Seed {
        private String adminUsername = "admin";
        private String adminPassword;
        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
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
            "/api/auth/**", "/api/open/**", "/api/internal/**", "/api/system/tasks/**"
        );
        public java.util.List<String> getExcludedMethods() { return excludedMethods; }
        public void setExcludedMethods(java.util.List<String> excludedMethods) { this.excludedMethods = excludedMethods; }
        public java.util.List<String> getExcludedPaths() { return excludedPaths; }
        public void setExcludedPaths(java.util.List<String> excludedPaths) { this.excludedPaths = excludedPaths; }
    }

    public static class ApiTrigger {
        private java.util.List<String> allowedHosts = java.util.List.of();
        private boolean allowPrivateNetwork;
        private int schedulerPoolSize = 4;
        private int lockSeconds = 300;
        private int resultMaxLength = 2000;
        public java.util.List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(java.util.List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
        public boolean isAllowPrivateNetwork() { return allowPrivateNetwork; }
        public void setAllowPrivateNetwork(boolean allowPrivateNetwork) { this.allowPrivateNetwork = allowPrivateNetwork; }
        public int getSchedulerPoolSize() { return schedulerPoolSize; }
        public void setSchedulerPoolSize(int schedulerPoolSize) { this.schedulerPoolSize = schedulerPoolSize; }
        public int getLockSeconds() { return lockSeconds; }
        public void setLockSeconds(int lockSeconds) { this.lockSeconds = lockSeconds; }
        public int getResultMaxLength() { return resultMaxLength; }
        public void setResultMaxLength(int resultMaxLength) { this.resultMaxLength = resultMaxLength; }
    }
}
