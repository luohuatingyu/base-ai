package com.baseai.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class PlatformProperties {
    private String configEncryptionKey;
    private DatabaseProperties systemDatabase = new DatabaseProperties();
    private DatabaseProperties businessDatabase = new DatabaseProperties();
    private Token token = new Token();
    private Seed seed = new Seed();
    private PythonWorker pythonWorker = new PythonWorker();
    private JobLog jobLog = new JobLog();
    private JobTracking jobTracking = new JobTracking();
    private ApiTrigger apiTrigger = new ApiTrigger();

    public String getConfigEncryptionKey() { return configEncryptionKey; }
    public void setConfigEncryptionKey(String configEncryptionKey) { this.configEncryptionKey = configEncryptionKey; }

    public DatabaseProperties getSystemDatabase() { return systemDatabase; }
    public void setSystemDatabase(DatabaseProperties systemDatabase) { this.systemDatabase = systemDatabase; }
    public DatabaseProperties getBusinessDatabase() { return businessDatabase; }
    public void setBusinessDatabase(DatabaseProperties businessDatabase) { this.businessDatabase = businessDatabase; }
    public Token getToken() { return token; }
    public void setToken(Token token) { this.token = token; }
    public Seed getSeed() { return seed; }
    public void setSeed(Seed seed) { this.seed = seed; }
    public PythonWorker getPythonWorker() { return pythonWorker; }
    public void setPythonWorker(PythonWorker pythonWorker) { this.pythonWorker = pythonWorker; }
    public JobLog getJobLog() { return jobLog; }
    public void setJobLog(JobLog jobLog) { this.jobLog = jobLog; }
    public JobTracking getJobTracking() { return jobTracking; }
    public void setJobTracking(JobTracking jobTracking) { this.jobTracking = jobTracking; }
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

    public static class JobLog {
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

    public static class JobTracking {
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
