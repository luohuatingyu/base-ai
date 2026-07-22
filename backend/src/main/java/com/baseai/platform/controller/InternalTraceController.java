package com.baseai.platform.controller;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.service.TaskTraceService;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部追踪控制器
 * <p>
 * 该控制器负责处理来自Python Worker的内部追踪事件，提供任务执行状态同步接口。
 * 所有接口均为内部接口，通过共享令牌（Internal Token）进行身份验证，确保只有
 * 授权的Worker实例可以上报任务执行状态和心跳信息。
 * </p>
 * <p>
 * 主要功能：
 * <ul>
 *   <li>接收Python Worker的任务状态更新事件</li>
 *   <li>接收Worker实例的心跳信息</li>
 *   <li>验证内部令牌，防止未授权访问</li>
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

    /** 平台配置属性，包含内部令牌等安全配置 */
    private final PlatformProperties properties;

    /**
     * 构造函数 - 通过依赖注入初始化控制器
     *
     * @param service 任务追踪服务实例
     * @param properties 平台配置属性实例
     */
    public InternalTraceController(TaskTraceService service, PlatformProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * 接收 Python Worker 的子任务状态与心跳事件
     * <p>
     * 该接口由Python Worker调用，用于上报任务执行过程中的状态变化和心跳信息。
     * 请求需要携带内部令牌（X-Internal-Token）进行身份验证。
     * </p>
     *
     * @param token 内部令牌，通过HTTP Header "X-Internal-Token" 传递，用于验证请求来源的合法性
     * @param event Python追踪事件对象，包含任务追踪ID、状态、Worker实例ID和错误信息
     * @throws BusinessException 当内部令牌验证失败时抛出未授权异常
     */
    @PostMapping("/python/events")
    public void event(@RequestHeader(value = "X-Internal-Token", required = false) String token, @RequestBody PythonTraceEvent event) {
        // 验证内部令牌的有效性
        verify(token);
        // 更新Python任务的追踪信息，包括状态、Worker实例和错误信息
        service.updatePython(event.pythonTraceId(), event.status(), event.workerInstanceId(), event.errorMessage());
    }

    /**
     * 校验 Java 后端与 Python Worker 之间的共享内部令牌
     * <p>
     * 该方法使用常量时间比较算法（MessageDigest.isEqual）来验证令牌，
     * 防止时序攻击（Timing Attack）。如果令牌为空或不匹配，则抛出未授权异常。
     * </p>
     *
     * @param token 待验证的令牌字符串，来自HTTP请求头
     * @throws BusinessException 当令牌为null或与预期值不匹配时，抛出未授权异常
     */
    private void verify(String token) {
        // 从平台配置中获取预期的内部令牌
        String expected = properties.getPythonWorker().getInternalToken();
        // 使用常量时间比较防止时序攻击，同时检查令牌是否为空
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.unauthorized("内部令牌无效");
        }
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
