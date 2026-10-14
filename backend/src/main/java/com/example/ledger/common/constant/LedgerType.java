package com.example.ledger.common.constant;

/**
 * 记账凭证类型。
 */
public enum LedgerType {

    /** 支出：借 费用科目 / 贷 资产科目 */
    EXPENSE,

    /** 收入：借 资产科目 / 贷 收入科目 */
    INCOME,

    /** 转账：借 转入资产科目 / 贷 转出资产科目（不影响预算，见 BR-04） */
    TRANSFER,

    /** 期初建账：借 资产科目 / 贷 权益·期初余额 */
    OPENING,

    /** 冲正：分录方向与原凭证逐条相反，金额相等 */
    REVERSAL
}
