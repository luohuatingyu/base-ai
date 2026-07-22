package com.baseai.platform.service;

import com.baseai.platform.logging.SystemAuditAsyncWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 登录审计服务
 *
 * <p>负责记录用户登录行为的审计日志，包括登录成功和失败的情况。
 * 采用异步非阻塞方式写入日志，确保日志记录不会影响用户登录认证流程的性能。
 *
 * <p>主要功能：
 * <ul>
 *   <li>异步记录用户登录成功事件</li>
 *   <li>异步记录用户登录失败事件</li>
 *   <li>记录登录相关元数据（IP地址、User-Agent等）</li>
 *   <li>处理日志写入异常，避免影响主业务流程</li>
 * </ul>
 *
 * @author baseai
 * @since 1.0
 */
@Service
public class LoginAuditService {
    private static final Logger log = LoggerFactory.getLogger(LoginAuditService.class);

    /**
     * 系统审计日志异步写入器
     */
    private final SystemAuditAsyncWriter writer;

    /**
     * 构造函数，通过依赖注入初始化审计日志写入器
     *
     * @param writer 系统审计日志异步写入器实例
     */
    public LoginAuditService(SystemAuditAsyncWriter writer) { this.writer = writer; }

    /**
     * 保存用户登录审计日志
     *
     * <p>以非阻塞方式异步记录用户登录行为，包括登录成功和失败的情况。
     * 即使日志线程池满载或写入失败，也不会影响用户的认证结果，确保主业务流程不受干扰。
     *
     * <p>实现原理：
     * <ul>
     *   <li>使用异步写入器将日志投递到后台线程池处理</li>
     *   <li>捕获所有运行时异常，防止日志记录失败影响登录流程</li>
     *   <li>记录日志写入失败的警告信息，便于排查问题</li>
     * </ul>
     *
     * @param username 登录用户名，用于标识登录的用户
     * @param metadata 登录元数据，包含IP地址、User-Agent等客户端信息
     * @param success 登录是否成功，true表示登录成功，false表示登录失败
     * @param message 登录结果消息，通常包含成功提示或失败原因描述
     */
    public void save(String username, AuthService.LoginMetadata metadata, boolean success, String message) {
        try {
            // 调用异步写入器记录登录日志，提取IP地址和User-Agent信息
            writer.writeLogin(username, metadata.ipAddress(), metadata.userAgent(), success, message);
        } catch (RuntimeException exception) {
            // 日志写入失败时记录警告，但不影响登录流程继续执行
            log.warn("event=login_audit_enqueue_failed username={} success={}", username, success, exception);
        }
    }
}
