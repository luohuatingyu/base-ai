package com.baseai.platform.datasync;

import com.baseai.platform.deployment.ServerModels;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceIgnored;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供数据同步计划、预检、执行和运行记录接口。 */
@RestController
@RequestMapping("/api/data-sync")
public class DataSyncController {
    private final DataSyncService service;

    /** 注入数据同步服务。 */
    public DataSyncController(DataSyncService service) { this.service = service; }

    /** 查询同步计划。 */
    @GetMapping("/plans")
    @RequiredPermission("operations:data-sync:list")
    public List<DataSyncModels.PlanView> plans() { return service.plans(); }

    /** 查询数据同步可用连接。 */
    @GetMapping("/connections")
    @RequiredPermission("operations:data-sync:list")
    public List<DataSyncModels.ConnectionOption> connections() { return service.connections(); }

    /** 查询当前用户可用于执行数据同步的服务器。 */
    @GetMapping("/servers")
    @RequiredPermission("operations:data-sync:list")
    public List<ServerModels.DataSyncServerOption> servers() { return service.servers(); }

    /** 创建同步计划。 */
    @PostMapping("/plans")
    @RequiredPermission("operations:data-sync:create")
    public DataSyncModels.PlanView create(@RequestBody DataSyncModels.PlanCommand command) { return service.create(command); }

    /** 更新同步计划。 */
    @PutMapping("/plans/{id}")
    @RequiredPermission("operations:data-sync:update")
    public DataSyncModels.PlanView update(@PathVariable Long id, @RequestBody DataSyncModels.PlanCommand command) { return service.update(id, command); }

    /** 删除同步计划。 */
    @DeleteMapping("/plans/{id}")
    @RequiredPermission("operations:data-sync:delete")
    public void delete(@PathVariable Long id) { service.delete(id); }

    /** 查询数据库表元数据。 */
    @GetMapping("/connections/{connectionId}/tables")
    @RequiredPermission("operations:data-sync:preview")
    public List<DataSyncModels.TableView> tables(@PathVariable Long connectionId,
                                                 @RequestParam(defaultValue = "") String schema,
                                                 @RequestParam(required = false) Long serverId) {
        return service.tables(connectionId, schema, serverId);
    }

    /** 预览源目标表结构和行数。 */
    @PostMapping("/preview")
    @RequiredPermission("operations:data-sync:preview")
    public DataSyncModels.PreviewView preview(@RequestBody DataSyncModels.PreviewCommand command) { return service.preview(command); }

    /** 异步启动同步运行。 */
    @PostMapping("/plans/{id}/run")
    @RequiredPermission("operations:data-sync:run")
    @TraceIgnored
    public DataSyncModels.RunView run(@PathVariable Long id) { return service.run(id); }

    /** 查询运行记录。 */
    @GetMapping("/runs/{id}")
    @RequiredPermission("operations:data-sync:logs")
    public DataSyncModels.RunView runDetail(@PathVariable Long id) { return service.runDetail(id); }

    /** 取消运行。 */
    @PostMapping("/runs/{id}/cancel")
    @RequiredPermission("operations:data-sync:cancel")
    public DataSyncModels.RunView cancel(@PathVariable Long id) { return service.cancel(id); }

    /** 重新执行已失败或取消的同步运行。 */
    @PostMapping("/runs/{id}/retry")
    @RequiredPermission("operations:data-sync:run")
    @TraceIgnored
    public DataSyncModels.RunView retry(@PathVariable Long id) { return service.retry(id); }
}
