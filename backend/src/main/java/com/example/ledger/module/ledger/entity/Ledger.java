package com.example.ledger.module.ledger.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.ledger.common.constant.LedgerState;
import com.example.ledger.common.constant.LedgerType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 记账凭证（{@code ledger}），一笔业务一条记录。
 *
 * <p><b>该表只插入并更新状态字段，永不删除。</b>纠错一律通过新增反向分录实现
 * （《系统设计说明书》§7.4）。
 *
 * <p>刻意把 {@code occurredDate} 设计为 {@link LocalDate} 而非 {@code LocalDateTime}：
 * 若用 DATETIME 且以 UTC 存储，"3 月 1 日 00:30 记的账"在东八区读出来会变成
 * 2 月 28 日，导致月度统计错位。用 DATE 直接消除时区歧义。
 */
@Data
@TableName("ledger")
public class Ledger {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 凭证号，格式 LE + yyyyMMdd + 3 位序号 */
    private String ledgerNo;

    private Long userId;

    private LedgerType ledgerType;

    /** CREATED / POSTED / PARTIALLY_REVERSED / REVERSED */
    private LedgerState state;

    /** 凭证金额（借方合计 = 贷方合计） */
    private BigDecimal amount;

    /** 已冲正金额（累计）。恒有 0 ≤ reversedAmount ≤ amount */
    private BigDecimal reversedAmount;

    /** 冲正单指向的原凭证 ID；非冲正单为 null */
    private Long reversalOfId;

    /** 记账业务日期，统计的主过滤条件 */
    private LocalDate occurredDate;

    private LocalDateTime occurredAt;

    private String remark;

    /** 冲正原因（仅冲正单） */
    private String reversalReason;

    /** 幂等键（客户端 UUID），唯一索引兜底防重复记账 */
    private String idempotencyKey;

    private String requestId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    private LocalDateTime postedAt;

    // ---------- 派生属性 ----------

    /** 剩余可冲正金额 = amount − reversedAmount。 */
    public BigDecimal reversibleAmount() {
        BigDecimal base = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal done = reversedAmount == null ? BigDecimal.ZERO : reversedAmount;
        return base.subtract(done);
    }

    /** 是否仍可被冲正。 */
    public boolean isReversible() {
        return state != null && state.isReversible()
                && reversibleAmount().compareTo(BigDecimal.ZERO) > 0;
    }
}
