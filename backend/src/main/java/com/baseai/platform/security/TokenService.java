package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String REVOKED_PREFIX = "base-ai:auth:revoked:";
    private final PlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public TokenService(PlatformProperties properties, ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /** 创建带唯一编号和过期时间的 HS256 登录令牌。 */
    public String createToken(Long userId, String username) {
        Instant expiresAt = Instant.now().plusSeconds(properties.getToken().getExpireMinutes() * 60);
        Map<String, Object> header = Map.of("typ", "JWT", "alg", "HS256");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("username", username);
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("exp", expiresAt.getEpochSecond());
        String content = encode(header) + "." + encode(payload);
        return content + "." + sign(content);
    }

    /** 校验签名、有效期及 Redis 撤销状态。 */
    public TokenClaims parseToken(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.");
            if (parts.length != 3) throw BusinessException.unauthorized("登录状态无效");
            String content = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(sign(content).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
                throw BusinessException.unauthorized("登录状态无效");
            }
            Map<String, Object> payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), new TypeReference<>() {});
            Instant expiresAt = Instant.ofEpochSecond(((Number) payload.get("exp")).longValue());
            String tokenId = String.valueOf(payload.get("jti"));
            if (!expiresAt.isAfter(Instant.now())) throw BusinessException.unauthorized("登录已过期");
            if (Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_PREFIX + tokenId))) {
                throw BusinessException.unauthorized("登录状态已退出");
            }
            return new TokenClaims(((Number) payload.get("userId")).longValue(), String.valueOf(payload.get("username")), tokenId, expiresAt);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unauthorized("登录状态无效");
        }
    }

    /** 将当前令牌加入 Redis 撤销缓存直到自然过期。 */
    public void revoke(String token) {
        TokenClaims claims = parseToken(token);
        Duration ttl = Duration.between(Instant.now(), claims.expiresAt());
        if (!ttl.isNegative() && !ttl.isZero()) redisTemplate.opsForValue().set(REVOKED_PREFIX + claims.tokenId(), "1", ttl);
    }

    /** 将 JSON 内容编码为 URL 安全 Base64。 */
    private String encode(Map<String, Object> value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Token serialization failed", exception);
        }
    }

    /** 使用统一密钥生成 HMAC-SHA256 签名。 */
    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getToken().getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Token signing failed", exception);
        }
    }
}
