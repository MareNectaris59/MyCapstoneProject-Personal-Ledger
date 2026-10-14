package com.example.ledger.module.user.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户偏好设置（{@code user_profile}），与 {@code sys_user} 一对一。
 *
 * <p>注意 {@code weekStart}：它决定周预算与周报表的窗口起点。
 * 硬编码为周一会让"本周支出"与用户直觉不符，因此做成可配置
 * （《系统设计说明书》§7.5.1）。
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 本位币，v1.0 固定 CNY */
    private String baseCurrency;

    private String currencySymbol;

    private Integer amountPrecision;

    /** 周起始日：1 = 周一 … 7 = 周日 */
    private Integer weekStart;

    /** 严格余额模式：true 时余额不足将拒绝支出/转账 */
    private Boolean strictBalanceMode;

    /** 预算预警阈值（百分比），默认 80 */
    private BigDecimal budgetWarnThreshold;

    private Boolean alertEmailEnabled;

    /** 扩展偏好配置（JSON） */
    private String preferences;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
