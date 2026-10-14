package com.example.ledger.security;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>密钥从配置读取（环境变量 {@code APP_JWT_SECRET}），<b>不硬编码进代码</b>
 * （《系统设计说明书》§8.1）。HS256 要求密钥至少 256 位，启动时会做长度校验。
 *
 * <p>令牌分两类：
 * <ul>
 *   <li>accessToken —— 30 分钟，放在 {@code Authorization: Bearer} 头里</li>
 *   <li>refreshToken —— 14 天，用于静默换取新的 accessToken</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey secretKey;
    private final long accessTokenSeconds;
    private final long refreshTokenSeconds;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-seconds:1800}") long accessTokenSeconds,
            @Value("${app.jwt.refresh-token-seconds:1209600}") long refreshTokenSeconds) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret 至少需要 32 字节（256 位）以保证 HS256 安全性，当前 " + keyBytes.length + " 字节");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenSeconds = accessTokenSeconds;
        this.refreshTokenSeconds = refreshTokenSeconds;
    }

    /** 签发访问令牌。 */
    public String createAccessToken(Long userId, String email) {
        return create(userId, email, TYPE_ACCESS, accessTokenSeconds);
    }

    /** 签发刷新令牌。 */
    public String createRefreshToken(Long userId, String email) {
        return create(userId, email, TYPE_REFRESH, refreshTokenSeconds);
    }

    private String create(Long userId, String email, String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验访问令牌。
     *
     * @throws BizException 令牌过期返回 {@code TOKEN_EXPIRED}，其余非法情形返回 {@code TOKEN_INVALID}
     */
    public CurrentUser parseAccessToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw BizException.of(ResultCode.TOKEN_INVALID, "该令牌不能用于接口访问");
        }
        return toCurrentUser(claims);
    }

    /** 解析并校验刷新令牌。 */
    public CurrentUser parseRefreshToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw BizException.of(ResultCode.TOKEN_INVALID, "该令牌不能用于刷新");
        }
        return toCurrentUser(claims);
    }

    private CurrentUser toCurrentUser(Claims claims) {
        return new CurrentUser(Long.valueOf(claims.getSubject()), claims.get("email", String.class));
    }

    private Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw BizException.of(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT 解析失败: {}", ex.getMessage());
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }
    }

    public long getAccessTokenSeconds() {
        return accessTokenSeconds;
    }

    public long getRefreshTokenSeconds() {
        return refreshTokenSeconds;
    }

    /**
     * 令牌的剩余有效毫秒数，用于设置黑名单 TTL。
     *
     * <p>令牌已过期或非法时返回 0 —— 这类令牌无需加入黑名单。
     * 刻意<b>不</b>对外暴露密钥：解析能力应收敛在本类内部。
     */
    public long remainingTtlMillis(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            return Math.max(remaining, 0);
        } catch (JwtException | IllegalArgumentException ex) {
            return 0;
        }
    }
}
