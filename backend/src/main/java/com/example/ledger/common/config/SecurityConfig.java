package com.example.ledger.common.config;

import com.example.ledger.common.result.Result;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.result.TraceIdHolder;
import com.example.ledger.security.JwtAuthenticationFilter;
import com.example.ledger.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p>要点：
 * <ul>
 *   <li><b>完全无状态</b>：{@code SessionCreationPolicy.STATELESS}，
 *       认证只靠 JWT，不写 Session</li>
 *   <li>CSRF 关闭：采用 {@code Authorization} 头而非 Cookie 传递凭证，
 *       天然免疫 Cookie 型 CSRF（《系统设计说明书》§8.1）</li>
 *   <li>公开接口白名单：登录/注册/验证码/刷新令牌</li>
 *   <li>密码编码器用 BCrypt（强度 10），绝不存明文</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 无需认证即可访问的路径。
     *
     * <p><b>这里刻意逐条列出，而不是用 {@code /api/v1/auth/**} 通配。</b>
     * 曾经用通配的写法导致 {@code /auth/me} 也变成了公开接口 ——
     * 一个"读取当前登录用户"的接口不需要登录，本身就是配置缺陷：
     * 令牌被吊销后请求仍会进入 Controller，{@code @AuthenticationPrincipal}
     * 传入 null，直接抛空指针（500）而不是应有的 401。
     *
     * <p>新增 auth 接口时请判断它是否真的无需登录：
     * 凡是需要知道"当前是谁"的接口，都不能放进这个数组。
     */
    private static final String[] PUBLIC_PATHS = {
            // ---- 真正无需登录的认证接口 ----
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",   // 用 refreshToken 换新令牌，此时 accessToken 已失效
            "/api/v1/auth/code",      // 发送邮箱验证码
            "/api/v1/auth/logout",    // 令牌可能已过期，仍应允许调用以清理本地状态
            "/api/v1/auth/delete",    // 注销账号；密码在 Service 层二次校验

            "/actuator/health",
            "/error",
            // OpenAPI 文档与 Swagger UI（springdoc）。
            // 生产环境应把这几条移除或限制来源 IP —— 接口文档会暴露全部接口签名。
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // 前端静态资源。
            // 前端由 Vite 构建，产物直接输出到 src/main/resources/static/：
            // index.html 在根，带 hash 的 js/css 在 /assets/ 下。
            // 这几条必须公开，否则浏览器加载不到脚本，页面白屏。
            "/",
            "/index.html",
            "/assets/**",
            "/favicon.ico"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 预检请求直接放行
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** 已登录但无权限：返回 403 + 统一响应体。 */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.failure(ResultCode.NO_PERMISSION)));
        };
    }

    /**
     * CORS 配置。
     *
     * <p>开发期允许 Vite dev server（5173）跨域；生产环境前后端同源
     * （Nginx 统一入口），此配置不会生效但保留无害。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
