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
 * 预算预警记录（{@code budget_alert}）。
 *
 * <p>唯一索引 {@code uk_period_level_date} 保证同一周期同级别每天最多一条，
 * 防止每次记账都插入重复预警（《系统设计说明书》§7.5.5）。
 */
@Data
@TableName("budget_alert")
public class BudgetAlert {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long budgetId;

    private Long budgetPeriodId;

    /** INFO / WARNING / DANGER */
    private String alertLevel;

    private LocalDate alertDate;

    private BigDecimal executionRate;

    private BigDecimal usedAmount;

    private BigDecimal availableAmount;

    private String message;

    private Boolean isRead;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
