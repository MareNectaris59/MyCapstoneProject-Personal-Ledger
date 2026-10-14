package com.example.ledger.module.ledger.dto;

import com.example.ledger.module.ledger.entity.Entry;
import com.example.ledger.module.ledger.entity.Ledger;
import com.example.ledger.module.subject.entity.Subject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 凭证详情响应。
 *
 * <p>金额字段一律以<b>字符串</b>返回（{@code "30.00"}），避免 JSON 浮点精度丢失
 * （《系统设计说明书》§6.1.3）。
 */
public record LedgerDetail(
        Long ledgerId,
        String ledgerNo,
        String ledgerType,
        String state,
        String occurredDate,
        String remark,
        String reversalOfId,
        String reversedAmount,
        List<LedgerEntryLine> entries
) {

    /** 单条分录的展示结构。 */
    public record LedgerEntryLine(
            Integer entryNo,
            String direction,
            Long subjectId,
            String subjectCode,
            String subjectName,
            String subjectType,
            String amount,
            String summary
    ) {
    }

    public static LedgerDetail of(Ledger ledger, List<Entry> entries, Map<Long, Subject> subjectById) {
        List<LedgerEntryLine> lines = entries.stream().map(e -> {
            Subject s = subjectById.get(e.getSubjectId());
            return new LedgerEntryLine(
                    e.getEntryNo(),
                    e.getDirection() == null ? null : e.getDirection().name(),
                    e.getSubjectId(),
                    s == null ? null : s.getSubjectCode(),
                    s == null ? null : s.getName(),
                    s == null || s.getSubjectType() == null ? null : s.getSubjectType().name(),
                    plain(e.getAmount()),
                    e.getSummary());
        }).toList();

        return new LedgerDetail(
                ledger.getId(),
                ledger.getLedgerNo(),
                ledger.getLedgerType() == null ? null : ledger.getLedgerType().name(),
                ledger.getState() == null ? null : ledger.getState().name(),
                ledger.getOccurredDate() == null ? null : ledger.getOccurredDate().toString(),
                ledger.getRemark(),
                ledger.getReversalOfId() == null ? null : String.valueOf(ledger.getReversalOfId()),
                plain(ledger.getReversedAmount()),
                lines);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
