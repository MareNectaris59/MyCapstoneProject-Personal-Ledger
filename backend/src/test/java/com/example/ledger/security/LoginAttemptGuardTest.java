package com.example.ledger.security;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录失败限流单元测试。
 *
 * <p>限流器的错误方向有两种，都很糟：
 * <ul>
 *   <li>太松 —— 等于没做，密码仍可被爆破</li>
 *   <li>太紧 —— 正常用户输错几次就被锁，比不做更烦人</li>
 * </ul>
 * 所以边界（刚好 N 次、第 N+1 次、窗口到期后的第一秒）必须逐个钉死。
 *
 * <p>这些测试能写出来，是因为 {@code LoginAttemptGuard} 允许注入 {@link Clock}。
 * 直接依赖 {@code Instant.now()} 的话，测窗口过期就得真的等 10 分钟。
 */
class LoginAttemptGuardTest {

    private static final int MAX = 5;
    private static final long WINDOW_SECONDS = 600;

    /** 可手动推进的时钟。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptGuard guard = new LoginAttemptGuard(MAX, WINDOW_SECONDS, clock);

    private static void assertTooMany(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("失败次数未达上限时不锁")
    void notBlockedBelowLimit() {
        String key = guard.key("a@example.com", "127.0.0.1");
        for (int i = 0; i < MAX - 1; i++) {
            guard.recordFailure(key);
            assertThatCode(() -> guard.assertNotBlocked(key)).doesNotThrowAnyException();
        }
        assertThat(guard.failureCount(key)).isEqualTo(MAX - 1);
    }

    @Test
    @DisplayName("第 N 次失败后再请求即被拒绝（错误码 1204）")
    void blocksAtLimit() {
        String key = guard.key("b@example.com", "127.0.0.1");
        for (int i = 0; i < MAX; i++) {
            guard.recordFailure(key);
        }
        assertTooMany(() -> guard.assertNotBlocked(key));
    }

    @Test
    @DisplayName("窗口到期后自动解锁，计数从 0 重新开始")
    void unblocksAfterWindow() {
        String key = guard.key("c@example.com", "127.0.0.1");
        for (int i = 0; i < MAX; i++) {
            guard.recordFailure(key);
        }
        assertTooMany(() -> guard.assertNotBlocked(key));

        // 差一秒到期仍然锁着
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS - 1));
        assertTooMany(() -> guard.assertNotBlocked(key));

        // 正好到期即解锁
        clock.advance(Duration.ofSeconds(1));
        assertThatCode(() -> guard.assertNotBlocked(key)).doesNotThrowAnyException();
        assertThat(guard.failureCount(key)).isZero();
    }

    @Test
    @DisplayName("登录成功清零后可以重新累计")
    void resetClearsCounter() {
        String key = guard.key("d@example.com", "127.0.0.1");
        for (int i = 0; i < MAX; i++) {
            guard.recordFailure(key);
        }
        guard.reset(key);
        assertThat(guard.failureCount(key)).isZero();
        assertThatCode(() -> guard.assertNotBlocked(key)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不同邮箱互不影响（防止一个 IP 锁住所有人）")
    void differentEmailsAreIndependent() {
        String victim = guard.key("e@example.com", "127.0.0.1");
        String other = guard.key("f@example.com", "127.0.0.1");
        for (int i = 0; i < MAX; i++) {
            guard.recordFailure(victim);
        }
        assertTooMany(() -> guard.assertNotBlocked(victim));
        assertThatCode(() -> guard.assertNotBlocked(other)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不同 IP 上的同一邮箱也互不影响（防止单 IP 放大成账号封锁）")
    void differentIpsAreIndependent() {
        String fromA = guard.key("g@example.com", "10.0.0.1");
        String fromB = guard.key("g@example.com", "10.0.0.2");
        for (int i = 0; i < MAX; i++) {
            guard.recordFailure(fromA);
        }
        assertTooMany(() -> guard.assertNotBlocked(fromA));
        assertThatCode(() -> guard.assertNotBlocked(fromB)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("键归一化：邮箱大小写与空格不产生新的额度")
    void keyIsNormalized() {
        assertThat(guard.key("User@Example.com", "127.0.0.1"))
                .isEqualTo(guard.key("  user@example.com  ", "127.0.0.1"));
    }

    @Test
    @DisplayName("IP 为空时退化为 unknown，不会产生 NPE")
    void nullIpIsSafe() {
        assertThat(guard.key("h@example.com", null)).endsWith("@unknown");
        assertThat(guard.key("h@example.com", "  ")).endsWith("@unknown");
        assertThat(guard.key(null, "127.0.0.1")).startsWith("@");
    }

    @Test
    @DisplayName("非法配置回退到默认值（5 次 / 600 秒）")
    void invalidConfigFallsBack() {
        LoginAttemptGuard fallback = new LoginAttemptGuard(0, 0, clock);
        String key = fallback.key("i@example.com", "127.0.0.1");
        for (int i = 0; i < 5; i++) {
            fallback.recordFailure(key);
        }
        assertTooMany(() -> fallback.assertNotBlocked(key));
    }
}
