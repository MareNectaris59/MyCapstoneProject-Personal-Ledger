package com.example.ledger.module.budget.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预算定义（{@code budget}）。
 *
 * <p><b>注意 {@code amount} 的语义是「每期额度」，不是总预算。</b>
 * 月底额度 1000、周期为月时，每个月的可用额度都是 1000（再加上结转）。
 * 这个语义必须在实现时守住，否则很容易被当成总额一次性分配。
 *
 * <p>{@code rolloverMode} 的三种取值见《系统设计说明书》§7.5.3。
 */
@Data
@TableName("budget")
public class Budget {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** TOTAL（全部费用）/ SUBJECT（指定科目及其子树） */
    private String scopeType;

    /** scopeType = SUBJECT 时的科目 ID，必须是费用类 */
    private Long subjectId;

    /** WEEK / MONTH / CUSTOM */
    private String periodType;

    /** 自定义周期天数，仅 CUSTOM 使用 */
    private Integer periodValue;

    private LocalDate startDate;

    /** 结束日，null 表示长期有效 */
    private LocalDate endDate;

    /** 每期额度 */
    private BigDecimal amount;

    /** NONE / SURPLUS_ONLY / FULL */
    private String rolloverMode;

    private BigDecimal alertThreshold;

    private Boolean enabled;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
