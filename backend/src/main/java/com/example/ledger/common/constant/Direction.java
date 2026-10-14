package com.example.ledger.common.constant;

/**
 * 记账方向。
 *
 * <p>借方（DEBIT）/ 贷方（CREDIT）。分录金额恒为正数，方向由本枚举表达，
 * 不使用负数金额（《系统设计说明书》§7.1）。
 */
public enum Direction {

    /** 借方 */
    DEBIT,

    /** 贷方 */
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    public boolean isDebit() {
        return this == DEBIT;
    }
}
