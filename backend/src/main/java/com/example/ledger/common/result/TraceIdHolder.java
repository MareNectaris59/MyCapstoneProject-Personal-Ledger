package com.example.ledger.common.result;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 当前请求的链路追踪 ID 持有者。
 *
 * <p>由 {@code TraceIdFilter} 在请求进入时写入、请求结束时清除，
 * 供 {@link Result} 与日志共同使用，便于按 traceId 串联一次请求的全部日志。
 */
public final class TraceIdHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIdHolder() {
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static String current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 服务端当前时间，ISO 8601 带时区偏移。 */
    public static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
