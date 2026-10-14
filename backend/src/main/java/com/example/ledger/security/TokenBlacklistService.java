package com.example.ledger.security;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌黑名单（退出登录时使用）。
 *
 * <p>本骨架用进程内 {@link ConcurrentHashMap} 实现，带过期清理。
 * 生产环境应替换为 Redis 实现（键 {@code auth:blacklist:{jti}}，
 * TTL 取令牌剩余有效期），否则多实例部署时会出现
 * "在 A 实例登出、B 实例仍然认这个令牌"的漏洞。
 *
 * <p>更严格的做法是配合 refreshToken 的吊销（{@code auth:refresh:{userId}:{tokenId}}），
 * 让黑名单只需覆盖 accessToken 的 30 分钟窗口。
 */
@Service
public class TokenBlacklistService {

    /** token -> 过期时间戳（毫秒） */
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    private static final int CLEAN_THRESHOLD = 512;

    /** 加入黑名单。 */
    public void add(String token, long ttlMillis) {
        if (token == null || token.isBlank()) {
            return;
        }
        cleanUpIfNeeded();
        blacklist.put(token, System.currentTimeMillis() + Math.max(ttlMillis, 0));
    }

    /** 是否已在黑名单中（顺带清理已过期的条目）。 */
    public boolean isBlacklisted(String token) {
        Long expireAt = blacklist.get(token);
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            blacklist.remove(token);
            return false;
        }
        return true;
    }

    private void cleanUpIfNeeded() {
        if (blacklist.size() < CLEAN_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        blacklist.entrySet().removeIf(e -> e.getValue() < now);
    }
}
