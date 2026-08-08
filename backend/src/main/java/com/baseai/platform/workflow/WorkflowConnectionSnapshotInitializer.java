package com.baseai.platform.workflow;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在网络白名单导入完成后补齐升级前版本的连接安全修订快照。 */
@Component
@Order(20)
public class WorkflowConnectionSnapshotInitializer implements ApplicationRunner {
    private final WorkflowService workflowService;

    /** 注入版本管理服务。 */
    public WorkflowConnectionSnapshotInitializer(WorkflowService workflowService) { this.workflowService = workflowService; }

    /** 幂等执行历史连接快照迁移。 */
    @Override
    public void run(ApplicationArguments arguments) { workflowService.initializeLegacyConnectionSnapshots(); }
}
