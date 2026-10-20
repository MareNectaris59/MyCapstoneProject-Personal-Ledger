package com.example.ledger.common.util;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 金额工具单元测试。
 *
 * <p>为什么这个类值得单独测：全系统所有金额运算都必须过它，
 * 而「金额」是记账软件唯一不能出错的东西。这里的每条断言都对应一条业务规则
 * （BR-01 金额为正、BR-03 上下限、§7.1 禁止浮点与静默舍入）。
 */
class MoneyUtilTest {

    @Nested
    @DisplayName("validateAmount：合法性（BR-01 / BR-03）")
    class ValidateAmount {

        @Test
        @DisplayName("下限 0.01 与上限 1000000.00 都算合法边界")
        void acceptsBoundaries() {
            assertThatCode(() -> MoneyUtil.validateAmount(new BigDecimal("0.01")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> MoneyUtil.validateAmount(new BigDecimal("1000000.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null、0、负数一律拒绝")
        void rejectsNullOrNonPositive() {
            assertCode(() -> MoneyUtil.validateAmount(null), ResultCode.AMOUNT_INVALID);
            // 0 走的是"小于下限"分支，负数同理
            assertCode(() -> MoneyUtil.validateAmount(BigDecimal.ZERO), ResultCode.AMOUNT_INVALID);
            assertCode(() -> MoneyUtil.validateAmount(new BigDecimal("-1")), ResultCode.AMOUNT_INVALID);
        }

        @ParameterizedTest(name = "超过两位小数应被拒绝：{0}")
        @ValueSource(strings = {"1.001", "0.005", "100.000"})
        @DisplayName("超过 2 位小数直接拒绝，不做静默舍入")
        void rejectsMoreThanTwoDecimals(String raw) {
            // 设计文档 §7.1：宁可拒绝，也不要让用户以为自己输的金额被接受了。
            // 这条容易被"顺手 round 一下"改掉，所以用参数化钉死。
            assertCode(() -> MoneyUtil.validateAmount(new BigDecimal(raw)), ResultCode.AMOUNT_INVALID);
        }

        @Test
        @DisplayName("上限之外（1000000.01）被拒绝")
        void rejectsAboveMax() {
            assertCode(() -> MoneyUtil.validateAmount(new BigDecimal("1000000.01")),
                    ResultCode.AMOUNT_INVALID);
        }
    }

    @Nested
    @DisplayName("数值比较（§7.1 必须用 compareTo 而非 equals）")
    class Comparison {

        @Test
        @DisplayName("eq 忽略标度差异：1.0 与 1.00 视为相等")
        void eqIgnoresScale() {
            // new BigDecimal("1.0").equals(new BigDecimal("1.00")) 是 false，
            // 这正是必须走 eq/compareTo 的原因
            assertThat(new BigDecimal("1.0")).isNotEqualTo(new BigDecimal("1.00"));
            assertThat(MoneyUtil.eq(new BigDecimal("1.0"), new BigDecimal("1.00"))).isTrue();
        }

        @Test
        @DisplayName("eq 把 null 当作 0")
        void eqTreatsNullAsZero() {
            assertThat(MoneyUtil.eq(null, BigDecimal.ZERO)).isTrue();
            assertThat(MoneyUtil.eq(null, new BigDecimal("0.00"))).isTrue();
            assertThat(MoneyUtil.eq(null, new BigDecimal("0.01"))).isFalse();
        }

        @Test
        @DisplayName("isZero / isPositive / isNegative 对 null 的处理")
        void predicatesHandleNull() {
            assertThat(MoneyUtil.isZero(null)).isTrue();
            assertThat(MoneyUtil.isPositive(null)).isFalse();
            assertThat(MoneyUtil.isNegative(null)).isFalse();
            assertThat(MoneyUtil.isPositive(new BigDecimal("0.01"))).isTrue();
            assertThat(MoneyUtil.isNegative(new BigDecimal("-0.01"))).isTrue();
        }
    }

    @Nested
    @DisplayName("格式化与规范化")
    class Formatting {

        @Test
        @DisplayName("toPlainString 固定两位小数，null 输出 0.00")
        void toPlainStringAlwaysTwoDecimals() {
            assertThat(MoneyUtil.toPlainString(new BigDecimal("1"))).isEqualTo("1.00");
            assertThat(MoneyUtil.toPlainString(new BigDecimal("1.5"))).isEqualTo("1.50");
            assertThat(MoneyUtil.toPlainString(null)).isEqualTo("0.00");
            assertThat(MoneyUtil.toPlainString(new BigDecimal("-2.005"))).isEqualTo("-2.01");
        }

        @Test
        @DisplayName("scale 用 HALF_UP 规范化，而不是直接截断")
        void scaleRoundsHalfUp() {
            assertThat(MoneyUtil.scale(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
            assertThat(MoneyUtil.scale(new BigDecimal("1.004"))).isEqualByComparingTo("1.00");
            assertThat(MoneyUtil.scale(null)).isNull();
        }

        @Test
        @DisplayName("nullToZero 保留两位小数的 0")
        void nullToZeroHasScale() {
            assertThat(MoneyUtil.nullToZero(null)).isEqualByComparingTo("0.00");
            assertThat(MoneyUtil.nullToZero(null).scale()).isEqualTo(MoneyUtil.SCALE);
        }
    }

    /** 断言抛出的是带指定错误码的业务异常 —— 只看类型不够，错误码才是接口契约。 */
    private static void assertCode(Runnable action, ResultCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(expected);
    }
}
