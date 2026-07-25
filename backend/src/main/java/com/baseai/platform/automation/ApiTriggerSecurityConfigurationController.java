package com.baseai.platform.automation;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/automation/api-trigger-security")
public class ApiTriggerSecurityConfigurationController {
    private final ApiTriggerSecurityConfigurationService configurationService;

    public ApiTriggerSecurityConfigurationController(ApiTriggerSecurityConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /** 查询当前生效的接口触发安全配置。 */
    @GetMapping
    @RequiredPermission("automation:api-trigger-security:view")
    public ApiTriggerSecurityConfigurationService.ConfigurationView current() {
        return configurationService.current();
    }

    /** 更新接口触发安全配置并使其立即生效。 */
    @PutMapping
    @RequiredPermission("automation:api-trigger-security:update")
    @TraceType(value = "更新接口触发安全配置", triggerEntry = "MANUAL", captureRequest = false)
    public ApiTriggerSecurityConfigurationService.ConfigurationView update(
        @RequestBody ApiTriggerSecurityConfigurationService.UpdateCommand command) {
        return configurationService.update(command);
    }
}
