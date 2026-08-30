package com.baseai.platform.controller;

import com.baseai.platform.service.TaskTraceService;
import org.springframework.web.bind.annotation.*;

/**
 * 内部追踪控制器
 * <p>
 * 该控制器负责处理来自Python Worker的内部追踪事件，提供任务执行状态同步接口。
 * 所有接口均为内部接口，通过正文绑定的短时 HMAC 签名进行身份验证，确保只有
 * 授权的Worker实例可以上报任务执行状态和心跳信息。
 * </p>
 * <p>
 * 主要功能：
 * <ul>
 *   <li>接收Python Worker的任务状态更新事件</li>
 *   <li>接收Worker实例的心跳信息</li>
 *   <li>验证内部签名并拒绝重放，防止未授权访问</li>
 * </ul>
 * </p>
 *
 * @author BaseAI Platform
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/internal/traces")
public class InternalTraceController {
    /** 任务追踪服务，用于处理任务状态更新 */
    private final TaskTraceService service;

    /**
     * 构造函数 - 通过依赖注入初始化控制器
     *
     * @param service 任务追踪服务实例
     */
    public InternalTraceController(TaskTraceService service) {
        this.service = service;
    }

    /**
     * 接收 Python Worker 的子任务状态与心跳事件
     * <p>
     * 该接口由Python Worker调用，用于上报任务执行过程中的状态变化和心跳信息。
     * 请求需要携带统一内部 HMAC 请求头进行身份验证。
     * </p>
     *
     * @param event Python追踪事件对象，包含任务追踪ID、状态、Worker实例ID和错误信息
     */
    @PostMapping("/python/events")
    public void event(@RequestBody PythonTraceEvent event) {
        // 更新Python任务的追踪信息，包括状态、Worker实例和错误信息
        service.updatePython(event.pythonTraceId(), event.status(), event.workerInstanceId(), event.errorMessage());
    }

    /**
     * Python追踪事件记录类
     * <p>
     * 该记录类封装了Python Worker上报的任务执行事件信息，使用Java 16+的record特性
     * 实现不可变数据传输对象（DTO）。
     * </p>
     *
     * @param pythonTraceId Python任务的追踪ID，用于唯一标识一个Python任务执行实例
     * @param status 任务状态，如：RUNNING（运行中）、COMPLETED（已完成）、FAILED（失败）等
     * @param workerInstanceId Worker实例ID，标识执行该任务的具体Worker实例
     * @param errorMessage 错误信息，当任务执行失败时包含详细的错误描述，成功时为null
     */
    public record PythonTraceEvent(String pythonTraceId, String status, String workerInstanceId, String errorMessage) {}
}
