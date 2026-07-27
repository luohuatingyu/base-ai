package com.baseai.platform.common;

import com.baseai.platform.trace.TraceCancelledException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final MessageSource messageSource;

    /** 注入统一消息资源。 */
    public GlobalExceptionHandler(MessageSource messageSource) { this.messageSource = messageSource; }

    /** 返回本地化业务消息和对应 HTTP 状态。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        int status = exception.getStatus();
        return ResponseEntity.status(status).body(ApiResponse.failure(status,
            message(exception.getMessageKey(), exception.getMessageArguments())));
    }

    /** 将协作取消映射为冲突响应。 */
    @ExceptionHandler(TraceCancelledException.class)
    public ResponseEntity<ApiResponse<Void>> cancelled(TraceCancelledException exception) {
        return ResponseEntity.status(409).body(ApiResponse.failure(409,
            message(exception.getMessageKey(), exception.getMessageArguments())));
    }

    /** 返回参数校验错误。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> validation(Exception exception) {
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
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, message("error.invalidJson")));
    }

    /** 返回缺失参数或上传内容错误。 */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class, MultipartException.class})
    public ResponseEntity<ApiResponse<Void>> missing(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, message("error.missingParameter")));
    }

    /** 记录未知异常且不向客户端泄露内部堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unknown(Exception exception) {
        log.error("event=unhandled_exception", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.failure(500, message("error.internalError")));
    }

    /** 按当前请求语言解析消息资源。 */
    private String message(String key, Object... arguments) { return messageSource.getMessage(key, arguments, LocaleContextHolder.getLocale()); }
}
