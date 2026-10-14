package com.example.ledger.common.util;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额运算工具。
 *
 * <p>本系统所有金额运算都必须经过这里，理由见《系统设计说明书》§7.1：
 * <ul>
 *   <li>禁止 {@code double}/{@code float} 参与金额运算 —— 二进制浮点无法精确表示 0.1</li>
 *   <li>比较必须用 {@link BigDecimal#compareTo} 而非 {@code equals}，
 *       因为 {@code new BigDecimal("1.0").equals(new BigDecimal("1.00"))} 返回 false</li>
 *   <li>舍入统一 {@link RoundingMode#HALF_UP}，保留 2 位</li>
 * </ul>
 */
public final class MoneyUtil {

    /** 金额小数位 */
    public static final int SCALE = 2;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    /** 单笔金额上限 1,000,000.00（BR-03） */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");

    /** 单笔金额下限 0.01（BR-03） */
    public static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    private MoneyUtil() {
    }

    /** 规范化为 2 位小数。 */
    public static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 是否为正数（&gt; 0）。 */
    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 是否为负数（&lt; 0）。 */
    public static boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    /** 是否为 0（null 视为 0）。 */
    public static boolean isZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }

    /** null 安全的取值。 */
    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    /** 两个金额是否相等（按数值比较，忽略标度）。 */
    public static boolean eq(BigDecimal a, BigDecimal b) {
        return nullToZero(a).compareTo(nullToZero(b)) == 0;
    }

    /**
     * 校验单笔金额合法性（BR-01、BR-03）。
     *
     * @throws BizException 金额非法时抛出
     */
    public static void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw BizException.of(ResultCode.AMOUNT_INVALID, "金额不能为空");
        }
        if (amount.scale() > SCALE) {
            // 不做静默舍入：宁可拒绝，也不要让用户以为自己输的金额被接受了
            throw BizException.of(ResultCode.AMOUNT_INVALID,
                    "金额最多保留 " + SCALE + " 位小数");
        }
        if (amount.compareTo(MIN_AMOUNT) < 0) {
            throw BizException.of(ResultCode.AMOUNT_INVALID,
                    "金额不能小于 " + MIN_AMOUNT.toPlainString());
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw BizException.of(ResultCode.AMOUNT_INVALID,
                    "单笔金额不能超过 " + MAX_AMOUNT.toPlainString());
        }
    }

    /** 展示用格式化：固定 2 位小数的字符串（金额一律以字符串传输）。 */
    public static String toPlainString(BigDecimal value) {
        return scale(nullToZero(value)).toPlainString();
    }
}
