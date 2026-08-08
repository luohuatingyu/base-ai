package com.baseai.platform.workflow;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.service.MailManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供节点模板、画布、版本、调试运行和日志管理接口。 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
    private final WorkflowService workflowService;
    private final WorkflowExecutionService executionService;
    private final WorkflowConnectionService connectionService;
    private final WorkflowConnectionTester connectionTester;
    private final LlmManagementService llmManagementService;
    private final MailManagementService mailManagementService;

    /** 注入工作流配置和执行服务。 */
    public WorkflowController(WorkflowService workflowService, WorkflowExecutionService executionService,
                              WorkflowConnectionService connectionService, WorkflowConnectionTester connectionTester,
                              LlmManagementService llmManagementService, MailManagementService mailManagementService) {
        this.workflowService = workflowService;
        this.executionService = executionService;
        this.connectionService = connectionService;
        this.connectionTester = connectionTester;
        this.llmManagementService = llmManagementService;
        this.mailManagementService = mailManagementService;
    }

    /** 查询当前用户可见的脱敏连接配置。 */
    @GetMapping("/connections")
    @RequiredPermission("workflow:connection:list")
    public List<WorkflowModels.ConnectionView> connections() { return connectionService.connections(); }

    /** 创建工作流外部连接。 */
    @PostMapping("/connections")
    @RequiredPermission("workflow:connection:create")
    public WorkflowModels.ConnectionView createConnection(@RequestBody WorkflowModels.ConnectionCommand command) {
        return connectionService.create(command);
    }

    /** 更新当前用户拥有的连接。 */
    @PutMapping("/connections/{id}")
    @RequiredPermission("workflow:connection:update")
    public WorkflowModels.ConnectionView updateConnection(@PathVariable Long id,
                                                           @RequestBody WorkflowModels.ConnectionCommand command) {
        return connectionService.update(id, command);
    }

    /** 软删除未被工作流版本引用的连接。 */
    @DeleteMapping("/connections/{id}")
    @RequiredPermission("workflow:connection:delete")
    public void deleteConnection(@PathVariable Long id) { connectionService.delete(id); }

    /** 测试当前用户拥有的连接并只返回脱敏结果。 */
    @PostMapping("/connections/{id}/test")
    @RequiredPermission("workflow:connection:update")
    public java.util.Map<String, Object> testConnection(@PathVariable Long id) { return connectionTester.test(id); }

    /** 查询可复用节点模板。 */
    @GetMapping("/nodes")
    @RequiredPermission("workflow:node:list")
    public List<WorkflowModels.NodeTemplateView> templates() { return workflowService.templates(); }

    /** 查询 AI 节点可选择的启用模型，不返回供应商密钥或健康错误。 */
    @GetMapping("/model-options")
    @RequiredPermission("workflow:node:list")
    public List<LlmManagementService.WorkflowModelOption> modelOptions() {
        return llmManagementService.workflowModelOptions();
    }

    /** 查询邮件发送节点可选择的启用可发送路由，不返回账户或收件人配置。 */
    @GetMapping("/mail-route-options")
    @RequiredPermission("workflow:node:list")
    public List<MailManagementService.RouteOption> mailRouteOptions() {
        return mailManagementService.workflowRouteOptions();
    }

    /** 查询当前用户节点可选择的启用连接，不返回加密或脱敏连接参数。 */
    @GetMapping("/connection-options")
    @RequiredPermission("workflow:node:list")
    public List<WorkflowConnectionService.ConnectionOption> connectionOptions() {
        return connectionService.connectionOptions();
    }

    /** 创建节点模板。 */
    @PostMapping("/nodes")
    @RequiredPermission("workflow:node:create")
    public WorkflowModels.NodeTemplateView createTemplate(@RequestBody WorkflowModels.NodeTemplateCommand command) {
        return workflowService.createTemplate(command);
    }

    /** 更新节点模板。 */
    @PutMapping("/nodes/{id}")
    @RequiredPermission("workflow:node:update")
    public WorkflowModels.NodeTemplateView updateTemplate(@PathVariable Long id,
                                                           @RequestBody WorkflowModels.NodeTemplateCommand command) {
        return workflowService.updateTemplate(id, command);
    }

    /** 删除用户节点模板。 */
    @DeleteMapping("/nodes/{id}")
    @RequiredPermission("workflow:node:delete")
    public void deleteTemplate(@PathVariable Long id) { workflowService.deleteTemplate(id); }

    /** 查询工作流画布列表。 */
    @GetMapping("/canvases")
    @RequiredPermission("workflow:canvas:list")
    public List<WorkflowModels.WorkflowView> workflows() { return workflowService.workflows(); }

    /** 创建工作流和首个版本。 */
    @PostMapping("/canvases")
    @RequiredPermission("workflow:canvas:create")
    public WorkflowModels.WorkflowView createWorkflow(@RequestBody WorkflowModels.WorkflowCommand command) {
        return workflowService.createWorkflow(command);
    }

    /** 保存新的不可变草稿版本。 */
    @PutMapping("/canvases/{id}")
    @RequiredPermission("workflow:canvas:update")
    public WorkflowModels.WorkflowView updateWorkflow(@PathVariable Long id,
                                                       @RequestBody WorkflowModels.WorkflowCommand command) {
        return workflowService.updateWorkflow(id, command);
    }

    /** 删除工作流定义并保留历史执行。 */
    @DeleteMapping("/canvases/{id}")
    @RequiredPermission("workflow:canvas:delete")
    public void deleteWorkflow(@PathVariable Long id) { workflowService.deleteWorkflow(id); }

    /** 发布当前草稿版本。 */
    @PostMapping("/canvases/{id}/publish")
    @RequiredPermission("workflow:canvas:publish")
    public WorkflowModels.WorkflowView publish(@PathVariable Long id) { return workflowService.publish(id); }

    /** 查询工作流历史版本。 */
    @GetMapping("/canvases/{id}/versions")
    @RequiredPermission("workflow:canvas:list")
    public List<WorkflowModels.VersionView> versions(@PathVariable Long id) { return workflowService.versions(id); }

    /** 异步调试当前草稿版本。 */
    @PostMapping("/canvases/{id}/runs")
    @RequiredPermission("workflow:canvas:execute")
    public ResponseEntity<WorkflowModels.RunAccepted> runDraft(@PathVariable Long id,
                                                               @RequestBody(required = false) WorkflowModels.RunCommand command) {
        return ResponseEntity.accepted().body(executionService.startDraft(id, command == null ? null : command.inputs()));
    }

    /** 查询单个画布最近运行历史。 */
    @GetMapping("/canvases/{id}/runs")
    @RequiredPermission("workflow:canvas:logs")
    public List<WorkflowModels.RunView> runs(@PathVariable Long id) { return executionService.runs(id); }

    /** 查询运行详情和节点日志。 */
    @GetMapping("/runs/{runId}")
    @RequiredPermission("workflow:canvas:logs")
    public WorkflowModels.RunView run(@PathVariable String runId) { return executionService.run(runId); }

    /** 取消排队或运行中的工作流。 */
    @PostMapping("/runs/{runId}/cancel")
    @RequiredPermission("workflow:canvas:execute")
    public WorkflowModels.RunView cancel(@PathVariable String runId) { return executionService.cancel(runId); }
}
