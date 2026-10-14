package com.example.ledger.security;

/**
 * 当前登录用户上下文，由 {@link JwtAuthenticationFilter} 从 JWT 解析后放入
 * Spring Security 的 {@code SecurityContext}，Controller 通过
 * {@code @AuthenticationPrincipal} 注入。
 *
 * <p>刻意<b>不</b>实现 {@code UserDetails}：本系统无角色/权限模型，
 * 只需携带 userId 与 email，保持简单。
 *
 * <p>越权防护的关键约定：所有涉及资源的查询都必须使用这里的 {@code userId}，
 * <b>禁止从请求参数取 userId</b>（《系统设计说明书》§8.1）。
 */
public record CurrentUser(Long userId, String email) {
}
