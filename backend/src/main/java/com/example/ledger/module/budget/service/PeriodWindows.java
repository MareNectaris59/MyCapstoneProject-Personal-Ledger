package com.example.ledger.module.budget.service;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 预算周期窗口计算（《系统设计说明书》§7.5.1）。
 *
 * <p>三种周期类型：
 * <pre>
 *   WEEK    以用户配置的周起始日为准（默认周一），窗口 7 天
 *   MONTH   当月 1 日 ~ 当月最后一天
 *   CUSTOM  固定天数窗口，从预算生效日连续铺开
 * </pre>
 *
 * <p><b>跨年周的归属约定</b>：一年不恰好包含整数个周，设计文档明确规定
 * <b>跨年周归属于包含该周起始日的那一年</b>。这条约定必须落实，否则 12 月末的
 * 周预算会与次年 1 月初重复计算。
 */
public final class PeriodWindows {

    /** 周期窗口，闭区间 [start, end]。 */
    public record Window(LocalDate start, LocalDate end, String label) {
    }

    private PeriodWindows() {
    }

    /**
     * 计算包含基准日期的周期窗口。
     *
     * @param periodType  WEEK / MONTH / CUSTOM
     * @param baseDate    基准日期
     * @param weekStart   周起始日（1=周一 … 7=周日），仅 WEEK 使用
     * @param customDays  CUSTOM 的窗口天数，仅 CUSTOM 使用
     */
    public static Window of(String periodType, LocalDate baseDate,
                            int weekStart, Integer customDays) {
        if (baseDate == null) {
            throw BizException.of(ResultCode.PARAM_INVALID, "基准日期不能为空");
        }
        return switch (periodType == null ? "" : periodType) {
            case "WEEK" -> week(baseDate, weekStart);
            case "MONTH" -> month(baseDate);
            case "CUSTOM" -> custom(baseDate, customDays);
            default -> throw BizException.of(ResultCode.PARAM_INVALID,
                    "不支持的周期类型：" + periodType);
        };
    }

    /**
     * 计算 next 周期的窗口，用于从生效日连续铺开周期行。
     *
     * <p>步长规则：
     * <ul>
     *   <li>WEEK / MONTH：从上一窗口结束日的<b>次日</b>起算，
     *       自然得到连续的周/月（含月末天数不等的月份）</li>
     *   <li>CUSTOM：固定 {@code customDays} 天，连续铺设</li>
     * </ul>
     *
     * @param previousStart 上一周期的开始日，用于生成稳定可读的 label
     */
    public static Window next(String periodType, Window previous,
                              int weekStart, Integer customDays) {
        LocalDate cursor = previous.end().plusDays(1);
        return of(periodType, cursor, weekStart, customDays);
    }

    // ------------------------------------------------------------------ WEEK

    private static Window week(LocalDate date, int weekStart) {
        int configured = (weekStart >= 1 && weekStart <= 7) ? weekStart : 1;

        // java.time 的 DayOfWeek 是 1=周一 … 7=周日，与用户的 1..7 约定一致
        int currentDow = date.getDayOfWeek().getValue();
        // 往回退到本周期起始日：差值取模保证不越过上一周
        int back = Math.floorMod(currentDow - configured, 7);
        LocalDate start = date.minusDays(back);
        LocalDate end = start.plusDays(6);

        // 跨年周归属包含"周起始日"的那一年 —— ISO 周的跨年规则不同，
        // 不能直接用 WeekFields.weekOfWeekBasedYear()，必须按本约定显式构造。
        int labelYear = start.getYear();
        int weekOfYear = weekOfYear(start, configured);
        String label = String.format("%d-W%02d", labelYear, weekOfYear);

        return new Window(start, end, label);
    }

    /**
     * 按"周起始日归属年"计算周序号。
     *
     * <p>做法：找该年 1 月 1 日所在的窗口起点作为第 1 周起点，
     * 用相差天数整除 7 得到序号。若 {@code start} 落在上一年的最后一个窗口里，
     * 其年份已被调用方取为 {@code start.getYear()}，序号按该年计算，两者自洽。
     */
    private static int weekOfYear(LocalDate start, int configured) {
        LocalDate firstOfYear = LocalDate.of(start.getYear(), 1, 1);
        int firstDow = firstOfYear.getDayOfWeek().getValue();
        int backToStart = Math.floorMod(firstDow - configured, 7);
        LocalDate firstWindowStart = firstOfYear.minusDays(backToStart);

        long days = start.toEpochDay() - firstWindowStart.toEpochDay();
        return (int) (days / 7) + 1;
    }

    // ----------------------------------------------------------------- MONTH

    private static Window month(LocalDate date) {
        LocalDate start = date.withDayOfMonth(1);
        LocalDate end = date.withDayOfMonth(date.lengthOfMonth());
        return new Window(start, end, String.format("%d-%02d", start.getYear(), start.getMonthValue()));
    }

    // ---------------------------------------------------------------- CUSTOM

    private static Window custom(LocalDate date, Integer customDays) {
        if (customDays == null || customDays <= 0) {
            throw BizException.of(ResultCode.BUDGET_AMOUNT_INVALID,
                    "自定义周期必须指定正整数天数");
        }
        LocalDate end = date.plusDays(customDays - 1L);
        // label 用起始日，保证同一窗口在任何基准日下都得到同一标识
        return new Window(date, end, date.toString());
    }

    /** 供测试与展示：把 DayOfWeek 转成用户的 1..7 约定。 */
    public static int toConfiguredDayOfWeek(DayOfWeek dow) {
        return dow.getValue();
    }
}
