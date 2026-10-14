package com.example.ledger.module.reconcile.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账校验日志（{@code reconcile_log}）。
 *
 * <p>{@code diffCount} 期望恒为 0。非 0 说明缓存余额（或预算已用额）
 * 与分录汇总不一致，需要告警并重算修复（《系统设计说明书》§7.3.2、§7.7.1）。
 *
 * <p>策略为「增量校验保证及时性 + 周期性全量校验保证兜底」。
 */
@Data
@TableName("reconcile_log")
public class ReconcileLog {

    /** 借贷平衡全局校验 */
    public static final String TYPE_GLOBAL_BALANCE = "GLOBAL_BALANCE";
    /** 账户余额与分录汇总一致性校验 */
    public static final String TYPE_ACCOUNT_BALANCE = "ACCOUNT_BALANCE";
    /** 预算已用额一致性校验 */
    public static final String TYPE_BUDGET_USED = "BUDGET_USED";

    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_FIXED = "FIXED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID；null 表示全局对账 */
    private Long userId;

    private String checkType;

    private Integer ledgerCount;

    private Integer entryCount;

    /** 差异记录数（期望 0） */
    private Integer diffCount;

    private BigDecimal diffAmount;

    /** 差异详情（JSON） */
    private String detail;

    private String status;

    private Integer durationMs;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
