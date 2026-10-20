package com.example.ledger.module.ledger.service;

import com.example.ledger.common.constant.Direction;
import com.example.ledger.common.constant.LedgerState;
import com.example.ledger.common.constant.LedgerType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.module.ledger.entity.Entry;
import com.example.ledger.module.ledger.entity.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 冲正纯逻辑单元测试（设计文档 §7.4）。
 *
 * <p>这些断言原本只能靠"起服务 + 发 100 次 HTTP + 查数据库"来验证。
 * 抽成 {@link ReversalAllocator} 后，全部边界都能在毫秒级跑完，
 * 而且失败时直接指向具体规则，不用再去翻 e2e 脚本的输出。
 *
 * <p>覆盖四条最容易改错的规则：
 * <ol>
 *   <li>冲正单不能再被冲正（防套娃）</li>
 *   <li>冲正金额不得超过剩余可冲金额，且 null 表示全额</li>
 *   <li>部分冲正按比例拆分，方向取反</li>
 *   <li>拆分后借贷必须仍然平衡</li>
 * </ol>
 */
class ReversalAllocatorTest {

    // ---------------------------------------------------------------- 构造工具

    /** 造一张原凭证。amount 为原额，reversed 为已冲正额。 */
    private static Ledger ledger(LedgerType type, String amount, String reversed, LedgerState state) {
        Ledger l = new Ledger();
        l.setId(1L);
        l.setLedgerType(type);
        l.setAmount(new BigDecimal(amount));
        l.setReversedAmount(new BigDecimal(reversed));
        l.setState(state);
        return l;
    }

    /** 造一条分录。signedAmount 由 Entry.of 按方向自动算。 */
    private static Entry entry(int no, Direction dir, String amount, long subjectId) {
        return Entry.of(1L, no, subjectId, dir, new BigDecimal(amount), "测试");
    }

    /** 一张典型支出凭证：借费用 100 / 贷资产 100。 */
    private static List<Entry> expenseEntries(String amount) {
        return List.of(
                entry(1, Direction.DEBIT, amount, 10L),
                entry(2, Direction.CREDIT, amount, 20L));
    }

    private static void assertCode(Runnable action, ResultCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(expected);
    }

    // ---------------------------------------------------------------- resolveAmount

    @Nested
    @DisplayName("resolveAmount：金额解析与校验")
    class ResolveAmount {

        @Test
        @DisplayName("null 表示全额冲正，取剩余可冲金额")
        void nullMeansFullRemaining() {
            Ledger l = ledger(LedgerType.EXPENSE, "100.00", "33.33", LedgerState.PARTIALLY_REVERSED);
            assertThat(ReversalAllocator.resolveAmount(l, null)).isEqualByComparingTo("66.67");
        }

        @Test
        @DisplayName("指定金额在可冲范围内时原样返回")
        void returnsRequestedAmount() {
            Ledger l = ledger(LedgerType.EXPENSE, "100.00", "0.00", LedgerState.POSTED);
            assertThat(ReversalAllocator.resolveAmount(l, new BigDecimal("33.33")))
                    .isEqualByComparingTo("33.33");
        }

        @Test
        @DisplayName("冲正单不能再被冲正（防套娃）")
        void reversalCannotBeReversed() {
            Ledger rev = ledger(LedgerType.REVERSAL, "100.00", "0.00", LedgerState.POSTED);
            assertCode(() -> ReversalAllocator.resolveAmount(rev, new BigDecimal("10.00")),
                    ResultCode.LEDGER_STATE_INVALID);
        }

        @Test
        @DisplayName("已全额冲正的凭证拒绝再次冲正")
        void alreadyReversedIsRejected() {
            Ledger l = ledger(LedgerType.EXPENSE, "100.00", "100.00", LedgerState.REVERSED);
            assertCode(() -> ReversalAllocator.resolveAmount(l, new BigDecimal("10.00")),
                    ResultCode.LEDGER_ALREADY_REVERSED);
        }

        @Test
        @DisplayName("超出剩余可冲金额被拒绝，错误码 1404")
        void exceedingReversibleAmountIsRejected() {
            Ledger l = ledger(LedgerType.EXPENSE, "100.00", "66.67", LedgerState.PARTIALLY_REVERSED);
            // 剩余 33.33，想冲 33.34
            assertCode(() -> ReversalAllocator.resolveAmount(l, new BigDecimal("33.34")),
                    ResultCode.REVERSAL_AMOUNT_EXCEEDED);
        }

        @Test
        @DisplayName("恰好等于剩余可冲金额是允许的边界")
        void exactlyReversibleIsAllowed() {
            Ledger l = ledger(LedgerType.EXPENSE, "100.00", "66.67", LedgerState.PARTIALLY_REVERSED);
            assertThat(ReversalAllocator.resolveAmount(l, new BigDecimal("33.33")))
                    .isEqualByComparingTo("33.33");
        }

        @Test
        @DisplayName("金额非法（0 / 负数 / 三位小数）走金额校验，而不是状态校验")
        void invalidAmountUsesAmountRule() {
            Ledger l = ledger(LedgerType.EXPENSE, "100.00", "0.00", LedgerState.POSTED);
            assertCode(() -> ReversalAllocator.resolveAmount(l, BigDecimal.ZERO), ResultCode.AMOUNT_INVALID);
            assertCode(() -> ReversalAllocator.resolveAmount(l, new BigDecimal("-5")), ResultCode.AMOUNT_INVALID);
            assertCode(() -> ReversalAllocator.resolveAmount(l, new BigDecimal("1.005")), ResultCode.AMOUNT_INVALID);
        }
    }

    // ---------------------------------------------------------------- allocate

