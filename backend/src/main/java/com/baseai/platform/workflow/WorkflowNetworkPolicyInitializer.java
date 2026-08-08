package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;

/** 首次升级时把既有连接目标精确导入独立网络白名单，不信任任意 Host 或整个私网。 */
@Component
@Order(10)
public class WorkflowNetworkPolicyInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkflowNetworkPolicyInitializer.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final WorkflowConnectionTargetParser targetParser;
    private final WorkflowNetworkSecurityService securityService;
    private final ApiKeyCidrMatcher cidrMatcher;

    /** 注入连接存储、解密、目标解析与策略服务。 */
    public WorkflowNetworkPolicyInitializer(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                            ObjectMapper objectMapper, ConfigCryptoService cryptoService,
                                            WorkflowConnectionTargetParser targetParser,
                                            WorkflowNetworkSecurityService securityService,
                                            ApiKeyCidrMatcher cidrMatcher) {
        this.jdbcTemplate = jdbcTemplate; this.objectMapper = objectMapper; this.cryptoService = cryptoService;
        this.targetParser = targetParser; this.securityService = securityService; this.cidrMatcher = cidrMatcher;
    }

    /** 仅在独立策略尚未初始化时扫描一次现有未作废连接。 */
    @Override
    public void run(ApplicationArguments arguments) {
        if (securityService.current().initialized()) return;
        LinkedHashSet<ApiTriggerSecurityConfigurationService.HostRule> hosts = new LinkedHashSet<>();
        LinkedHashSet<String> cidrs = new LinkedHashSet<>();
        jdbcTemplate.query("SELECT id,connection_type,config_encrypted FROM workflow_connection WHERE voided=false", rs -> {
            try {
                JsonNode config = objectMapper.readTree(cryptoService.decrypt(rs.getString("config_encrypted")));
                for (WorkflowConnectionTargetParser.Target target : targetParser.parse(rs.getString("connection_type"), config)) {
                    hosts.add(new ApiTriggerSecurityConfigurationService.HostRule("EXACT", target.host()));
                    for (InetAddress address : InetAddress.getAllByName(target.host())) {
                        if (WorkflowNetworkPolicy.restricted(address)) cidrs.add(cidrMatcher.normalize(address.getHostAddress()));
                    }
                }
            } catch (Exception exception) {
                log.warn("Skipped invalid workflow connection target during security policy import: id={}", rs.getLong("id"));
            }
        });
        securityService.initializeImported(List.copyOf(hosts), List.copyOf(cidrs));
        log.info("Initialized workflow connector network policy: hosts={}, cidrs={}", hosts.size(), cidrs.size());
    }
}
