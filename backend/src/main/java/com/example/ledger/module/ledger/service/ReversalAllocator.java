package com.example.ledger.module.ledger.service;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.ledger.entity.Entry;
import com.example.ledger.module.ledger.entity.Ledger;
import com.example.ledger.common.constant.Direction;
import com.example.ledger.common.constant.LedgerType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 冲正的纯逻辑：金额校验与反向分录拆分。
 *
 * <p><b>为什么从 {@link LedgerCoreService} 里拆出来</b>：
 * 这两段逻辑不碰数据库，却原来是私有方法，只能靠起服务发 HTTP 请求验证。
 * 而它们恰好是最容易改错、也最该被钉死的地方 ——
 * 部分冲正的舍入差额、超额冲正的边界、冲正单不可再冲正。
 * 抽成静态纯函数后可以用毫秒级的单元测试穷举边界，
 * 不必跑一整轮端到端脚本。服务层只保留「取数据 + 事务 + 落库」。
 *
 * <p>本类不依赖任何 Spring 组件、不读时钟、不访问 mapper —— 输入相同则输出必然相同。
 */
public final class ReversalAllocator {

    private ReversalAllocator() {
    }

    /**
     * 校验并解析本次冲正金额。
     *
     * <p>规则（《系统设计说明书》§7.4）：
     * <ul>
     *   <li>冲正单本身不能再被冲正 —— 否则可无限套娃，账目无法审计</li>
     *   <li>只有 POSTED / PARTIALLY_REVERSED 且仍有可冲余额时才允许</li>
     *   <li>金额为 null 表示全额冲正，取剩余可冲金额</li>
     *   <li>冲正金额不得超过剩余可冲金额（BR-05）</li>
     * </ul>
     *
     * @param original  原凭证（调用方已确认它属于当前用户）
     * @param requested 用户指定的冲正金额；null 表示全额
     * @return 实际要冲正的金额，已通过 {@link MoneyUtil#validateAmount}
     * @throws BizException 状态不允许或金额越界
     */
    public static BigDecimal resolveAmount(Ledger original, BigDecimal requested) {
        if (original.getLedgerType() == LedgerType.REVERSAL) {
            throw BizException.of(ResultCode.LEDGER_STATE_INVALID, "冲正单不能再被冲正");
        }
        if (!original.isReversible()) {
            throw BizException.of(ResultCode.LEDGER_ALREADY_REVERSED);
        }

        BigDecimal reversible = original.reversibleAmount();
        BigDecimal amount = requested == null ? reversible : requested;
        MoneyUtil.validateAmount(amount);

        if (amount.compareTo(reversible) > 0) {
            throw BizException.of(ResultCode.REVERSAL_AMOUNT_EXCEEDED,
                    "最多可冲正 " + MoneyUtil.toPlainString(reversible));
        }
        return amount;
    }

    /**
     * 把冲正金额按比例拆分到各条原分录上，方向取反。
     *
     * <p>原凭证金额 100.00、两条分录各 100.00，用户冲正 33.33 时两条反向分录
     * 各 33.33，恰好平衡。若出现除不尽，前 n−1 条按 HALF_UP 舍入，
     * <b>最后一条用「冲正金额 − 同方向已分配合计」补齐</b>，
     * 保证冲正单自身借贷平衡（§7.4.2 R4）。
     *
     * <p>全额冲正直接照抄原分录金额，不走比例计算 —— 避免
     * {@code 100 * 100 / 100} 这类无意义的乘除引入舍入风险。
     *
     * @param reversalLedgerId 新冲正凭证的 id
     * @param originalAmount   原凭证金额（比例计算的分母）
     * @param originalEntries  原分录，按 entryNo 升序
     * @param reversalAmount   本次冲正金额
     * @return 反向分录列表，与入参一一对应且顺序一致
     */
    public static List<Entry> allocate(Long reversalLedgerId,
                                       BigDecimal originalAmount,
                                       List<Entry> originalEntries,
                                       BigDecimal reversalAmount) {
        if (originalEntries == null || originalEntries.isEmpty()) {
            throw BizException.of(ResultCode.PARAM_INVALID, "原凭证没有分录，无法冲正");
        }
        BigDecimal base = MoneyUtil.nullToZero(originalAmount);
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ResultCode.PARAM_INVALID, "原凭证金额必须大于 0");
        }

        boolean fullReversal = reversalAmount.compareTo(base) == 0;

        List<Entry> result = new ArrayList<>(originalEntries.size());
        BigDecimal debitAssigned = BigDecimal.ZERO;
        BigDecimal creditAssigned = BigDecimal.ZERO;

        for (int i = 0; i < originalEntries.size(); i++) {
            Entry src = originalEntries.get(i);
            boolean last = (i == originalEntries.size() - 1);

            BigDecimal piece;
            if (fullReversal) {
                piece = src.getAmount();
            } else if (last) {
                // 末条补齐差额：同方向已分配的金额之和从冲正金额里扣掉
                BigDecimal already = src.getDirection() == Direction.DEBIT
                        ? debitAssigned : creditAssigned;
                piece = reversalAmount.subtract(already);
            } else {
                piece = src.getAmount()
                        .multiply(reversalAmount)
                        .divide(base, MoneyUtil.SCALE, RoundingMode.HALF_UP);
            }

            if (src.getDirection() == Direction.DEBIT) {
                debitAssigned = debitAssigned.add(piece);
            } else {
                creditAssigned = creditAssigned.add(piece);
            }

            result.add(Entry.reversalOf(reversalLedgerId, src.getEntryNo(), src, piece));
        }
        return result;
    }
}
