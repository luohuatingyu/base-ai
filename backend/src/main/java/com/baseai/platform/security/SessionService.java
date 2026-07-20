package com.baseai.platform.security;

import com.baseai.platform.config.PlatformProperties;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class SessionService {
    private final StringRedisTemplate redisTemplate;
    private final TokenService tokenService;
    private final String prefix;

    public SessionService(StringRedisTemplate redisTemplate, TokenService tokenService, PlatformProperties properties) {
        this.redisTemplate = redisTemplate;
        this.tokenService = tokenService;
        this.prefix = properties.getBrand().getCode() + ":auth:";
    }

    /** 注册登录会话并建立用户到 Token 的索引。 */
    public void register(TokenClaims claims, String ipAddress, String userAgent) {
        Duration ttl = Duration.between(Instant.now(), claims.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) return;
        String key = sessionKey(claims.tokenId());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tokenId", claims.tokenId());
        values.put("userId", String.valueOf(claims.userId()));
        values.put("username", claims.username());
        values.put("ipAddress", limit(ipAddress, 64));
        values.put("userAgent", limit(userAgent, 500));
        values.put("loginAt", Instant.now().toString());
        values.put("lastActiveAt", Instant.now().toString());
        values.put("expiresAt", claims.expiresAt().toString());
        redisTemplate.opsForHash().putAll(key, values);
        redisTemplate.expire(key, ttl);
        redisTemplate.opsForSet().add(userKey(claims.userId()), claims.tokenId());
        redisTemplate.expire(userKey(claims.userId()), ttl);
    }

    /** 刷新会话最后活跃时间。 */
    public void touch(TokenClaims claims) {
        String key = sessionKey(claims.tokenId());
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) redisTemplate.opsForHash().put(key, "lastActiveAt", Instant.now().toString());
    }

    /** 注销并删除在线会话索引。 */
    public void remove(TokenClaims claims) {
        redisTemplate.delete(sessionKey(claims.tokenId()));
        redisTemplate.opsForSet().remove(userKey(claims.userId()), claims.tokenId());
    }

    /** 扫描当前平台全部在线会话。 */
    public List<OnlineSession> sessions() {
        List<OnlineSession> result = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(prefix + "session:*").count(200).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
                if (!values.isEmpty()) result.add(toSession(values));
            }
        }
        return result.stream().sorted(Comparator.comparing(OnlineSession::lastActiveAt).reversed()).toList();
    }

    /** 强制撤销指定会话。 */
    public void terminate(String tokenId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(tokenId));
        if (values.isEmpty()) return;
        Long userId = Long.valueOf(String.valueOf(values.get("userId")));
        Instant expiresAt = Instant.parse(String.valueOf(values.get("expiresAt")));
        tokenService.revokeTokenId(tokenId, expiresAt);
        redisTemplate.delete(sessionKey(tokenId));
        redisTemplate.opsForSet().remove(userKey(userId), tokenId);
    }

    /** 强制撤销某用户的全部会话。 */
    public void terminateUser(Long userId) {
        Set<String> tokenIds = redisTemplate.opsForSet().members(userKey(userId));
        if (tokenIds != null) tokenIds.forEach(this::terminate);
        redisTemplate.delete(userKey(userId));
    }

    private OnlineSession toSession(Map<Object, Object> values) {
        return new OnlineSession(text(values, "tokenId"), Long.valueOf(text(values, "userId")), text(values, "username"),
            text(values, "ipAddress"), text(values, "userAgent"), Instant.parse(text(values, "loginAt")),
            Instant.parse(text(values, "lastActiveAt")), Instant.parse(text(values, "expiresAt")));
    }
    private String text(Map<Object, Object> values, String name) { return String.valueOf(values.get(name)); }
    private String sessionKey(String tokenId) { return prefix + "session:" + tokenId; }
    private String userKey(Long userId) { return prefix + "user:" + userId; }
    private String limit(String value, int length) { String safe = value == null ? "" : value; return safe.substring(0, Math.min(length, safe.length())); }

    public record OnlineSession(String tokenId, Long userId, String username, String ipAddress, String userAgent,
                                Instant loginAt, Instant lastActiveAt, Instant expiresAt) {}
}
