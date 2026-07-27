package com.baseai.platform.common;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public class BusinessException extends RuntimeException {
    private final int status;
    private final String messageKey;
    private final Object[] messageArguments;

    /** 构建默认 400 状态的本地化业务异常。 */
    public BusinessException(String messageKey, Object... messageArguments) {
        this(400, messageKey, messageArguments);
    }

    /** 构建指定 HTTP 状态的本地化业务异常。 */
    public BusinessException(int status, String messageKey, Object... messageArguments) {
        super(defaultMessage(messageKey, messageArguments));
        this.status = status;
        this.messageKey = messageKey;
        this.messageArguments = messageArguments == null ? new Object[0] : messageArguments.clone();
    }

    /** 返回业务异常对应的 HTTP 状态。 */
    public int getStatus() { return status; }
    /** 返回供统一异常处理器解析的消息资源键。 */
    public String getMessageKey() { return messageKey; }
    /** 返回消息占位参数的防御性副本。 */
    public Object[] getMessageArguments() { return messageArguments.clone(); }
    /** 构建 401 未认证异常。 */
    public static BusinessException unauthorized(String messageKey, Object... arguments) { return new BusinessException(401, messageKey, arguments); }
    /** 构建 403 无权限异常。 */
    public static BusinessException forbidden(String messageKey, Object... arguments) { return new BusinessException(403, messageKey, arguments); }
    /** 构建 404 资源不存在异常。 */
    public static BusinessException notFound(String messageKey, Object... arguments) { return new BusinessException(404, messageKey, arguments); }

    /** 使用中文资源构建异常默认文本，确保异步日志和内部错误记录保持可读。 */
    private static String defaultMessage(String messageKey, Object[] arguments) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.SIMPLIFIED_CHINESE);
        return new MessageFormat(bundle.getString(messageKey), Locale.SIMPLIFIED_CHINESE)
            .format(arguments == null ? new Object[0] : arguments);
    }
}
