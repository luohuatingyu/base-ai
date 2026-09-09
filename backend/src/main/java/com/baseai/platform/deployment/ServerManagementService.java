package com.baseai.platform.deployment;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceIgnored;
import com.baseai.platform.trace.TraceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.regex.Pattern;

/** 管理本地主机/SSH 服务器配置，并通过独立 Agent 执行固定部署动作。 */
@Service
public class ServerManagementService {
    private static final Set<String> MODES = Set.of("LOCAL", "SSH");
    private static final Set<String> AUTH_TYPES = Set.of("KEY", "PASSWORD");
    private static final Set<String> ACTIONS = Set.of("DEPLOY", "ROLLBACK");
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:-]{0,253}");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9._-]{0,63}");
    private static final Pattern HOST_KEY_PATTERN = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
    private static final Pattern REVISION_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern AGENT_JOB_PATTERN = Pattern.compile("[a-f0-9]{32}");
    private static final String AGENT_JOB_PREFIX = "agent-job:";
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final TaskTraceService taskTraceService;
    private final ThreadPoolTaskExecutor executor;
    private final RestClient restClient;
    private final String agentUrl;
    private final String agentToken;
    private final ConcurrentHashMap<Long, Future<?>> running = new ConcurrentHashMap<>();

    /** 注入服务器存储、加密、追踪和 Agent 配置。 */
    public ServerManagementService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper, ConfigCryptoService cryptoService,
                                   TaskTraceService taskTraceService,
                                   @Qualifier("deploymentTaskExecutor") ThreadPoolTaskExecutor executor,
                                   @Value("${app.deployment-agent.url:}") String agentUrl,
                                   @Value("${app.deployment-agent.internal-token:}") String agentToken) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.taskTraceService = taskTraceService;
        this.executor = executor;
        this.agentUrl = normalizeAgentUrl(agentUrl);
        this.agentToken = agentToken == null ? "" : agentToken.trim();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(16));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 查询当前用户可见的服务器配置，敏感字段只返回掩码状态。 */
    public List<ServerModels.ServerView> servers() {
        AuthUser user = AuthContext.require();
        String sql = user.roles().contains("ADMIN") ? "SELECT * FROM managed_server WHERE voided=false ORDER BY id DESC" : "SELECT * FROM managed_server WHERE voided=false AND owner_user_id=? ORDER BY id DESC";
        return user.roles().contains("ADMIN") ? jdbcTemplate.query(sql, (rs, row) -> map(rs)) : jdbcTemplate.query(sql, (rs, row) -> map(rs), user.id());
    }

    /** 创建本地或 SSH 服务器配置。 */
    @Transactional
    public ServerModels.ServerView create(ServerModels.ServerCommand command) {
        validate(command, false);
        Long ownerId = AuthContext.require().id();
        jdbcTemplate.update("INSERT INTO managed_server(name,mode,host,port,username,config_encrypted,owner_user_id,enabled) VALUES (?,?,?,?,?,?,?,?)",
            text(command.name()), mode(command.mode()), blank(command.host()), command.port(), blank(command.username()),
            encrypt(configuration(command)), ownerId, !Boolean.FALSE.equals(command.enabled()));
        return server(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    /** 更新当前用户拥有的服务器配置。 */
    @Transactional
    public ServerModels.ServerView update(Long id, ServerModels.ServerCommand command) {
        ServerRecord existing = require(id);
        requireOwner(existing.ownerUserId());
        validate(command, true);
        ObjectNode merged = merge(existing.config(), command);
        validateMergedCredential(command, merged);
        jdbcTemplate.update("UPDATE managed_server SET name=?,mode=?,host=?,port=?,username=?,config_encrypted=?,enabled=?,updated_at=NOW() WHERE id=? AND voided=false",
            text(command.name()), mode(command.mode()), blank(command.host()), command.port(), blank(command.username()), encrypt(merged), !Boolean.FALSE.equals(command.enabled()), id);
        return server(id);
    }

    /** 软删除服务器配置，运行中的部署不得删除。 */
    @Transactional
    public void delete(Long id) {
        ServerRecord existing = require(id);
        requireOwner(existing.ownerUserId());
        if (running.containsKey(id) || hasActiveDeployment(id)) throw new BusinessException("server.running");
        jdbcTemplate.update("UPDATE managed_server SET voided=true,enabled=false,updated_at=NOW() WHERE id=?", id);
    }

    /** 使用 Agent 测试服务器连通性和 Compose 能力。 */
    public Map<String, Object> test(Long id) {
        ServerRecord server = require(id);
        requireOwner(server.ownerUserId());
        requireEnabled(server);
        Map<String, Object> result = callAgent("test", server, "STATUS", "", "");
        String status = agentStatus(result);
        String error = result.get("error") == null ? null : safeText(String.valueOf(result.get("error")), 500);
        jdbcTemplate.update("UPDATE managed_server SET last_test_status=?,last_test_error=?,last_test_at=NOW(),updated_at=NOW() WHERE id=?", status, error, id);
        return result;
    }

    /** 异步执行固定的部署或回滚动作。 */
    @TraceIgnored
    public ServerModels.DeploymentView deploy(Long serverId, ServerModels.DeploymentCommand command) {
        ServerRecord server = require(serverId);
        requireOwner(server.ownerUserId());
        requireEnabled(server);
        validateDeployment(command);
        if ("ROLLBACK".equalsIgnoreCase(command.action()) && !AuthContext.require().hasPermission("server:rollback")) throw BusinessException.forbidden("server.accessForbidden");
        if (running.containsKey(serverId) || hasActiveDeployment(serverId)) throw new BusinessException("server.running");
        String action = text(command.action()).toUpperCase(Locale.ROOT);
        String revision = text(command.revision());
        String traceId = taskTraceService.create(null, server.ownerUserId(), "DEPLOYMENT", "MANUAL", "POST",
            "/api/servers/" + serverId + "/deploy", new TraceSnapshot("{}", "{}"));
        Long runId;
        try {
            runId = createDeploymentRecord(server, traceId, action, revision);
        } catch (RuntimeException exception) {
            taskTraceService.markFailed(traceId, safeText(exception.getMessage(), 1000));
            throw exception;
        }
        ServerModels.DeploymentCommand normalized = new ServerModels.DeploymentCommand(action, revision);
        FutureTask<Void> task = new FutureTask<>(() -> { executeDeployment(runId, traceId, server, normalized); return null; });
        if (running.putIfAbsent(serverId, task) != null) {
            jdbcTemplate.update("UPDATE deployment_run SET status='FAILED',active_slot=NULL,error_message='server.running',finished_at=NOW() WHERE id=?", runId);
            taskTraceService.markFailed(traceId, "server.running");
            throw new BusinessException("server.running");
        }
        try {
            executor.execute(task);
        } catch (RuntimeException exception) {
            running.remove(serverId, task);
            String error = safeText(exception.getMessage() == null ? "deployment queue unavailable" : exception.getMessage(), 1000);
            jdbcTemplate.update("UPDATE deployment_run SET status='FAILED',active_slot=NULL,error_message=?,finished_at=NOW() WHERE id=?", error, runId);
            taskTraceService.markFailed(traceId, error);
            throw exception;
        }
        return deployment(runId);
    }

    /** 查询部署详情。 */
    public ServerModels.DeploymentView deployment(Long id) {
        DeploymentRecord deployment = requireDeployment(id);
        requireOwner(deployment.ownerUserId());
        return deploymentView(id);
    }

    /** 查询服务器历史部署。 */
    public List<ServerModels.DeploymentView> deployments(Long serverId) {
        ServerRecord server = require(serverId);
        requireOwner(server.ownerUserId());
        return jdbcTemplate.query("SELECT * FROM deployment_run WHERE server_id=? ORDER BY id DESC", (rs, row) -> mapDeployment(rs), serverId);
    }

    /** 在 Agent 中执行部署并维护部署状态和任务追踪。 */
    private void executeDeployment(Long runId, String traceId, ServerRecord server, ServerModels.DeploymentCommand command) {
        try {
            String jobId = traceId.replace("-", "").toLowerCase(Locale.ROOT);
            jdbcTemplate.update("UPDATE deployment_run SET output_summary=? WHERE id=? AND status='RUNNING'", AGENT_JOB_PREFIX + jobId, runId);
            Map<String, Object> result = callAgent("execute", server, command.action(), command.revision(), jobId);
            String status = text(String.valueOf(result.getOrDefault("status", "FAILED"))).toUpperCase(Locale.ROOT);
            if ("RUNNING".equals(status)) requireAgentJobId(result, jobId);
            else if (!"UNKNOWN".equals(status)) completeDeployment(runId, traceId, result);
        } catch (Exception exception) {
            String error = safeText(exception.getMessage() == null ? "server.deployFailed" : exception.getMessage(), 1000);
            failDeployment(runId, traceId, error);
        } finally {
            running.remove(server.id());
        }
    }

    /** 定期续查 Agent 任务，使本地部署重启 Backend 后仍能回收最终结果。 */
    @Scheduled(fixedDelay = 5000)
    public void reconcileAgentJobs() {
        List<PendingDeployment> pending = jdbcTemplate.query("""
            SELECT id,trace_id,output_summary,started_at FROM deployment_run
            WHERE status='RUNNING' AND output_summary LIKE 'agent-job:%' ORDER BY id LIMIT 50
            """, (rs, row) -> new PendingDeployment(rs.getLong("id"), rs.getString("trace_id"),
                rs.getString("output_summary").substring(AGENT_JOB_PREFIX.length()), timestamp(rs, "started_at")));
        for (PendingDeployment deployment : pending) {
            try {
                if (deployment.startedAt() == null || deployment.startedAt().isBefore(LocalDateTime.now().minusMinutes(16))) {
                    failDeployment(deployment.id(), deployment.traceId(), "server.deployTimeout");
                    continue;
                }
                Map<String, Object> result = callAgentJob(deployment.jobId());
                if (result == null) continue;
                String status = text(String.valueOf(result.getOrDefault("status", ""))).toUpperCase(Locale.ROOT);
                if (Set.of("SUCCEEDED", "FAILED").contains(status)) completeDeployment(deployment.id(), deployment.traceId(), result);
            } catch (RuntimeException ignored) {
                // 单个 Agent 任务暂时不可用时保留运行状态，等待下一轮或超时回收。
            }
        }
    }

    /** 将 Agent 结束状态写回部署记录并仅由成功更新状态的实例完成追踪。 */
    private void completeDeployment(Long runId, String traceId, Map<String, Object> result) {
        String status = text(String.valueOf(result.getOrDefault("status", "FAILED"))).toUpperCase(Locale.ROOT);
        if ("SUCCEEDED".equals(status)) {
            int updated = jdbcTemplate.update("UPDATE deployment_run SET status='SUCCEEDED',active_slot=NULL,output_summary=?,error_message=NULL,finished_at=NOW() WHERE id=? AND status='RUNNING'",
                safeText(String.valueOf(result.getOrDefault("output", "")), 2000), runId);
            if (updated > 0) taskTraceService.markSuccess(traceId);
            return;
        }
        failDeployment(runId, traceId, safeText(String.valueOf(result.getOrDefault("error", "server.deployFailed")), 1000));
    }

    /** 以幂等方式结束失败任务并释放服务器并发槽。 */
    private void failDeployment(Long runId, String traceId, String error) {
        int updated = jdbcTemplate.update("UPDATE deployment_run SET status='FAILED',active_slot=NULL,error_message=?,finished_at=NOW() WHERE id=? AND status='RUNNING'",
            safeText(error, 1000), runId);
        if (updated > 0) taskTraceService.markFailed(traceId, error);
    }

    /** 通过内部 Agent 执行固定请求，不把数据库密钥传给 Agent。 */
    private Map<String, Object> callAgent(String path, ServerRecord server, String action, String revision, String jobId) {
        if (agentUrl.isBlank() || agentToken.isBlank()) return Map.of("status", "FAILED", "error", "server.agentNotConfigured");
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("mode", server.mode()); payload.put("host", value(server.config(), "host")); payload.put("port", number(server.config(), "port", 22));
            payload.put("username", value(server.config(), "username")); payload.put("authType", value(server.config(), "authType"));
            payload.put("privateKey", value(server.config(), "privateKey")); payload.put("password", value(server.config(), "password"));
            payload.put("passphrase", value(server.config(), "passphrase")); payload.put("hostKey", value(server.config(), "hostKey"));
            payload.put("workingDir", value(server.config(), "workingDir")); payload.put("composeFile", value(server.config(), "composeFile"));
            payload.put("action", action); payload.put("revision", revision); payload.put("jobId", jobId);
            Map<String, Object> result = restClient.post().uri(agentUrl + "/" + path)
                .header("Authorization", "Bearer " + agentToken).body(payload).retrieve()
                .body(new ParameterizedTypeReference<>() { });
            return result == null ? Map.of("status", "FAILED", "error", "server.agentInvalidResponse") : result;
        } catch (Exception exception) {
            if ("execute".equals(path)) return Map.of("status", "UNKNOWN", "jobId", jobId);
            return Map.of("status", "FAILED", "error", safeText(exception.getMessage() == null ? "server.testFailed" : exception.getMessage(), 500));
        }
    }

    /** 查询 Agent 中已启动任务；网络短暂不可用时返回空值等待下一轮。 */
    private Map<String, Object> callAgentJob(String jobId) {
        if (agentUrl.isBlank() || agentToken.isBlank() || !AGENT_JOB_PATTERN.matcher(text(jobId)).matches()) return null;
        try {
            return restClient.get().uri(agentUrl + "/jobs/" + jobId)
                .header("Authorization", "Bearer " + agentToken).retrieve()
                .body(new ParameterizedTypeReference<>() { });
        } catch (Exception exception) {
            return null;
        }
    }

    /** 验证 Agent 接受结果仍对应 Backend 已持久化的任务编号。 */
    String requireAgentJobId(Map<String, Object> result, String expected) {
        String jobId = text(String.valueOf(result.getOrDefault("jobId", ""))).toLowerCase(Locale.ROOT);
        if (!AGENT_JOB_PATTERN.matcher(jobId).matches() || !jobId.equals(expected)) {
            throw new BusinessException("server.agentInvalidResponse");
        }
        return jobId;
    }

    /** 验证服务器字段和 SSH 安全配置。 */
    private void validate(ServerModels.ServerCommand command, boolean update) {
        if (command == null || text(command.name()).isBlank() || text(command.name()).length() > 120) {
            throw new BusinessException("server.nameRequired");
        }
        String mode = mode(command.mode());
        if ("SSH".equals(mode)) {
            if (!HOST_PATTERN.matcher(text(command.host())).matches() || command.port() == null
                || command.port() < 1 || command.port() > 65535
                || !USERNAME_PATTERN.matcher(text(command.username())).matches()) {
                throw new BusinessException("server.sshRequired");
            }
            String auth = authType(command.authType());
            if (!update) {
                validateHostKey(command.hostKey());
                validateCredential(auth, command.privateKey(), command.password());
            } else if (!text(command.hostKey()).isBlank() && !"******".equals(text(command.hostKey()))) {
                validateHostKey(command.hostKey());
            }
        }
        validateWorkingDir(command.workingDir());
        if ("LOCAL".equals(mode) && !"/workspace".equals(text(command.workingDir()))) throw new BusinessException("server.invalid");
        if (!Set.of("docker-compose.yml", "compose.yml").contains(text(command.composeFile()))) throw new BusinessException("server.invalid");
        if (text(command.privateKey()).length() > 65536 || text(command.password()).length() > 1024
            || text(command.passphrase()).length() > 1024) throw new BusinessException("server.invalid");
    }

    /** 更新时基于合并后的密文配置验证实际认证凭据。 */
    void validateMergedCredential(ServerModels.ServerCommand command, JsonNode merged) {
        if (!"SSH".equals(mode(command.mode()))) return;
        validateHostKey(value(merged, "hostKey"));
        validateCredential(authType(value(merged, "authType")), value(merged, "privateKey"), value(merged, "password"));
    }

    /** Host Key 必须是完整 SHA-256 指纹，禁止子串匹配。 */
    private void validateHostKey(String hostKey) {
        if (!HOST_KEY_PATTERN.matcher(text(hostKey)).matches()) throw new BusinessException("server.hostKeyRequired");
    }

    /** SSH 认证方式必须具有对应凭据。 */
    private void validateCredential(String authType, String privateKey, String password) {
        if ("KEY".equals(authType) && text(privateKey).isBlank()) throw new BusinessException("server.privateKeyRequired");
        if ("PASSWORD".equals(authType) && text(password).isBlank()) throw new BusinessException("server.passwordRequired");
    }

    /** Compose 工作目录必须是规范化绝对路径且不包含 Shell 字符。 */
    private void validateWorkingDir(String workingDir) {
        String value = text(workingDir);
        if (!value.matches("/[A-Za-z0-9_./-]{1,200}")) throw new BusinessException("server.invalid");
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.normalize().toString().equals(value)) throw new BusinessException("server.invalid");
        } catch (InvalidPathException exception) {
            throw new BusinessException("server.invalid");
        }
    }

    /** 验证部署动作和不可变发布版本格式。 */
    void validateDeployment(ServerModels.DeploymentCommand command) {
        if (command == null || !ACTIONS.contains(text(command.action()).toUpperCase(Locale.ROOT))
            || !REVISION_PATTERN.matcher(text(command.revision())).matches()) {
            throw new BusinessException("server.revisionInvalid");
        }
    }
    /** 读取服务器记录。 */
    private ServerRecord require(Long id) {
        List<ServerRecord> rows = jdbcTemplate.query("SELECT * FROM managed_server WHERE id=? AND voided=false",
            (rs, row) -> mapRecord(rs), id);
        if (rows.isEmpty()) throw BusinessException.notFound("server.notFound");
        return rows.get(0);
    }
    /** 读取部署记录。 */
    private DeploymentRecord requireDeployment(Long id) {
        List<DeploymentRecord> rows = jdbcTemplate.query("SELECT * FROM deployment_run WHERE id=?",
            (rs, row) -> new DeploymentRecord(rs.getLong("id"), rs.getLong("server_id"), rs.getLong("owner_user_id")), id);
        if (rows.isEmpty()) throw BusinessException.notFound("server.notFound");
        return rows.get(0);
    }
    /** 查询服务器是否存在运行中的部署。 */
    private boolean hasActiveDeployment(Long serverId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deployment_run WHERE server_id=? AND status='RUNNING'",
            Integer.class, serverId);
        return count != null && count > 0;
    }
    /** 创建部署记录并直接读取当前 INSERT 的生成键。 */
    private Long createDeploymentRecord(ServerRecord server, String traceId, String action, String revision) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO deployment_run(server_id,owner_user_id,trace_id,action,revision,status,active_slot)
                    VALUES (?,?,?,?,?,'RUNNING',1)
                    """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, server.id());
                statement.setLong(2, server.ownerUserId());
                statement.setString(3, traceId);
                statement.setString(4, action);
                statement.setString(5, revision);
                return statement;
            }, keyHolder);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "server.running");
        }
        Number key = keyHolder.getKey();
        if (key == null) throw new BusinessException("server.deployFailed");
        return key.longValue();
    }
    /** 禁用的服务器配置不得测试或部署。 */
    private void requireEnabled(ServerRecord server) {
        if (!server.enabled()) throw new BusinessException("server.disabled");
    }
    /** 校验资源所有者。 */
    private void requireOwner(Long ownerId) { AuthUser user = AuthContext.require(); if (!user.roles().contains("ADMIN") && !user.id().equals(ownerId)) throw BusinessException.forbidden("server.accessForbidden"); }
    /** 映射脱敏服务器视图。 */
    private ServerModels.ServerView map(ResultSet rs) throws SQLException { ServerRecord record = mapRecord(rs); JsonNode config = record.config(); return new ServerModels.ServerView(record.id(), record.name(), record.mode(), rs.getString("host"), rs.getObject("port", Integer.class), rs.getString("username"), value(config, "authType"), mask(value(config, "hostKey")), value(config, "workingDir"), value(config, "composeFile"), record.enabled(), rs.getString("last_test_status"), rs.getString("last_test_error"), timestamp(rs, "last_test_at"), record.ownerUserId(), timestamp(rs, "created_at"), timestamp(rs, "updated_at")); }
    /** 映射内部服务器记录并解密配置。 */
    private ServerRecord mapRecord(ResultSet rs) throws SQLException { return new ServerRecord(rs.getLong("id"), rs.getString("name"), rs.getString("mode"), rs.getLong("owner_user_id"), rs.getBoolean("enabled"), decrypt(rs.getString("config_encrypted"))); }
    /** 查询服务器视图。 */
    private ServerModels.ServerView server(Long id) { return jdbcTemplate.query("SELECT * FROM managed_server WHERE id=? AND voided=false", (rs, row) -> map(rs), id).stream().findFirst().orElseThrow(() -> BusinessException.notFound("server.notFound")); }
    /** 查询部署视图。 */
    private ServerModels.DeploymentView deploymentView(Long id) { return jdbcTemplate.query("SELECT * FROM deployment_run WHERE id=?", (rs, row) -> mapDeployment(rs), id).stream().findFirst().orElseThrow(() -> BusinessException.notFound("server.notFound")); }
    /** 映射部署记录。 */
    private ServerModels.DeploymentView mapDeployment(ResultSet rs) throws SQLException { return new ServerModels.DeploymentView(rs.getLong("id"), rs.getLong("server_id"), rs.getString("trace_id"), rs.getString("action"), rs.getString("revision"), rs.getString("status"), rs.getString("output_summary"), rs.getString("error_message"), timestamp(rs, "started_at"), timestamp(rs, "finished_at")); }
    /** 加密服务器 JSON 配置。 */
    private String encrypt(JsonNode config) { try { return cryptoService.encrypt(objectMapper.writeValueAsString(config)); } catch (Exception exception) { throw new BusinessException("server.invalid"); } }
    /** 将非敏感控制字段规范化后写入加密配置。 */
    private ObjectNode configuration(ServerModels.ServerCommand command) {
        ObjectNode object = objectMapper.valueToTree(command);
        object.put("mode", mode(command.mode()));
        object.put("host", text(command.host()));
        object.put("username", text(command.username()));
        object.put("authType", text(command.authType()).toUpperCase(Locale.ROOT));
        object.put("hostKey", text(command.hostKey()));
        object.put("workingDir", text(command.workingDir()));
        object.put("composeFile", text(command.composeFile()));
        return object;
    }
    /** 更新时保留未重新输入的脱敏凭据。 */
    ObjectNode merge(JsonNode old, ServerModels.ServerCommand command) {
        ObjectNode object = configuration(command);
        if ("LOCAL".equals(mode(command.mode()))) {
            for (String field : List.of("privateKey", "password", "passphrase", "hostKey")) object.put(field, "");
            return object;
        }
        boolean sameAuthType = value(old, "authType").equalsIgnoreCase(value(object, "authType"));
        List<String> preserved = sameAuthType ? List.of("privateKey", "password", "passphrase", "hostKey") : List.of("hostKey");
        for (String field : preserved) {
            if ("******".equals(object.path(field).asText())
                || object.path(field).asText().isBlank() && !old.path(field).asText().isBlank()) {
                object.set(field, old.path(field));
            }
        }
        return object;
    }
    /** 解密服务器配置。 */
    private JsonNode decrypt(String value) { try { return objectMapper.readTree(cryptoService.decrypt(value)); } catch (Exception exception) { throw new BusinessException("server.invalid"); } }
    /** 读取 JSON 文本。 */
    private String value(JsonNode node, String key) { return node.path(key).asText(""); }
    /** 读取 JSON 数字。 */
    private int number(JsonNode node, String key, int fallback) { return node.path(key).asInt(fallback); }
    /** 规范 Agent 返回状态。 */
    private String agentStatus(Map<String, Object> result) { return "SUCCEEDED".equals(result.get("status")) ? "SUCCEEDED" : "FAILED"; }
    /** 返回敏感字段掩码。 */
    private String mask(String value) { return value == null || value.isBlank() ? "" : "******"; }
    /** 规范服务器模式。 */
    private String mode(String value) { String normalized = text(value).toUpperCase(Locale.ROOT); if (!MODES.contains(normalized)) throw new BusinessException("server.modeInvalid"); return normalized; }
    /** 规范认证类型。 */
    private String authType(String value) { String normalized = text(value).toUpperCase(Locale.ROOT); if (!AUTH_TYPES.contains(normalized)) throw new BusinessException("server.invalid"); return normalized; }
    /** 规范 Agent 地址并移除尾部斜线。 */
    private String normalizeAgentUrl(String value) { return text(value).replaceFirst("/+$", ""); }
    /** 规范文本。 */
    private String text(String value) { return value == null ? "" : value.trim(); }
    /** 空文本转 NULL。 */
    private String blank(String value) { String normalized = text(value); return normalized.isBlank() ? null : normalized; }
    /** 截断外部错误。 */
    private String truncate(String value, int max) { String normalized = text(value); return normalized.length() <= max ? normalized : normalized.substring(0, max); }
    /** 截断并清除错误中的常见凭据片段。 */
    private String safeText(String value, int max) { return truncate(text(value).replaceAll("(?i)(password|secret|token|passphrase)(?:=|\\\"\\s*:\\s*\\\")[^\\s,}\"]+", "$1=******"), max); }
    /** 转换 SQL 时间。 */
    private LocalDateTime timestamp(ResultSet rs, String name) throws SQLException { java.sql.Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toLocalDateTime(); }
    private record ServerRecord(Long id, String name, String mode, Long ownerUserId, boolean enabled, JsonNode config) { }
    private record DeploymentRecord(Long id, Long serverId, Long ownerUserId) { }
    private record PendingDeployment(Long id, String traceId, String jobId, LocalDateTime startedAt) { }
}
