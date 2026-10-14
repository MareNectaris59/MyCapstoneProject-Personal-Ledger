package com.example.ledger.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>约定（见《系统设计说明书》§6.1.2）：
 * <ul>
 *   <li>业务错误统一返回 HTTP 200 + 非 0 的 {@code code}，便于前端统一处理；
 *       仅认证失败返回 401、无权限 403、系统异常 500。</li>
 *   <li>金额字段一律以字符串传输，避免 JSON 浮点精度丢失。</li>
 *   <li>{@code data} 无内容时为 null，序列化时保留该字段以便前端判断。</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务码，0 表示成功 */
    private int code;

    /** 面向用户的提示文案，可直接展示 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 链路追踪 ID，便于按日志定位问题 */
    private String traceId;

    /** 服务端时间戳（ISO 8601，由 Jackson 统一格式化） */
    private String timestamp;

    public Result() {
    }

    public Result(int code, String message, T data, String traceId, String timestamp) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = timestamp;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(),
                data, TraceIdHolder.current(), TraceIdHolder.now());
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> failure(ResultCode resultCode) {
        return failure(resultCode.getCode(), resultCode.getMessage());
    }

    /**
     * 由 {@link ResultCode} 构造失败结果。
     *
     * <p>供 Spring Security 的入口点/拒绝处理器使用 —— 它们不在 MVC 的
     * 异常处理链上，拿不到 {@code GlobalExceptionHandler} 的转换，
     * 需要自己构造统一响应体。
     */
    public static <T> Result<T> failureOf(ResultCode resultCode) {
        return failure(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(code, message, null, TraceIdHolder.current(), TraceIdHolder.now());
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }

    // ---------- getters / setters ----------

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
