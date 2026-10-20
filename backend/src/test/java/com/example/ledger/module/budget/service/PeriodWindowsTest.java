package com.example.ledger.module.budget.service;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.module.budget.service.PeriodWindows.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预算周期窗口单元测试（设计文档 §7.5.1）。
 *
 * <p>这段逻辑决定了"这笔消费算进哪个预算周期"，一旦算错，
 * 预算已用额、执行率、结转全部连带出错，而且错得很隐蔽（数字看起来都正常）。
 * 三个易错点在这里逐个钉死：
 * <ol>
 *   <li>周起始日可配置（周一/周日），往回退的天数要取模</li>
 *   <li>跨年周归属于"包含周起始日"的那一年 —— 与 ISO 周不同</li>
 *   <li>月末天数不等，next() 必须从上一窗口结束日的<b>次日</b>连续铺开</li>
 * </ol>
 */
class PeriodWindowsTest {

    private static final int MONDAY = 1;
    private static final int SUNDAY = 7;

    @Nested
    @DisplayName("WEEK：可配置周起始日")
    class Week {

        @Test
        @DisplayName("周一起始：周三落在本周一~周日")
        void mondayStart() {
            // 2026-10-21 是周三
            Window w = PeriodWindows.of("WEEK", LocalDate.of(2026, 10, 21), MONDAY, null);
            assertThat(w.start()).isEqualTo("2026-10-19"); // 周一
            assertThat(w.end()).isEqualTo("2026-10-25");   // 周日
        }

        @Test
        @DisplayName("周日起始：周三落在上周日~本周六")
        void sundayStart() {
            Window w = PeriodWindows.of("WEEK", LocalDate.of(2026, 10, 21), SUNDAY, null);
            assertThat(w.start()).isEqualTo("2026-10-18"); // 周日
            assertThat(w.end()).isEqualTo("2026-10-24");   // 周六
        }

        @Test
        @DisplayName("基准日正好是周起始日时不该往回退")
        void baseDateOnStartBoundary() {
            // 2026-10-19 是周一
            Window w = PeriodWindows.of("WEEK", LocalDate.of(2026, 10, 19), MONDAY, null);
            assertThat(w.start()).isEqualTo("2026-10-19");
            assertThat(w.end()).isEqualTo("2026-10-25");
        }

        @Test
        @DisplayName("窗口恒为 7 天且闭区间")
        void alwaysSevenDays() {
            for (int day = 1; day <= 28; day += 3) {
                Window w = PeriodWindows.of("WEEK", LocalDate.of(2026, 2, day), MONDAY, null);
                assertThat(w.end().toEpochDay() - w.start().toEpochDay()).isEqualTo(6);
            }
        }

        @Test
        @DisplayName("跨年周按「周起始日所在年」编号，与 ISO 周不同")
        void crossYearWeekBelongsToStartYear() {
            // 2026-12-31 是周四；周一起始时它属于 2026-12-28(周一) ~ 2027-01-03(周日)
            Window w = PeriodWindows.of("WEEK", LocalDate.of(2026, 12, 31), MONDAY, null);
            assertThat(w.start()).isEqualTo("2026-12-28");
            assertThat(w.end()).isEqualTo("2027-01-03");
            // 周起始日在 2026 年，所以标签年份必须是 2026
            assertThat(w.label()).startsWith("2026-W");
        }

        @Test
        @DisplayName("跨年但周起始日在次年时，标签年份取次年")
        void crossYearWeekBelongsToNextYear() {
            // 2027-01-01 是周五；周一起始时本周从 2026-12-28 开始 -> 仍属 2026
            // 而 2027-01-04(周一) 那一周起，标签应为 2027-W01
            Window w = PeriodWindows.of("WEEK", LocalDate.of(2027, 1, 5), MONDAY, null);
            assertThat(w.label()).startsWith("2027-W");
        }

        @Test
        @DisplayName("周起始日传非法值时回退到周一")
        void invalidWeekStartFallsBackToMonday() {
            Window bad = PeriodWindows.of("WEEK", LocalDate.of(2026, 10, 21), 0, null);
            Window good = PeriodWindows.of("WEEK", LocalDate.of(2026, 10, 21), MONDAY, null);
            assertThat(bad.start()).isEqualTo(good.start());
        }
    }

    @Nested
    @DisplayName("MONTH：自然月 1 日到月末")
    class Month {

