package com.example.ledger.common.exception;

import com.example.ledger.common.result.Result;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.result.TraceIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>核心原则：<b>绝不向前端暴露堆栈</b>（《系统设计说明书》§8.3）。
 * 可预期的业务异常记 WARN 且不打堆栈；不可预期的异常记 ERROR 并打堆栈，
 * 但返回给前端的仍是统一的安全文案。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：HTTP 200 + 业务码，便于前端统一处理。 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException ex) {
        log.warn("业务异常 traceId={} code={} msg={}",
                TraceIdHolder.current(), ex.getResultCode().getCode(), ex.getMessage());
        return Result.failure(ex.getResultCode().getCode(), ex.getMessage());
    }

    /** @Valid 校验失败（请求体）。取第一条错误提示返回。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 traceId={} detail={}", TraceIdHolder.current(), detail);
        return Result.failure(ResultCode.PARAM_INVALID.getCode(), detail);
    }

    /** @Valid 校验失败（表单绑定）。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException ex) {
        String detail = ex.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败 traceId={} detail={}", TraceIdHolder.current(), detail);
        return Result.failure(ResultCode.PARAM_INVALID.getCode(), detail);
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return Result.failure(ResultCode.PARAM_INVALID.getCode(),
                "缺少必填参数：" + ex.getParameterName());
    }

    /** 参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return Result.failure(ResultCode.PARAM_INVALID.getCode(),
                "参数格式不正确：" + ex.getName());
    }

    /** 请求体不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败 traceId={} msg={}", TraceIdHolder.current(), ex.getMessage());
        return Result.failure(ResultCode.PARAM_INVALID.getCode(), "请求体格式不正确");
    }

    /** 请求方法不支持。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return Result.failure(ResultCode.PARAM_INVALID.getCode(),
                "不支持的请求方法：" + ex.getMethod());
    }

    /** 无权限：返回 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException ex) {
        log.warn("越权访问 traceId={} msg={}", TraceIdHolder.current(), ex.getMessage());
        return Result.failure(ResultCode.NO_PERMISSION);
    }

    /** 兜底：未预期的异常，记 ERROR 与完整堆栈，对外只给安全文案。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期异常 traceId={} uri={} method={}",
                TraceIdHolder.current(), request.getRequestURI(), request.getMethod(), ex);
        return Result.failure(ResultCode.INTERNAL_ERROR);
    }
}
