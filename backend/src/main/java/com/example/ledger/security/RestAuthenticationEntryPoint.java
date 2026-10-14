package com.example.ledger.security;

import com.example.ledger.common.result.Result;
import com.example.ledger.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证入口点：返回 <b>HTTP 401</b> + 统一响应体。
 *
 * <p>按《系统设计说明书》§6.1.3，令牌过期返回 401 + {@code code=1001}，
 * 前端拦截器据此触发一次静默刷新并重放原请求。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 过滤器把 JWT 解析失败的原因放在了请求属性里，这里还原成对应业务码
        Object codeName = request.getAttribute("jwt.error.code");
        ResultCode resultCode = ResultCode.UNAUTHENTICATED;
        if (codeName != null) {
            try {
                resultCode = ResultCode.valueOf(String.valueOf(codeName));
            } catch (IllegalArgumentException ignored) {
                // 属性名不可识别时回退到默认的"请先登录"
            }
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.failureOf(resultCode)));
    }
}
