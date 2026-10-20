package com.example.ledger.module.recurring.service;

import java.time.LocalDate;

/**
 * 定期记账的日期推进规则 —— 纯函数，不碰数据库。
 *
 * <p>抽成独立类的理由和 {@code ReversalAllocator} 一样：这是"算错也不会报错"的
 * 逻辑。日期推错一天，账目照记、接口照返回 200，只是钱记在了错误的日期上，
 * 而且往往几周后才被发现。所以必须能单独穷举测试。
 *
 * <p><b>核心难点：月末锚定。</b>
 *
 * <p>月付的房租常定在 31 号。若直接用 {@code plusMonths(1)} 逐月推进：
 * <pre>
 *   1/31 --plusMonths(1)--> 2/28 --plusMonths(1)--> 3/28   ← 越走越偏，3 月就该是 31 号
 * </pre>
 * 因为 {@code plusMonths} 每次都以"当前值"为基准，第一次被夹到 28 之后，
 * 28 就丢了"原本是 31"的信息。
 *
 * <p>正确做法是记住最初约定的日期（锚定日），每次推进都以锚定日为目标：
 * <pre>
 *   锚定 31：1/31 -> 2/28 -> 3/31 -> 4/30 -> 5/31   ← 只在当月没有 31 号时退让
 * </pre>
 * 这就是 {@code anchor_day} 这一列存在的全部理由。
 */
public final class RecurringSchedule {

    /** 单次补跑最多生成多少条，防止应用停机很久后一次刷出上千条 */
    public static final int MAX_CATCH_UP = 366;

    private RecurringSchedule() {
    }

    /**
     * 计算下一次执行日。
     *
     * @param current   当前执行日
     * @param frequency WEEKLY 或 MONTHLY
     * @param anchorDay 月度锚定日（1-31）；WEEKLY 忽略，可为 null
     * @return 下一次执行日
     * @throws IllegalArgumentException 频率非法
     */
    public static LocalDate nextAfter(LocalDate current, String frequency, Integer anchorDay) {
        if (current == null) {
            throw new IllegalArgumentException("当前执行日不能为空");
        }
        if ("WEEKLY".equals(frequency)) {
            return current.plusWeeks(1);
        }
        if ("MONTHLY".equals(frequency)) {
            int anchor = anchorDay == null ? current.getDayOfMonth() : anchorDay;
            if (anchor < 1 || anchor > 31) {
                throw new IllegalArgumentException("锚定日必须在 1-31 之间：" + anchor);
            }
            LocalDate nextMonth = current.plusMonths(1);
            // 当月没有锚定日时（如 2 月没有 31 号）退让到当月最后一天
            int day = Math.min(anchor, nextMonth.lengthOfMonth());
            return nextMonth.withDayOfMonth(day);
        }
        throw new IllegalArgumentException("不支持的执行频率：" + frequency);
    }

    /**
     * 该规则是否已到期。
     *
     * <p>用 {@code !isAfter} 而不是 {@code isBefore}：</p>
     * <p>{@code next_run_date == today} 的那天就应当执行，等一天就变成滞后一天。</p>
     */
    public static boolean isDue(LocalDate nextRunDate, LocalDate today) {
        return nextRunDate != null && !nextRunDate.isAfter(today);
    }

    /**
     * 该规则是否已经走完（下次执行日超过结束日）。
     *
     * <p>用于在补跑结束后自动停用，避免每天都扫到一条永远不该再执行的规则。
     */
    public static boolean isFinished(LocalDate nextRunDate, LocalDate endDate) {
        return endDate != null && nextRunDate != null && nextRunDate.isAfter(endDate);
    }
}
