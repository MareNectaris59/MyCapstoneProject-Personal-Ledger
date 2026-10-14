package com.example.ledger.module.ledger.service;

import com.example.ledger.common.constant.Direction;
import com.example.ledger.common.constant.LedgerType;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.ledger.entity.Entry;
import com.example.ledger.module.subject.entity.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 借贷平衡校验器 —— 复式记账的守门人。
 *
 * <p>实现《系统设计说明书》§7.3.1 的 C1–C7 约束。
 *
 * <p>这是<b>纯计算组件</b>：不依赖数据库、Redis、Web 层，因此可以脱离 Spring
 * 上下文直接做单元测试。建议对"测试先行"——它的正确性判据明确且可枚举
 * （平衡 / 不平衡 / 非叶子 / 方向非法 / 零金额 / 越权）。
 */
@Component
public class BalanceValidator {

    private static final Logger log = LoggerFactory.getLogger(BalanceValidator.class);

    /**
     * 校验一批分录是否构成合法凭证。
     *
     * @param ledgerType 凭证类型，决定适用的科目组合规则
     * @param entries    待校验分录（已构造、尚未入库）
     * @param subjects   分录涉及科目的完整信息（由 Mapper 查出）
     * @param userId     当前登录用户，用于越权校验
     * @throws BizException 任一约束不满足时抛出，调用方事务回滚
     */
    public void validate(LedgerType ledgerType, List<Entry> entries,
                         List<Subject> subjects, Long userId) {

        // C1: 至少两条分录 —— 单条分录不构成复式记账
        if (entries == null || entries.size() < 2) {
            throw BizException.of(ResultCode.ENTRY_NOT_BALANCED,
                    "一笔业务至少需要两条分录，当前 " + (entries == null ? 0 : entries.size()) + " 条");
        }

        // C3 + C7: 金额为正、精度不超过两位
        for (Entry e : entries) {
            MoneyUtil.validateAmount(e.getAmount());
        }

        // C4 + C5: 科目存在、属于当前用户、必须是叶子科目
        Map<Long, Subject> subjectById = indexSubjects(entries, subjects, userId);

        // C2: 借贷平衡 —— 核心约束
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Entry e : entries) {
            if (e.getDirection() == Direction.DEBIT) {
                debit = debit.add(e.getAmount());
            } else {
                credit = credit.add(e.getAmount());
            }
        }
        if (debit.compareTo(credit) != 0) {
            // error 级：走到这里说明分录构造逻辑有 bug，不是用户输入问题
            log.error("借贷不平衡！debit={} credit={} entries={}",
                    debit.toPlainString(), credit.toPlainString(), entries.size());
            throw BizException.of(ResultCode.ENTRY_NOT_BALANCED,
                    "借方 " + debit.toPlainString() + " 与贷方 " + credit.toPlainString() + " 不相等");
        }

        // C6: 科目组合符合凭证类型
        validateComposition(ledgerType, entries, subjectById);
    }

    /**
     * 建立 subjectId → Subject 索引，并顺带完成 C4/C5 校验。
     */
    private Map<Long, Subject> indexSubjects(List<Entry> entries, List<Subject> subjects, Long userId) {
        Set<Long> referencedIds = new HashSet<>();
        for (Entry e : entries) {
            referencedIds.add(e.getSubjectId());
        }

        Map<Long, Subject> byId = new HashMap<>();
        if (subjects != null) {
            for (Subject s : subjects) {
                byId.put(s.getId(), s);
            }
        }

        if (byId.size() != referencedIds.size()) {
            throw BizException.of(ResultCode.SUBJECT_NOT_FOUND, "分录引用了不存在的科目");
        }

        for (Subject s : byId.values()) {
            if (!s.getUserId().equals(userId)) {
                // 越权：绝不能允许对他人的科目记账
                throw BizException.of(ResultCode.NO_PERMISSION, "科目不属于当前用户");
            }
            if (!Boolean.TRUE.equals(s.getIsLeaf())) {
                throw BizException.of(ResultCode.SUBJECT_NOT_LEAF,
                        "科目「" + s.getName() + "」不是最末级科目，无法记账");
            }
        }
        return byId;
    }

    /**
     * 校验分录的科目组合是否符合业务语义（§7.3.1 C6）。
     *
     * <p>按凭证类型枚举允许的组合：
     * <ul>
     *   <li>EXPENSE  —— 借 费用 + 贷 资产</li>
     *   <li>INCOME   —— 借 资产 + 贷 收入</li>
     *   <li>TRANSFER —— 借 资产 + 贷 资产，且两个科目不同</li>
     *   <li>OPENING  —— 借 资产 + 贷 权益</li>
     *   <li>REVERSAL —— <b>跳过本表校验</b>。冲正分录方向天然相反（如贷费用），
     *       按正常规则校验会被误拒。冲正的正确性改由
     *       「与原分录逐条方向相反且金额相等」保证，见 LedgerCoreService.reverse</li>
     * </ul>
     */
    private void validateComposition(LedgerType ledgerType, List<Entry> entries,
                                    Map<Long, Subject> subjectById) {
        if (ledgerType == LedgerType.REVERSAL) {
            return;
        }

        if (entries.size() != 2) {
            // v1.0 只支持一借一贷。凭证/分录分离的模型本身支持一借多贷，
            // 扩展"混合支付"时只需放宽这里，表结构无需改动。
            throw BizException.of(ResultCode.SUBJECT_TYPE_INVALID,
                    "该类型凭证必须恰好两条分录，当前 " + entries.size() + " 条");
        }

        Entry debitEntry = findEntry(entries, Direction.DEBIT);
        Entry creditEntry = findEntry(entries, Direction.CREDIT);
        SubjectType debitType = subjectById.get(debitEntry.getSubjectId()).getSubjectType();
        SubjectType creditType = subjectById.get(creditEntry.getSubjectId()).getSubjectType();

        boolean compositionOk = switch (ledgerType) {
            case EXPENSE -> debitType == SubjectType.EXPENSE && creditType == SubjectType.ASSET;
            case INCOME -> debitType == SubjectType.ASSET && creditType == SubjectType.INCOME;
            case TRANSFER -> debitType == SubjectType.ASSET && creditType == SubjectType.ASSET;
            case OPENING -> debitType == SubjectType.ASSET && creditType == SubjectType.EQUITY;
            default -> false;
        };
        if (!compositionOk) {
            throw BizException.of(ResultCode.SUBJECT_TYPE_INVALID,
                    "科目组合不符合该凭证类型的要求（借=" + debitType + "，贷=" + creditType + "）");
        }

        if (ledgerType == LedgerType.TRANSFER
                && debitEntry.getSubjectId().equals(creditEntry.getSubjectId())) {
            throw BizException.of(ResultCode.TRANSFER_SAME_ACCOUNT);
        }
    }

    /** 取指定方向的那条分录；不存在时视为组合非法。 */
    private Entry findEntry(List<Entry> entries, Direction direction) {
        for (Entry e : entries) {
            if (e.getDirection() == direction) {
                return e;
            }
        }
        throw BizException.of(ResultCode.SUBJECT_TYPE_INVALID,
                "凭证缺少" + (direction == Direction.DEBIT ? "借" : "贷") + "方分录");
    }
}
