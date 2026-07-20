package com.baseai.platform.common;

import com.baseai.platform.job.JobCancelledException;
import jakarta.validation.ConstraintViolationException;
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
    private static final Logger log=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /** 返回业务错误码和对应 HTTP 状态。 */ @ExceptionHandler(BusinessException.class) public ResponseEntity<ApiResponse<Void>> business(BusinessException exception){return ResponseEntity.status(exception.getStatus()).body(ApiResponse.failure(exception.getCode(),exception.getMessage()));}
    /** 将协作取消映射为冲突响应。 */ @ExceptionHandler(JobCancelledException.class) public ResponseEntity<ApiResponse<Void>> cancelled(JobCancelledException exception){return ResponseEntity.status(409).body(ApiResponse.failure("JOB_CANCELLED",exception.getMessage()));}
    /** 返回参数校验错误。 */ @ExceptionHandler({MethodArgumentNotValidException.class,ConstraintViolationException.class}) public ResponseEntity<ApiResponse<Void>> validation(Exception exception){String message=exception instanceof MethodArgumentNotValidException valid?valid.getBindingResult().getAllErrors().stream().findFirst().map(item->item.getDefaultMessage()).orElse("请求参数无效"):"请求参数无效";return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR",message));}
    /** 返回无效 JSON 错误。 */ @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException exception){return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_JSON","请求体不是有效的 JSON"));}
    /** 返回缺失参数或上传内容错误。 */ @ExceptionHandler({MissingServletRequestPartException.class,MissingServletRequestParameterException.class,MultipartException.class}) public ResponseEntity<ApiResponse<Void>> missing(Exception exception){return ResponseEntity.badRequest().body(ApiResponse.failure("MISSING_PARAMETER","请求参数不完整"));}
    /** 记录未知异常且不向客户端泄露内部堆栈。 */ @ExceptionHandler(Exception.class) public ResponseEntity<ApiResponse<Void>> unknown(Exception exception){log.error("event=unhandled_exception",exception);return ResponseEntity.internalServerError().body(ApiResponse.failure("INTERNAL_ERROR","服务内部错误"));}
}
