package com.baseai.platform.deployment;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceIgnored;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 通过登录及 CSRF 保护的接口签发一次性终端票据。 */
@RestController
@RequestMapping("/api/servers")
public class ServerTerminalController {
    private final ServerTerminalService terminals;

    /** 注入终端会话服务。 */
    public ServerTerminalController(ServerTerminalService terminals) { this.terminals = terminals; }

    /** 票据不进入请求追踪，且不携带 SSH 凭据。 */
    @PostMapping("/{id}/terminal")
    @RequiredPermission("operations:server:shell")
    @TraceIgnored
    public Map<String, String> create(@PathVariable Long id) { return Map.of("ticket", terminals.issue(id)); }
}
