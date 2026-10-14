package com.example.ledger.module.budget.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预算周期实例（{@code budget_period}），预算的"账页"。
 *
 * <p>结转递推（《系统设计说明书》§7.5.3）：
 * <pre>
 * available = baseAmount + carryIn
 * remaining = available − usedAmount
 * executionRate = usedAmount / available × 100%
 * </pre>
 *
 * <p><b>{@code usedAmount} 是缓存，且采用「每次由 entry 重算」而非增量累加。</b>
 * 冲正、补记历史账目都会让累加值漂移且无法自愈，重算则永远正确。
 * {@code dirtySince} 记录最早的待重算记账日期，供结算任务界定级联范围。
 */
@Data
@TableName("budget_period")
public class BudgetPeriod {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long budgetId;

    private Long userId;

    /** 周期标识，如 2025-03 / 2025-W09 */
    private String periodLabel;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 本期基础额度 */
    private BigDecimal baseAmount;

    /** 期初结转额（可为负） */
    private BigDecimal carryIn;

    /** 被下限截断的部分，用于如实展示"累计超支 X 元" */
    private BigDecimal carryFloor;

    /** 本期可用额 = baseAmount + carryIn */
    private BigDecimal availableAmount;

    /** 本期已用额（缓存，由 entry 重算） */
    private BigDecimal usedAmount;

    /** 执行率（%），可用额为 0 时为 null——不返回 0，也不返回 ∞ */
    private BigDecimal executionRate;

    /** OPEN / SETTLED */
    private String settleState;

    private LocalDateTime settledAt;

    /** 待重算标记：最早的脏记账日期，null 表示数据最新 */
    private LocalDate dirtySince;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    /** 本期结余 = availableAmount − usedAmount，可为负。 */
    public BigDecimal remaining() {
        BigDecimal available = availableAmount == null ? BigDecimal.ZERO : availableAmount;
        BigDecimal used = usedAmount == null ? BigDecimal.ZERO : usedAmount;
        return available.subtract(used);
    }
}