        @ParameterizedTest(name = "{0} -> {1} ~ {2}")
        @CsvSource({
                "2026-01-15, 2026-01-01, 2026-01-31",
                "2026-02-10, 2026-02-01, 2026-02-28",  // 平年
                "2028-02-10, 2028-02-01, 2028-02-29",  // 闰年
                "2026-04-30, 2026-04-01, 2026-04-30",  // 30 天且当月最后一天
                "2026-12-31, 2026-12-01, 2026-12-31",
        })
        @DisplayName("含闰年与大小月的月末")
        void monthBoundaries(String base, String start, String end) {
            Window w = PeriodWindows.of("MONTH", LocalDate.parse(base), MONDAY, null);
            assertThat(w.start()).isEqualTo(LocalDate.parse(start));
            assertThat(w.end()).isEqualTo(LocalDate.parse(end));
            assertThat(w.label()).isEqualTo(start.substring(0, 7));
        }
    }

    @Nested
    @DisplayName("CUSTOM：固定天数窗口")
    class Custom {

        @Test
        @DisplayName("N 天窗口是闭区间，末日在起始日 + N-1")
        void customWindow() {
            Window w = PeriodWindows.of("CUSTOM", LocalDate.of(2026, 10, 5), MONDAY, 10);
            assertThat(w.start()).isEqualTo("2026-10-05");
            assertThat(w.end()).isEqualTo("2026-10-14");
            // label 用起始日，保证同一窗口在任何基准日下标识一致
            assertThat(w.label()).isEqualTo("2026-10-05");
        }

        @Test
        @DisplayName("天数为空或非正数应被拒绝")
        void rejectsInvalidDays() {
            assertThatThrownBy(() -> PeriodWindows.of("CUSTOM", LocalDate.of(2026, 10, 5), MONDAY, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getResultCode())
                    .isEqualTo(ResultCode.BUDGET_AMOUNT_INVALID);
            assertThatThrownBy(() -> PeriodWindows.of("CUSTOM", LocalDate.of(2026, 10, 5), MONDAY, 0))
                    .isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("next：从上一窗口结束日次日连续铺开")
    class Next {

        @Test
        @DisplayName("WEEK 连续且不重叠")
        void weekIsContiguous() {
            Window first = PeriodWindows.of("WEEK", LocalDate.of(2026, 10, 21), MONDAY, null);
            Window second = PeriodWindows.next("WEEK", first, MONDAY, null);
            assertThat(second.start()).isEqualTo(first.end().plusDays(1));
            assertThat(second.end().toEpochDay() - second.start().toEpochDay()).isEqualTo(6);
        }

        @Test
        @DisplayName("MONTH 连续：从 1 月末跨到 2 月 1 日")
        void monthIsContiguous() {
            Window jan = PeriodWindows.of("MONTH", LocalDate.of(2026, 1, 15), MONDAY, null);
            Window feb = PeriodWindows.next("MONTH", jan, MONDAY, null);
            assertThat(feb.start()).isEqualTo("2026-02-01");
            assertThat(feb.end()).isEqualTo("2026-02-28");
        }

        @Test
        @DisplayName("跨年连续：12 月之后是次年 1 月")
        void monthCrossesYear() {
            Window dec = PeriodWindows.of("MONTH", LocalDate.of(2026, 12, 10), MONDAY, null);
            Window next = PeriodWindows.next("MONTH", dec, MONDAY, null);
            assertThat(next.start()).isEqualTo("2027-01-01");
        }

        @Test
        @DisplayName("CUSTOM 连续且长度稳定")
        void customIsContiguous() {
            Window first = PeriodWindows.of("CUSTOM", LocalDate.of(2026, 10, 1), MONDAY, 7);
            Window second = PeriodWindows.next("CUSTOM", first, MONDAY, 7);
            assertThat(second.start()).isEqualTo("2026-10-08");
            assertThat(second.end()).isEqualTo("2026-10-14");
        }
    }

    @Nested
    @DisplayName("参数守卫")
    class Guards {

        @Test
        @DisplayName("基准日期为空、周期类型未知都拒绝")
        void rejectsBadInput() {
            assertThatThrownBy(() -> PeriodWindows.of("WEEK", null, MONDAY, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getResultCode())
                    .isEqualTo(ResultCode.PARAM_INVALID);
            assertThatThrownBy(() -> PeriodWindows.of("YEARLY", LocalDate.now(), MONDAY, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getResultCode())
                    .isEqualTo(ResultCode.PARAM_INVALID);
        }

        @Test
        @DisplayName("toConfiguredDayOfWeek 与 java.time 的 1..7 约定一致")
        void configuredDayOfWeekMatchesJavaTime() {
            assertThat(PeriodWindows.toConfiguredDayOfWeek(java.time.DayOfWeek.MONDAY)).isEqualTo(1);
            assertThat(PeriodWindows.toConfiguredDayOfWeek(java.time.DayOfWeek.SUNDAY)).isEqualTo(7);
        }
    }
}
