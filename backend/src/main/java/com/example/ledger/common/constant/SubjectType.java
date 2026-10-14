package com.example.ledger.common.constant;

/**
 * 会计科目类型。
 *
 * <p>资金账户与消费分类共处一张 {@code subject} 表，靠本枚举区分
 * （《系统设计说明书》§5.1.2）。五类科目的"正常余额方向"固定：
 *
 * <pre>
 * 资产 ASSET      借方   增加记借、减少记贷
 * 负债 LIABILITY  贷方   增加记贷、减少记借
 * 权益 EQUITY     贷方   增加记贷、减少记借
 * 收入 INCOME     贷方   只允许贷记
 * 费用 EXPENSE    借方   只允许借记
 * </pre>
 */
public enum SubjectType {

    /** 资产：银行卡、微信、支付宝、现金、储蓄 */
    ASSET("1", Direction.DEBIT),

    /** 负债：信用卡等（v1.0 预留，模板不生成、界面不开放） */
    LIABILITY("2", Direction.CREDIT),

    /** 权益：v1.0 仅用于期初建账轧差 */
    EQUITY("3", Direction.CREDIT),

    /** 收入：工资、奖金、利息 */
    INCOME("4", Direction.CREDIT),

    /** 费用：餐饮、交通、娱乐等消费分类 */
    EXPENSE("5", Direction.DEBIT);

    /** 科目编码的类型前缀 */
    private final String codePrefix;

    /** 正常余额方向 */
    private final Direction normalSide;

    SubjectType(String codePrefix, Direction normalSide) {
        this.codePrefix = codePrefix;
        this.normalSide = normalSide;
    }

    public String getCodePrefix() {
        return codePrefix;
    }

    public Direction getNormalSide() {
        return normalSide;
    }

    /** 是否资产类（决定是否需要 {@code account_meta} 扩展行）。 */
    public boolean isAsset() {
        return this == ASSET;
    }

    /** 是否费用类（只有费用类可被设置预算，BR-11）。 */
    public boolean isExpense() {
        return this == EXPENSE;
    }

    /**
     * 指定方向的记账是否会使本科目金额「增加」。
     *
     * <p>用于「借 餐饲 / 贷 支付宝」这类分录的语义判断与余额展示换算。
     */
    public boolean increasesOn(Direction direction) {
        return this.normalSide == direction;
    }
}