    @Nested
    @DisplayName("allocate：反向分录拆分")
    class Allocate {

        @Test
        @DisplayName("全额冲正：照抄原金额，方向取反")
        void fullReversalMirrorsAmounts() {
            List<Entry> originals = expenseEntries("100.00");
            List<Entry> reversed = ReversalAllocator.allocate(
                    2L, new BigDecimal("100.00"), originals, new BigDecimal("100.00"));

            assertThat(reversed).hasSize(2);
            assertThat(reversed.get(0).getDirection()).isEqualTo(Direction.CREDIT);
            assertThat(reversed.get(1).getDirection()).isEqualTo(Direction.DEBIT);
            assertThat(reversed.get(0).getAmount()).isEqualByComparingTo("100.00");
            assertThat(reversed.get(1).getAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("反向分录的 signedAmount 符号必须与原分录相反（统计时能自然抵消）")
        void signedAmountsCancelOut() {
            List<Entry> originals = expenseEntries("100.00");
            List<Entry> reversed = ReversalAllocator.allocate(
                    2L, new BigDecimal("100.00"), originals, new BigDecimal("100.00"));

            // 这是"不需要排除已冲正记录"这条设计的算术基础
            BigDecimal sum = originals.get(0).getSignedAmount()
                    .add(originals.get(1).getSignedAmount())
                    .add(reversed.get(0).getSignedAmount())
                    .add(reversed.get(1).getSignedAmount());
            assertThat(sum).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("部分冲正 33.33：两侧各 33.33，仍然平衡")
        void partialReversalStaysBalanced() {
            List<Entry> originals = expenseEntries("100.00");
            List<Entry> reversed = ReversalAllocator.allocate(
                    2L, new BigDecimal("100.00"), originals, new BigDecimal("33.33"));

            assertThat(reversed.get(0).getAmount()).isEqualByComparingTo("33.33");
            assertThat(reversed.get(1).getAmount()).isEqualByComparingTo("33.33");
            assertThat(debitTotal(reversed)).isEqualByComparingTo(creditTotal(reversed));
        }

        @Test
        @DisplayName("两次部分冲正 33.33 + 33.34 累计等于原额，最后一笔余额归零")
        void twoPartialReversalsAddUp() {
            // 对应 AC-05 的算术：100 = 33.33 + 33.34 + 33.33
            List<Entry> originals = expenseEntries("100.00");
            List<Entry> first = ReversalAllocator.allocate(
                    2L, new BigDecimal("100.00"), originals, new BigDecimal("33.33"));
            assertThat(first.get(0).getAmount()).isEqualByComparingTo("33.33");

            // 原凭证此刻 reversedAmount=33.33，剩余 66.67
            Ledger partially = ledger(LedgerType.EXPENSE, "100.00", "33.33", LedgerState.PARTIALLY_REVERSED);
            BigDecimal remaining = ReversalAllocator.resolveAmount(partially, null);
            assertThat(remaining).isEqualByComparingTo("66.67");

            List<Entry> second = ReversalAllocator.allocate(
                    3L, new BigDecimal("100.00"), originals, remaining);
            assertThat(debitTotal(second)).isEqualByComparingTo("66.67");
            assertThat(creditTotal(second)).isEqualByComparingTo("66.67");
        }

        @Test
        @DisplayName("多条分录（2 借 2 贷）也能保持借贷平衡")
        void multiEntryStaysBalanced() {
            // 实际业务里目前只有 2 条分录，但算法不该在扩展时悄悄失去平衡
            List<Entry> originals = List.of(
                    entry(1, Direction.DEBIT, "30.00", 10L),
                    entry(2, Direction.DEBIT, "70.00", 11L),
                    entry(3, Direction.CREDIT, "30.00", 20L),
                    entry(4, Direction.CREDIT, "70.00", 21L));

            List<Entry> reversed = ReversalAllocator.allocate(
                    2L, new BigDecimal("100.00"), originals, new BigDecimal("33.33"));

            assertThat(reversed).hasSize(4);
            assertThat(debitTotal(reversed)).isEqualByComparingTo(creditTotal(reversed));
            assertThat(debitTotal(reversed)).isEqualByComparingTo("33.33");
        }

        @Test
        @DisplayName("分录号与科目沿用原分录，只把方向反过来")
        void keepsEntryNoAndSubject() {
            List<Entry> originals = expenseEntries("100.00");
            List<Entry> reversed = ReversalAllocator.allocate(
                    9L, new BigDecimal("100.00"), originals, new BigDecimal("50.00"));

            for (int i = 0; i < originals.size(); i++) {
                assertThat(reversed.get(i).getEntryNo()).isEqualTo(originals.get(i).getEntryNo());
                assertThat(reversed.get(i).getSubjectId()).isEqualTo(originals.get(i).getSubjectId());
                assertThat(reversed.get(i).getLedgerId()).isEqualTo(9L);
            }
        }

        @Test
        @DisplayName("空分录或非正原额属于非法输入")
        void rejectsDegenerateInput() {
            assertCode(() -> ReversalAllocator.allocate(2L, new BigDecimal("100.00"),
                    List.of(), new BigDecimal("10.00")), ResultCode.PARAM_INVALID);
            assertCode(() -> ReversalAllocator.allocate(2L, BigDecimal.ZERO,
                    expenseEntries("100.00"), new BigDecimal("10.00")), ResultCode.PARAM_INVALID);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static BigDecimal debitTotal(List<Entry> entries) {
        return entries.stream()
                .filter(e -> e.getDirection() == Direction.DEBIT)
                .map(Entry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal creditTotal(List<Entry> entries) {
        return entries.stream()
                .filter(e -> e.getDirection() == Direction.CREDIT)
                .map(Entry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
