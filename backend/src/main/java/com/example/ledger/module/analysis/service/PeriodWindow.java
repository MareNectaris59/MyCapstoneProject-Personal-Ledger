package com.example.ledger.module.analysis.service;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * 分析用的时间粒度窗口（《系统设计说明书》§7.6.1）。
 *
 * <p>与预算模块的 {@code PeriodWindows} 是<b>两套不同口径</b>，不要合并：
 * <ul>
 *   <li>预算：从"生效日所在窗口"起，按用户的周起始日连续铺开</li>
 *   <li>分析：按<b>自然日历</b>对齐（周一为周首、1 日为月首、1 月 1 日为年首），
 *       因为报表口径应当与人人都理解的日历一致，而不随某个用户的偏好漂移</li>
 * </ul>
 *
 * <p>粒度与周期标识格式：
 * <pre>
 *   DAY    2025-03-01     当日
 *   WEEK   2025-W09       周一 ~ 周日
 *   MONTH  2025-03        当月 1 日 ~ 末日
 *   YEAR   2025           1 月 1 日 ~ 12 月 31 日
 * </pre>
 */
public final class PeriodWindow {

    /** 周期窗口，闭区间。 */
    public record Window(String period, LocalDate start, LocalDate end) {
    }

    private PeriodWindow() {
    }

    /**
     * 解析周期标识并给出窗口。
     *
     * @param granularity DAY / WEEK / MONTH / YEAR
     * @param period      周期标识；为 null 时取"包含今天的那个周期"
     */
    public static Window of(String granularity, String period) {
        String g = normalize(granularity);
        return switch (g) {
            case "DAY" -> day(period);
            case "WEEK" -> week(period);
            case "MONTH" -> month(period);
            case "YEAR" -> year(period);
            default -> throw BizException.of(ResultCode.PARAM_INVALID,
                    "不支持的时间粒度：" + granularity);
        };
    }

    /**
     * 前一个相邻同粒度周期（环比的基期）。
     *
     * <p>周与月的环比较为特殊：
     * <ul>
     *   <li>月：直接减一个月，天数不同（2 月 28 天）由 {@code withDayOfMonth(1)} 处理</li>
     *   <li>周：减 7 天。跨年时周序号会回绕（2025-W01 的前一周是 2024-W52），
     *       因此周一并重算 period 标识，不能简单地对周序号减一</li>
     * </ul>
     */
    public static Window previous(String granularity, Window current) {
        String g = normalize(granularity);
        return switch (g) {
            case "DAY" -> day(current.start().minusDays(1).toString());
            case "WEEK" -> weekOf(current.start().minusWeeks(1));
            case "MONTH" -> month(current.start().minusMonths(1).toString().substring(0, 7));
            case "YEAR" -> year(String.valueOf(current.start().getYear() - 1));
            default -> throw BizException.of(ResultCode.PARAM_INVALID, "不支持的时间粒度：" + granularity);
        };
    }

    /**
     * 上一年度同期（同比的基期）。
     *
     * <p>周粒度按<b>周序号对齐</b>（§7.6.3）：2025-W09 的同比基期是 2024-W09，
     * 而不是"2025-W09 起始日减一年"。后者会因闰年与周分布差异落到错误的周。
     */
    public static Window samePeriodLastYear(String granularity, Window current) {
        String g = normalize(granularity);
        return switch (g) {
            case "WEEK" -> {
                WeekFields wf = WeekFields.of(Locale.UK);   // 周一为周首，符合 §7.6.1
                int week = current.start().get(wf.weekOfWeekBasedYear());
                int year = current.start().get(wf.weekBasedYear()) - 1;
                yield weekOfYearWeek(year, week);
            }
            case "YEAR" -> year(String.valueOf(current.start().getYear() - 1));
            default -> of(g, shiftYear(current.start(), -1).toString().substring(0, periodPrefixLen(g)));
        };
    }

