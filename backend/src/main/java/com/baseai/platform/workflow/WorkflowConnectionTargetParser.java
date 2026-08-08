package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import io.lettuce.core.RedisURI;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 从各类连接配置中提取需要执行出站校验的稳定 Host 和端口。 */
@Component
public class WorkflowConnectionTargetParser {
    /** 按连接类型解析全部网络目标；AWS 默认 S3 Endpoint 由 SDK 固定管理，无需自定义放行。 */
    public List<Target> parse(String rawType, JsonNode config) {
        String type = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
        if (config == null || !config.isObject()) throw new BusinessException("workflow.connectionInvalid");
        return switch (type) {
            case "MYSQL" -> List.of(jdbc(config.path("url").asText(), "jdbc:mysql:", 3306));
            case "POSTGRESQL" -> List.of(jdbc(config.path("url").asText(), "jdbc:postgresql:", 5432));
            case "REDIS" -> List.of(redis(config.path("uri").asText()));
            case "S3" -> config.path("endpoint").asText("").isBlank() ? List.of()
                : List.of(uri(config.path("endpoint").asText(), List.of("http", "https"), -1));
            case "KAFKA" -> authorities(config.path("bootstrapServers").asText(), 9092);
            case "RABBITMQ" -> List.of(uri(config.path("uri").asText(), List.of("amqp", "amqps"), -1));
            case "WEBHOOK" -> List.of();
            default -> throw new BusinessException("workflow.connectionTypeInvalid");
        };
    }

    /** 解析仅允许指定 JDBC 驱动协议的数据库地址。 */
    private Target jdbc(String value, String prefix, int defaultPort) {
        if (value == null || !value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            throw new BusinessException("workflow.connectionTargetInvalid");
        }
        return uri(value.substring("jdbc:".length()), List.of(prefix.substring(5, prefix.length() - 1)), defaultPort);
    }

    /** 使用 Lettuce 的严格 URI 解析器提取 Redis 地址。 */
    private Target redis(String value) {
        try {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!(normalized.startsWith("redis://") || normalized.startsWith("rediss://"))) {
                throw new BusinessException("workflow.connectionTargetInvalid");
            }
            RedisURI uri = RedisURI.create(value);
            if (uri.getHost() == null) {
                throw new BusinessException("workflow.connectionTargetInvalid");
            }
            return target(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.connectionTargetInvalid"); }
    }

    /** 解析 Kafka 逗号分隔 Broker 列表并要求每项包含有效 Host。 */
    private List<Target> authorities(String value, int defaultPort) {
        if (value == null || value.isBlank()) throw new BusinessException("workflow.connectionTargetInvalid");
        List<Target> targets = new ArrayList<>();
        for (String item : value.split(",")) {
            String authority = item.trim();
            if (authority.isBlank() || authority.contains("/")) throw new BusinessException("workflow.connectionTargetInvalid");
            targets.add(uri("tcp://" + authority, List.of("tcp"), defaultPort));
        }
        return List.copyOf(targets);
    }

    /** 解析绝对 URI 并拒绝片段、空 Host 和非法端口。 */
    private Target uri(String value, List<String> schemes, int defaultPort) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!schemes.contains(scheme) || uri.getHost() == null || uri.getRawFragment() != null || uri.getPort() == 0) {
                throw new BusinessException("workflow.connectionTargetInvalid");
            }
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort > 0 ? defaultPort
                : "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : "amqps".equals(scheme) ? 5671 : 5672;
            return target(uri.getHost(), port);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.connectionTargetInvalid"); }
    }

    /** 规范 Host 并限制 TCP 端口范围。 */
    private Target target(String host, int port) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || port < 1 || port > 65535) throw new BusinessException("workflow.connectionTargetInvalid");
        return new Target(normalized, port);
    }

    public record Target(String host, int port) {}
}
