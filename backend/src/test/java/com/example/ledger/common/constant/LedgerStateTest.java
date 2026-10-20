package com.example.ledger.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账目状态机单元测试。
 *
 * <p>{@code LedgerState.resolve(amount, reversedAmount)} 是"金额 → 状态"的
 * <b>唯一实现</b>（设计文档明确禁止在别处另写一套 if-else）。
 * 它决定了能否继续冲正，所以边界必须钉死：
 * 冲正额恰等于原额时必须是 REVERSED，差一分就必须还是 PARTIALLY_REVERSED。
 */
class LedgerStateTest {

    @Nested
    @DisplayName("resolve：金额到状态的映射")
    class Resolve {

        @ParameterizedTest(name = "amount={0}, reversed={1} -> {2}")
        @CsvSource({
                // 未冲正
                "100.00, 0.00,   POSTED",
                // reversedAmount 为 null 也视为未冲正
                "100.00, null,   POSTED",
                // 部分冲正：差一分钱都还没到底
                "100.00, 33.33,  PARTIALLY_REVERSED",
                "100.00, 99.99,  PARTIALLY_REVERSED",
                // 刚好冲完
                "100.00, 100.00, REVERSED",
                // 防御：超额（理论上不该出现）也算已冲正，绝不能仍是 PARTIALLY
                "100.00, 120.00, REVERSED",
        })
        @DisplayName("金额与状态的对应关系")
        void mapsAmountToState(String amount, String reversed, LedgerState expected) {
            BigDecimal a = new BigDecimal(amount);
            // @CsvSource 默认不会把字面量 null 转成 Java null（默认 nullValues 为空），
            // 所以这里显式识别字符串 "null"。
            BigDecimal r = (reversed == null || "null".equals(reversed)) ? null : new BigDecimal(reversed);
            assertThat(LedgerState.resolve(a, r)).isEqualTo(expected);
        }

        @Test
        @DisplayName("resolve 用数值比较，不受标度影响")
        void resolveIgnoresScale() {
            // 1.0 vs 1.00 —— 用 equals 会误判，这里必须走 compareTo
            assertThat(LedgerState.resolve(new BigDecimal("1.0"), new BigDecimal("1.00")))
                    .isEqualTo(LedgerState.REVERSED);
        }
    }

    @Nested
    @DisplayName("isReversible：只有未冲完的才可继续冲正")
    class Reversible {

        @Test
        @DisplayName("POSTED / PARTIALLY_REVERSED 可冲正，CREATED / REVERSED 不可")
        void onlyPostedStatesAreReversible() {
            assertThat(LedgerState.POSTED.isReversible()).isTrue();
            assertThat(LedgerState.PARTIALLY_REVERSED.isReversible()).isTrue();
            assertThat(LedgerState.REVERSED.isReversible()).isFalse();
            // CREATED 是事务内瞬时态，落库前不该被冲正
            assertThat(LedgerState.CREATED.isReversible()).isFalse();
        }
    }
}