    /**
     * 把基期窗口裁剪为与"当前周期已过天数"相同的长度（§7.6.3 的 alignWindow）。
     *
     * <p>为什么必须做：今天 3 月 15 日统计 3 月，直接与 2 月全月比会得到
     * "3 月只花了 2 月一半"的假象 —— 3 月才过了一半。
     *
     * @param base        原始基期窗口
     * @param elapsedDays 当前周期已过的天数（含今天），至少为 1
     * @return 裁剪后的窗口；若基期自然长度不足，则原样返回并把 aligned 置 false
     */
    public static Aligned align(LocalDate baseStart, LocalDate baseEnd, long elapsedDays) {
        long baseLen = baseEnd.toEpochDay() - baseStart.toEpochDay() + 1;
        if (elapsedDays >= baseLen) {
            // 基期本身比"已过天数"还短（如 3 月 30 日对齐 2 月的 28 天）：
            // 按整段计算，并如实标记未对齐，不做静默截断
            return new Aligned(baseStart, baseEnd, false);
        }
        return new Aligned(baseStart, baseStart.plusDays(elapsedDays - 1), true);
    }

    /** 对齐结果。 */
    public record Aligned(LocalDate start, LocalDate end, boolean aligned) {
    }

    // ------------------------------------------------------------------ 内部

    private static String normalize(String granularity) {
        return granularity == null ? "MONTH" : granularity.toUpperCase(Locale.ROOT);
    }

    private static int periodPrefixLen(String g) {
        return switch (g) {
            case "DAY" -> 10;    // yyyy-MM-dd
            case "MONTH" -> 7;   // yyyy-MM
            default -> 4;        // yyyy
        };
    }

    private static LocalDate shiftYear(LocalDate d, int years) {
        try {
            return d.plusYears(years);
        } catch (Exception e) {
            // 2 月 29 日减一年会落到非闰年
            return d.minusDays(1).plusYears(years).plusDays(1);
        }
    }

    private static Window day(String period) {
        LocalDate d = (period == null || period.isBlank()) ? LocalDate.now() : LocalDate.parse(period);
        return new Window(d.toString(), d, d);
    }

    private static Window month(String period) {
        LocalDate any = (period == null || period.isBlank())
                ? LocalDate.now()
                : LocalDate.parse(period + "-01");
        LocalDate start = any.withDayOfMonth(1);
        String label = String.format("%d-%02d", start.getYear(), start.getMonthValue());
        return new Window(label, start, start.withDayOfMonth(start.lengthOfMonth()));
    }

    private static Window year(String period) {
        int y = (period == null || period.isBlank()) ? LocalDate.now().getYear() : Integer.parseInt(period);
        return new Window(String.valueOf(y), LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
    }

    /** 由某个日期确定它所在的一周（周一 ~ 周日）。 */
    private static Window weekOf(LocalDate anyDay) {
        LocalDate monday = anyDay.minusDays(anyDay.getDayOfWeek().getValue() - 1L);
        return weekWindow(monday);
    }

    /** 按"周所在年 + 周序号"构造窗口，用于同比对齐。 */
    private static Window weekOfYearWeek(int year, int week) {
        WeekFields wf = WeekFields.of(Locale.UK);
        LocalDate firstOfYear = LocalDate.of(year, 1, 1);
        LocalDate monday = firstOfYear.with(wf.weekOfWeekBasedYear(), week)
                .with(wf.dayOfWeek(), 1);
        return weekWindow(monday);
    }

    private static Window weekWindow(LocalDate monday) {
        WeekFields wf = WeekFields.of(Locale.UK);
        int week = monday.get(wf.weekOfWeekBasedYear());
        int year = monday.get(wf.weekBasedYear());
        String label = String.format("%d-W%02d", year, week);
        return new Window(label, monday, monday.plusDays(6));
    }

    private static Window week(String period) {
        if (period == null || period.isBlank()) {
            return weekOf(LocalDate.now());
        }
        // 形如 2025-W09
        int dash = period.indexOf("-W");
        if (dash <= 0) {
            throw BizException.of(ResultCode.PARAM_INVALID, "周周期标识应形如 2025-W09：" + period);
        }
        int y = Integer.parseInt(period.substring(0, dash));
        int w = Integer.parseInt(period.substring(dash + 2));
        return weekOfYearWeek(y, w);
    }
}
