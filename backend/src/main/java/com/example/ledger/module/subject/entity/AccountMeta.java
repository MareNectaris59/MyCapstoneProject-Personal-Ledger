package com.example.ledger.module.subject.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金账户扩展表（{@code account_meta}）。
 *
 * <p>仅 {@code subject_type = 'ASSET'} 的科目有对应行。
 *
 * <p><b>{@code balance} 是缓存，不是真相。</b>真相是 {@code entry.signed_amount}
 * 之和。之所以冗余它，是因为科目树与账户列表每次查询都实时聚合会难以接受；
 * 之所以敢冗余，是因为所有写路径都在同一事务内更新它，且每日对账任务
 * 能发现并修复任何漂移（《系统设计说明书》§7.7.1）。
 */
@Data
@TableName("account_meta")
public class AccountMeta {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联科目 ID（资产类） */
    private Long subjectId;

    /** 冗余用户 ID，便于直接按用户查全部账户 */
    private Long userId;

    /** 账户类型：BANK / WECHAT / ALIPAY / CASH / SAVING / INVEST / OTHER */
    private String accountKind;

    private String bankName;

    /** 卡号后四位。禁止存完整卡号 */
    private String cardTailNo;

    /** 期初余额 */
    private BigDecimal openingBalance;

    /** 当前余额缓存 = 期初 + Σ signed_amount，允许为负（透支） */
    private BigDecimal balance;

    /** 是否计入净资产 */
    private Boolean includeInNetWorth;

    private LocalDateTime reconciledAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
