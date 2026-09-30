package com.baseai.platform.common;

import com.baseai.platform.trace.TraceCancelledException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final MessageSource messageSource;

    /** 注入统一消息资源。 */
    public GlobalExceptionHandler(MessageSource messageSource) { this.messageSource = messageSource; }

    /** 返回本地化业务消息和对应 HTTP 状态。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        markErrorType(exception);
        int status = exception.getStatus();
        return ResponseEntity.status(status).body(ApiResponse.failure(status,
            message(exception.getMessageKey(), exception.getMessageArguments())));
    }

    /** 将协作取消映射为冲突响应。 */
    @ExceptionHandler(TraceCancelledException.class)
    public ResponseEntity<ApiResponse<Void>> cancelled(TraceCancelledException exception) {
        markErrorType(exception);
        return ResponseEntity.status(409).body(ApiResponse.failure(409,
            message(exception.getMessageKey(), exception.getMessageArguments())));
    }

    /** 返回参数校验错误。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> validation(Exception exception) {
        markErrorType(exception);
        String value = exception instanceof MethodArgumentNotValidException valid
            ? valid.getBindingResult().getAllErrors().stream().findFirst().map(item -> item.getDefaultMessage())
                .orElse(message("error.validation"))
            : ((ConstraintViolationException) exception).getConstraintViolations().stream().findFirst()
                .map(item -> item.getMessage()).orElse(message("error.validation"));
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, value));
    }

    /** 返回无效 JSON 错误。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException exception) {
        markErrorType(exception);
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, message("error.invalidJson")));
    }

    /** 将 Spring 在解析 multipart 前拒绝的超限上传明确映射为 413。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        markErrorType(exception);
        return ResponseEntity.status(413).body(ApiResponse.failure(413, message("error.requestTooLarge")));
    }

    /** 返回缺失参数或上传内容错误。 */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class, MultipartException.class})
    public ResponseEntity<ApiResponse<Void>> missing(Exception exception) {
        markErrorType(exception);
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, message("error.missingParameter")));
    }

    /** 将未匹配到控制器或静态资源的请求映射为标准 404 响应。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException exception) {
        markErrorType(exception);
        return ResponseEntity.status(404).body(ApiResponse.failure(404, message("error.notFound")));
    }

    /** 记录未知异常且不向客户端泄露内部堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unknown(Exception exception) {
        markErrorType(exception);
        log.error("event=unhandled_exception status=500 error_type={} request_id={} trace_id={}",
            errorType(exception), contextValue("requestId"), contextValue("traceId"), exception);
        return ResponseEntity.internalServerError().body(ApiResponse.failure(500, message("error.internalError")));
    }

    /** 把异常类型交给请求响应日志，避免只看到状态码却无法判断失败类别。 */
    private void markErrorType(Exception exception) {
        if (exception == null) return;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(
                com.baseai.platform.web.RequestContextFilter.ERROR_TYPE_ATTRIBUTE, errorType(exception));
        }
    }

    /** 返回稳定的异常类型名，不把异常消息或请求内容写入结构化字段。 */
    private String errorType(Exception exception) {
        return exception == null ? "-" : exception.getClass().getSimpleName();
    }

    /** 从 MDC 读取跨服务关联字段，未建立请求上下文时使用占位值。 */
    private String contextValue(String key) {
        String value = MDC.get(key);
        return value == null || value.isBlank() ? "-" : value;
    }

    /** 按当前请求语言解析消息资源。 */
    private String message(String key, Object... arguments) { return messageSource.getMessage(key, arguments, LocaleContextHolder.getLocale()); }
}
