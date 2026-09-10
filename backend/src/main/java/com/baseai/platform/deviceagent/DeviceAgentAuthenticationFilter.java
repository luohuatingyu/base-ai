package com.baseai.platform.deviceagent;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** 使用每 Agent HMAC、正文摘要、时钟窗口和 Redis Nonce 保护设备协议。 */
@Component
public class DeviceAgentAuthenticationFilter extends OncePerRequestFilter {
    public static final String AGENT_ID_ATTRIBUTE = DeviceAgentAuthenticationFilter.class.getName() + ".agentId";
    private static final String PREFIX = "/api/agent/ios-device/v1";
    private static final String PAIRING_PATH = PREFIX + "/pairing/claim";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Duration AUDIT_INTERVAL = Duration.ofSeconds(60);
    private final PlatformProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DeviceAgentRegistrationService registrationService;
    private final ClientIpResolver clientIpResolver;

    /** 注入签名配置、防重放存储、Agent 凭据和客户端地址解析器。 */
    public DeviceAgentAuthenticationFilter(PlatformProperties properties, StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper,
                                           DeviceAgentRegistrationService registrationService,
                                           ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.registrationService = registrationService;
        this.clientIpResolver = clientIpResolver;
    }

    /** 仅保护 Agent 协议，首次配对领取保持一次性凭据认证。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || PAIRING_PATH.equals(uri) || !uri.startsWith(PREFIX);
    }

    /** 缓存正文后执行完整认证，失败时直接生成最小 JSON 响应。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) throw auth(413, "BODY_TOO_LARGE", "Agent request body too large");
            String agentId = header(request, "X-Device-Agent-Id");
            String timestamp = header(request, "X-Device-Agent-Timestamp");
            String nonce = header(request, "X-Device-Agent-Nonce");
            String contentHash = header(request, "X-Device-Agent-Content-SHA256");
            String signature = header(request, "X-Device-Agent-Signature");
            if (agentId == null || agentId.isBlank()) {
                throw auth(401, "MISSING_AGENT_ID", "Invalid Agent credentials");
            }
            String secret = resolveSecret(agentId);
            validateTimestamp(timestamp);
            String actualHash = sha256(body);
            if (!constantEquals(actualHash, contentHash)) {
                throw auth(401, "BAD_CONTENT_HASH", "Invalid Agent signature");
            }
            validateSignature(request, timestamp, nonce, contentHash, signature, secret);
            reserveNonce(agentId, nonce);
            registrationService.touchAuthenticated(agentId);
            CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
            wrapped.setAttribute(AGENT_ID_ATTRIBUTE, agentId);
            chain.doFilter(wrapped, response);
        } catch (AgentAuthException exception) {
            recordFailure(request, exception.reason());
            writeError(response, exception.status(), exception.getMessage());
        } catch (RuntimeException exception) {
            writeError(response, 503, "Agent authentication unavailable");
        }
    }

    /** 从 MySQL 注册表解密当前 Agent 的独立 Secret。 */
    private String resolveSecret(String agentId) {
        String secret = registrationService.secretForAuthentication(agentId);
        if (secret != null && secret.length() >= 32) return secret;
        throw auth(401, "UNKNOWN_AGENT", "Invalid Agent credentials");
    }

    /** 校验 Unix 秒时间戳处于配置时钟偏差内。 */
    private void validateTimestamp(String value) {
        try {
            long timestamp = Long.parseLong(value);
            long skew = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (skew > Math.max(1, properties.getDeviceAgent().getSignatureClockSkewSeconds())) {
                throw new NumberFormatException();
            }
        } catch (Exception exception) {
            throw auth(401, "EXPIRED_TIMESTAMP", "Expired Agent request");
        }
    }

    /** 按稳定换行协议校验方法、路径、摘要、时间戳和 Nonce。 */
    private void validateSignature(HttpServletRequest request, String timestamp, String nonce,
                                   String contentHash, String signature, String secret) {
        if (nonce == null || nonce.isBlank() || nonce.length() > 120 || signature == null) {
            throw auth(401, "BAD_SIGNATURE", "Invalid Agent signature");
        }
        String canonical = request.getMethod() + "\n" + request.getRequestURI() + "\n" + contentHash
            + "\n" + timestamp + "\n" + nonce;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            if (!constantEquals(expected, signature)) {
                throw auth(401, "BAD_SIGNATURE", "Invalid Agent signature");
            }
        } catch (AgentAuthException exception) {
            throw exception;
        } catch (Exception exception) {
            throw auth(503, "AUTH_UNAVAILABLE", "Agent authentication unavailable");
        }
    }

    /** Redis SET NX 保证同 Agent 的同一 Nonce 只接受一次。 */
    private void reserveNonce(String agentId, String nonce) {
        String key = properties.getPlatform().getCode() + ":device-agent-nonce:" + agentId + ":" + nonce;
        Boolean created = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(
            Math.max(60, properties.getDeviceAgent().getNonceTtlSeconds())));
        if (!Boolean.TRUE.equals(created)) throw auth(401, "REPLAYED_NONCE", "Replayed Agent request");
    }

    /** 对同 Agent 和原因的认证失败审计做一分钟节流。 */
    private void recordFailure(HttpServletRequest request, String reason) {
        try {
            String agentId = header(request, "X-Device-Agent-Id");
            if (agentId == null || agentId.isBlank() || agentId.length() > 64) return;
            String key = properties.getPlatform().getCode() + ":device-agent-auth-audit:" + agentId + ":" + reason;
            if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", AUDIT_INTERVAL))) return;
            registrationService.recordAuthFailure(agentId, reason,
                request.getMethod() + " " + request.getRequestURI(), clientIpResolver.resolve(request));
        } catch (RuntimeException ignored) {
            // 排障审计失败不得改变原认证响应。
        }
    }

    /** 读取并去除签名头两端空白。 */
    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? null : value.trim();
    }

    /** 计算小写十六进制 SHA-256。 */
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw auth(503, "AUTH_UNAVAILABLE", "Agent authentication unavailable"); }
    }

    /** 常量时间比较签名和身份字符串。 */
    private boolean constantEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 过滤器异常不经过 ControllerAdvice，直接写入统一形状响应。 */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
            Map.of("success", false, "code", status, "message", message));
    }

    /** 创建携带稳定原因码的认证异常。 */
    private AgentAuthException auth(int status, String reason, String message) {
        return new AgentAuthException(status, reason, message);
    }

    /** 保存原始正文供认证后的 Spring MVC 再次读取。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        /** 防御性复制原始正文。 */
        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        /** 每次调用都返回独立同步输入流。 */
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { /* 同步请求无需监听器。 */ }
                @Override public int read() { return input.read(); }
            };
        }

        /** 使用 UTF-8 返回缓存正文读取器。 */
        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** HMAC 认证失败状态与稳定原因码。 */
    private static final class AgentAuthException extends RuntimeException {
        private final int status;
        private final String reason;

        /** 保存响应状态、审计原因和安全文案。 */
        private AgentAuthException(int status, String reason, String message) {
            super(message);
            this.status = status;
            this.reason = reason;
        }

        /** 返回 HTTP 状态。 */
        private int status() { return status; }
        /** 返回稳定审计原因。 */
        private String reason() { return reason; }
    }
}
