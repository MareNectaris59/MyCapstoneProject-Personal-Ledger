package com.example.ledger.security;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败次数限制（防暴力破解，FR-U-06 的落地实现）。
 *
 * <p><b>为什么需要它</b>：登录接口如果不限流，攻击者可以无限次试密码。
 * 密码再强也扛不住离线之外的在线爆破，而这个系统连账号枚举都做了防护
 * （登录失败文案统一），却不限制次数，等于把最后一道门留着。
 *
 * <p><b>为什么手写而不是引入 Bucket4j / Resilience4j</b>：
 * 这个需求只需要"固定窗口内失败 N 次就锁一会儿"，十几行就能写清楚，
 * 而且没有 Redis 可用（阶段 5 才接）。引入一个分布式限流框架为本机
 * 单实例服务付出额外依赖与学习成本，不划算。
 *
 * <p><b>键的选择</b>：{@code 邮箱 + 客户端 IP}。
 * <ul>
 *   <li>只按邮箱：一个恶意 IP 可以锁定任意用户的账号（拒绝服务攻击）。</li>
 *   <li>只按 IP：同一 NAT 后的正常用户会互相牵连。</li>
 *   <li>两者组合：既限制了针对单个账号的爆破，也不会被单个 IP 轻易放大成
 *       对全站用户的封锁。</li>
 * </ul>
 *
 * <p><b>已知边界</b>：状态存在进程内存里，重启即清空，多实例部署时各自独立。
 * 对本项目（单机毕设）是合适的取舍；需要跨实例时把存储换成 Redis 即可，
 * 接口不用变。
 *
 * <p>本类不依赖时钟的真实值（构造时可注入 {@link Clock}），因此过期行为可单测。
 */
@Component
public class LoginAttemptGuard {

    /** 默认：10 分钟内允许 5 次失败 */
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long DEFAULT_WINDOW_SECONDS = 600;

    /** 内存里的记录上限，超过后触发一次过期清理，防止被刷爆内存 */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptGuard(
            @Value("${app.auth.login-max-attempts:5}") int maxAttempts,
            @Value("${app.auth.login-window-seconds:600}") long windowSeconds) {
        this(maxAttempts, windowSeconds, Clock.systemUTC());
    }

    /** 供单元测试注入可控时钟，实际运行不会用到。 */
    LoginAttemptGuard(int maxAttempts, long windowSeconds, Clock clock) {
        this.maxAttempts = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds <= 0 ? DEFAULT_WINDOW_SECONDS : windowSeconds);
        this.clock = clock;
    }

    /**
     * 构造限流键。
     *
     * <p>邮箱统一转小写 —— 攻击者不该因为换了大小写就得到一份新的尝试额度。
     */
    public String key(String email, String clientIp) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        return normalizedEmail + '@' + ip;
    }

    /**
     * 当前是否已被锁定。
     *
     * @throws BizException 已超限时直接抛出，错误码 1204
     */
    public void assertNotBlocked(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        Attempt fresh = attempt.expireIfStale(clock.instant(), window);
        if (fresh == null) {
            attempts.remove(key, attempt);
            return;
        }
        if (fresh.count() >= maxAttempts) {
            throw BizException.of(ResultCode.TOO_MANY_REQUESTS,
                    "尝试次数过多，请 " + remainingSeconds(fresh) + " 秒后重试");
        }
    }

    /** 记录一次失败。窗口过期则从 1 重新计数。 */
    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (k, existing) -> {
            Attempt fresh = existing == null ? null : existing.expireIfStale(now, window);
            if (fresh == null) {
                return new Attempt(1, now);
            }
            return new Attempt(fresh.count() + 1, fresh.windowStart());
        });
        if (attempts.size() > SWEEP_THRESHOLD) {
            sweepExpired(now);
        }
    }

    /** 登录成功后清空计数 —— 成功一次就说明不是爆破。 */
    public void reset(String key) {
        attempts.remove(key);
    }

    /** 供测试与排障：当前键的失败次数（已过期则为 0）。 */
    public int failureCount(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return 0;
        }
        Attempt fresh = attempt.expireIfStale(clock.instant(), window);
        return fresh == null ? 0 : fresh.count();
    }

    private long remainingSeconds(Attempt attempt) {
        long elapsed = Duration.between(attempt.windowStart(), clock.instant()).getSeconds();
        return Math.max(1, window.getSeconds() - elapsed);
    }

    private void sweepExpired(Instant now) {
        Iterator<Map.Entry<String, Attempt>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Attempt a = it.next().getValue();
            if (a.expireIfStale(now, window) == null) {
                it.remove();
            }
        }
    }

    /** 一次窗口内的失败记录。不可变，过期即视为不存在。 */
    private record Attempt(int count, Instant windowStart) {

        /** 窗口已过期则返回 null，否则返回自身。 */
        Attempt expireIfStale(Instant now, Duration window) {
            return Duration.between(windowStart, now).compareTo(window) >= 0 ? null : this;
        }
    }
}
