package com.example.ledger.module.ledger.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.ledger.common.constant.Direction;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会计分录（{@code entry}）—— <b>账务数据的唯一真相</b>。
 *
 * <p>只插入，永不更新、永不删除。任何余额、任何统计指标都必须能由本表重算得出。
 *
 * <p>两个关键设计：
 * <ol>
 *   <li>{@code amount} 恒为<b>正数</b>，方向由 {@code direction} 表达。
 *       负数金额会让"金额"与"方向"语义重叠，阅读和校验都容易出错。</li>
 *   <li>{@code signedAmount} = 借为 +amount，贷为 −amount。这个冗余字段让
 *       「科目余额 = SUM(signed_amount)」且<b>冲正自动抵消</b>——所有统计 SQL
 *       因此不需要任何"排除已冲正"的特殊逻辑，也就不会有人漏写符号导致
 *       统计静默出错（《系统设计说明书》§7.3.3）。</li>
 * </ol>
 */
@Data
@TableName("entry")
public class Entry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ledgerId;

    /** 凭证内序号，从 1 开始 */
    private Integer entryNo;

    /** 科目 ID，必须是叶子科目 */
    private Long subjectId;

    private Direction direction;

    /** 金额（恒为正数） */
    private BigDecimal amount;

    /** 带符号金额：借为 +，贷为 − */
    private BigDecimal signedAmount;

    private String summary;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /**
     * 构造一条分录，自动计算带符号金额。
     *
     * <p>这是创建分录的<b>唯一推荐入口</b>，避免有人忘了给 signedAmount 赋值。
     */
    public static Entry of(Long ledgerId, int entryNo, Long subjectId,
                           Direction direction, BigDecimal amount, String summary) {
        Entry e = new Entry();
        e.setLedgerId(ledgerId);
        e.setEntryNo(entryNo);
        e.setSubjectId(subjectId);
        e.setDirection(direction);
        e.setAmount(amount);
        e.setSignedAmount(direction.isDebit() ? amount : amount.negate());
        e.setSummary(summary);
        return e;
    }

    /**
     * 构造一条与原分录方向相反、金额相同（或按比例）的冲正分录。
     *
     * @param ledgerId  冲正单的凭证 ID
     * @param entryNo   序号
     * @param reversalAmount 冲正金额（可小于原金额，支持部分冲正）
     */
    public static Entry reversalOf(Long ledgerId, int entryNo, Entry original, BigDecimal reversalAmount) {
        return of(ledgerId, entryNo, original.getSubjectId(),
                original.getDirection().opposite(), reversalAmount, original.getSummary());
    }
}
