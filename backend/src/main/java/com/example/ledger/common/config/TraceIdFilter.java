package com.example.ledger.common.config;

import com.example.ledger.common.result.TraceIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器。
 *
 * <p>为每个请求生成（或沿用客户端传入的）traceId，写入 {@link TraceIdHolder}
 * 与 SLF4J 的 {@link MDC}，并回写到响应头 {@code X-Trace-Id}。
 * 这样前端报错时可以直接把 traceId 给出来，后端按它 grep 日志即可定位
 * （《系统设计说明书》§6.1.2）。
 *
 * <p>必须在最外层执行，故设 {@link Ordered#HIGHEST_PRECEDENCE}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        TraceIdHolder.set(traceId);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理，否则线程池复用时会串号
            MDC.remove(MDC_KEY);
            TraceIdHolder.clear();
        }
    }
}
