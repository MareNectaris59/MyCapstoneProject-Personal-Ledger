package com.example.ledger.security;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器。
 *
 * <p>从 {@code Authorization: Bearer <token>} 解析用户身份并写入
 * {@code SecurityContext}。解析失败<b>不直接抛错</b>，而是放行且不设置认证 ——
 * 是否放行由 {@code SecurityConfig} 的授权规则决定，这样公开接口
 * （登录、注册、验证码）带不带无效令牌都能正常访问。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && !tokenBlacklistService.isBlacklisted(token)) {
            try {
                CurrentUser user = jwtTokenProvider.parseAccessToken(token);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user, null, AuthorityUtils.NO_AUTHORITIES);
                auth.setDetails(token);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (BizException ex) {
                // 令牌无效/过期：不设置认证，交由授权规则与 EntryPoint 处理。
                // 若是 TOKEN_EXPIRED，把原始令牌暂存到请求属性中，便于入口点返回可识别的错误码。
                log.warn("JWT 解析失败 method={} uri={} code={} msg={}",
                        request.getMethod(), request.getRequestURI(),
                        ex.getResultCode().name(), ex.getMessage());
                request.setAttribute("jwt.error.code", ex.getResultCode().name());
                request.setAttribute("jwt.error.message", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        } else if (token != null) {
            // 黑名单令牌（已退出登录 / 已注销账号）：不设置认证，但要让入口点
            // 知道具体原因，前端才能区分"该重新登录"与"令牌本身有问题"。
            log.warn("令牌在黑名单中，已拒绝 method={} uri={}", request.getMethod(), request.getRequestURI());
            request.setAttribute("jwt.error.code", ResultCode.TOKEN_INVALID.name());
            request.setAttribute("jwt.error.message", "登录状态已失效，请重新登录");
            SecurityContextHolder.clearContext();
        } else {
            log.debug("请求未携带 Bearer 令牌 method={} uri={}", request.getMethod(), request.getRequestURI());
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /** 便于在入口点里判断异常类型（保留给后续扩展）。 */
    public static boolean isExpired(Throwable ex) {
        return ex instanceof ExpiredJwtException;
    }
}
