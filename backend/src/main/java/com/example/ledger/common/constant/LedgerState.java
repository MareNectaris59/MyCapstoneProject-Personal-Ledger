package com.example.ledger.common.constant;

import java.math.BigDecimal;

/**
 * 记账凭证状态。
 *
 * <p>状态机见《系统设计说明书》§5.1.4。{@code CREATED} 是事务内的瞬时状态，
 * 正常提交后不会持久存在。
 *
 * <p><b>{@link #resolve(BigDecimal, BigDecimal)} 是 amount/reversedAmount 与 state
 * 对应关系的唯一实现</b>，禁止在其他地方另写一套 if-else。
 */
public enum LedgerState {

    /** 已创建（事务内瞬时态） */
    CREATED,

    /** 已记账 */
    POSTED,

    /** 部分冲正 */
    PARTIALLY_REVERSED,

    /** 已全额冲正 */
    REVERSED;

    /**
     * 由凭证金额与已冲正金额推导状态。
     *
     * @param amount         凭证金额（必须 &gt; 0）
     * @param reversedAmount 已冲正金额（0 ≤ reversedAmount ≤ amount）
     */
    public static LedgerState resolve(BigDecimal amount, BigDecimal reversedAmount) {
        if (reversedAmount == null || reversedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return POSTED;
        }
        if (reversedAmount.compareTo(amount) >= 0) {
            return REVERSED;
        }
        return PARTIALLY_REVERSED;
    }

    /** 是否仍可被冲正（即可冲正金额 &gt; 0）。 */
    public boolean isReversible() {
        return this == POSTED || this == PARTIALLY_REVERSED;
    }
}
