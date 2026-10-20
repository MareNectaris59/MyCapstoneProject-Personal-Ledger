package com.example.ledger.common.exception;

import com.example.ledger.common.result.Result;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.result.TraceIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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

    /**
     * @RequestParam / @PathVariable 上的约束校验失败（方法参数级 @Validated）。
     *
     * <p>与上面的请求体校验是两套机制：{@code @Valid @RequestBody} 抛
     * MethodArgumentNotValidException，而 {@code @Validated} 加在类上时，
     * 参数上的 {@code @Min/@Max} 由方法校验代理抛 ConstraintViolationException。
     * 两者都要映射到 1101，否则分页越界会落进兜底 500。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> String.valueOf(v.getMessage()))
                .collect(Collectors.joining("; "));
        log.warn("参数越界 traceId={} detail={}", TraceIdHolder.current(), detail);
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

    /**
     * 路径不存在：404。
     *
     * <p>Spring Boot 3.2+ 对未匹配的路径抛 {@code NoResourceFoundException}
     * （message 形如 "No static resource api/v1/ledgers/abc"）—— 浏览器端被当作
     * 找不到静态资源，服务端其实等价于"没有这个接口/资源"。
     *
     * <p><b>为什么必须单独处理？</b>它的父类是 {@code ServletException}，
     * 不是 {@code NoHandlerFoundException}，所以不会被专门的处理器接住，
     * 而是落到下面的兜底 {@code Exception} 处理器，对外返回 <b>500</b>。
     * 症状是：访问一个写错的 URL（如 {@code /api/v1/ledgers/abc}，id 不是数字）
     * 得到"系统开小差了"，把调用方的路径错误伪装成服务端故障，
     * 排查时会往错误的方向走。实测确认过这个行为。
     *
     * <p>返回 404 而不是 400：路径根本没匹配上任何接口，属于"资源不存在"；
     * 而参数格式错误（如 {@code ?size=abc}）仍由
     * {@link #handleTypeMismatch} 返回 400。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("路径不存在 traceId={} uri={}", TraceIdHolder.current(), request.getRequestURI());
        return Result.failure(ResultCode.NOT_FOUND.getCode(), "请求的路径不存在");
    }

    /**
     * Content-Type 不被接口接受。
     *
     * <p>踩过：调用方把 JSON 以 {@code text/plain} 发到 {@code /auth/register}，
     * 此前会落进兜底分支，对外返回 1901「系统开小差了」，服务端还记了一条
     * ERROR 与完整堆栈 —— 两边的信息都是错的：
     * 调用方看不出是自己传错了格式，运维还会误以为服务出了故障。
     *
     * <p>现在返回 415 并直接说明需要什么格式。这是调用方的问题，
     * 不该以 5xx 呈现，也不该污染错误日志。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                    HttpServletRequest request) {
        log.warn("Content-Type 不支持 traceId={} uri={} contentType={}",
                TraceIdHolder.current(), request.getRequestURI(), ex.getContentType());
        String message = "请求格式不对";
        if (ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()) {
            message += "，该接口只接受 " + ex.getSupportedMediaTypes();
        }
        return Result.failure(ResultCode.PARAM_INVALID.getCode(), message);
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
