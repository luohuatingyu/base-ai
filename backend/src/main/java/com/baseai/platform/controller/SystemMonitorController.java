package com.baseai.platform.controller;

import com.baseai.platform.domain.LoginLog;
import com.baseai.platform.domain.OperationLog;
import com.baseai.platform.repository.LoginLogRepository;
import com.baseai.platform.repository.OperationLogRepository;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.security.SessionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import com.baseai.platform.service.PlatformAdminService;
import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemMonitorController {
    private final SessionService sessionService;
    private final OperationLogRepository operationLogRepository;
    private final LoginLogRepository loginLogRepository;

    public SystemMonitorController(SessionService sessionService, OperationLogRepository operationLogRepository, LoginLogRepository loginLogRepository) {
        this.sessionService = sessionService;
        this.operationLogRepository = operationLogRepository;
        this.loginLogRepository = loginLogRepository;
    }

    @GetMapping("/online-sessions") @RequiredPermission("system:session:list")
    public List<SessionService.OnlineSession> sessions() { return sessionService.sessions(); }
    @DeleteMapping("/online-sessions/{tokenId}") @RequiredPermission("system:session:terminate")
    public void terminate(@PathVariable String tokenId) { sessionService.terminate(tokenId); }
    @DeleteMapping("/online-users/{userId}") @RequiredPermission("system:session:terminate")
    public void terminateUser(@PathVariable Long userId) { sessionService.terminateUser(userId); }

    @GetMapping("/operation-logs") @RequiredPermission("system:audit:operation:list")
    public PlatformAdminService.PageResult<OperationLog> operationLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        var paged = operationLogRepository.findAll(PageRequest.of(page - 1, safeSize, Sort.by(Sort.Direction.DESC, "operatedAt")));
        return new PlatformAdminService.PageResult<>(paged.getContent(), paged.getTotalElements(), page, safeSize);
    }
    @GetMapping("/login-logs") @RequiredPermission("system:audit:login:list")
    public PlatformAdminService.PageResult<LoginLog> loginLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        var paged = loginLogRepository.findAll(PageRequest.of(page - 1, safeSize, Sort.by(Sort.Direction.DESC, "loginAt")));
        return new PlatformAdminService.PageResult<>(paged.getContent(), paged.getTotalElements(), page, safeSize);
    }
}
