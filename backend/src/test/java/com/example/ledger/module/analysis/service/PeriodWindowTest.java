package com.example.ledger.module.analysis.service;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.module.analysis.service.PeriodWindow.Aligned;
import com.example.ledger.module.analysis.service.PeriodWindow.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分析用时间窗口单元测试（设计文档 §7.6.1）。
 *
 * <p>与预算的 {@code PeriodWindows} 是两套口径，不能混：
 * 预算按用户配置的周起始日连续铺开，分析按自然日历对齐（周一起算）。
 * 这套口径直接影响同比/环比的"基期是哪一段"，算错会让报表得出
 * 完全错误的结论，而且用户很难察觉。
 */
class PeriodWindowTest {

    @Nested
    @DisplayName("of：按粒度解析周期标识")
    class Of {

        @Test
        @DisplayName("DAY：单日窗口，起止相同")
        void day() {
            Window w = PeriodWindow.of("DAY", "2026-03-15");
            assertThat(w.start()).isEqualTo("2026-03-15");
            assertThat(w.end()).isEqualTo("2026-03-15");
            assertThat(w.period()).isEqualTo("2026-03-15");
        }

        @ParameterizedTest(name = "{0} -> {1} ~ {2}")
        @CsvSource({
                "2026-03,  2026-03-01, 2026-03-31",
                "2026-02,  2026-02-01, 2026-02-28",
                "2028-02,  2028-02-01, 2028-02-29",  // 闰年
                "2026-04,  2026-04-01, 2026-04-30",
        })
        @DisplayName("MONTH：自然月，含闰年与大小月")
        void month(String period, String start, String end) {
            Window w = PeriodWindow.of("MONTH", period);
            assertThat(w.start()).isEqualTo(LocalDate.parse(start));
            assertThat(w.end()).isEqualTo(LocalDate.parse(end));
            assertThat(w.period()).isEqualTo(period);
        }

        @Test
        @DisplayName("YEAR：1 月 1 日到 12 月 31 日")
        void year() {
            Window w = PeriodWindow.of("YEAR", "2026");
            assertThat(w.start()).isEqualTo("2026-01-01");
            assertThat(w.end()).isEqualTo("2026-12-31");
        }

        @Test
        @DisplayName("WEEK：周一到周日（自然日历口径，与预算无关）")
        void week() {
            // 2026-W09 的周一是 2026-02-23
            Window w = PeriodWindow.of("WEEK", "2026-W09");
            assertThat(w.start().getDayOfWeek().getValue()).isEqualTo(1);
            assertThat(w.end().toEpochDay() - w.start().toEpochDay()).isEqualTo(6);
            assertThat(w.period()).isEqualTo("2026-W09");
        }

        @Test
        @DisplayName("周标识格式不对时拒绝，而不是静默当成某一天")
        void rejectsMalformedWeek() {
            assertThatThrownBy(() -> PeriodWindow.of("WEEK", "2026-09"))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("不支持的粒度拒绝")
        void rejectsUnsupportedGranularity() {
            assertThatThrownBy(() -> PeriodWindow.of("DECADE", "2026"))
                    .isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("previous：环比基期")
    class Previous {

        @Test
        @DisplayName("MONTH 减一个月，天数不同也能正确落到上月首末日")
        void month() {
            Window mar = PeriodWindow.of("MONTH", "2026-03");
            Window feb = PeriodWindow.previous("MONTH", mar);
            assertThat(feb.start()).isEqualTo("2026-02-01");
            assertThat(feb.end()).isEqualTo("2026-02-28");
            assertThat(feb.period()).isEqualTo("2026-02");
        }

        @Test
        @DisplayName("MONTH 跨年：1 月的上一期是去年 12 月")
        void monthCrossesYear() {
            Window jan = PeriodWindow.of("MONTH", "2026-01");
            Window prev = PeriodWindow.previous("MONTH", jan);
            assertThat(prev.start()).isEqualTo("2025-12-01");
            assertThat(prev.end()).isEqualTo("2025-12-31");
        }

        @Test
        @DisplayName("DAY 减一天，跨月跨年都对")
        void day() {
            Window d = PeriodWindow.of("DAY", "2026-01-01");
            assertThat(PeriodWindow.previous("DAY", d).start()).isEqualTo("2025-12-31");
        }

        @Test
        @DisplayName("WEEK 减 7 天，跨年时周序号会回绕（不能只对周序号减一）")
        void weekRollsBackAcrossYear() {
            // 2026-W01 的周一是 2025-12-29（自然日历口径下 1 月 1 日所在的周）
            Window firstWeek = PeriodWindow.of("WEEK", "2026-W01");
            Window prev = PeriodWindow.previous("WEEK", firstWeek);
            assertThat(prev.end()).isEqualTo(firstWeek.start().minusDays(1));
            assertThat(prev.end().toEpochDay() - prev.start().toEpochDay()).isEqualTo(6);
            // 上一期的周标识年份应当是 2025
            assertThat(prev.period()).startsWith("2025-W");
        }

        @Test
        @DisplayName("YEAR 减一年")
        void year() {
            Window y = PeriodWindow.of("YEAR", "2026");
            assertThat(PeriodWindow.previous("YEAR", y).start()).isEqualTo("2025-01-01");
        }
    }

    @Nested
    @DisplayName("samePeriodLastYear：同比基期")
    class SamePeriodLastYear {

        @Test
        @DisplayName("MONTH 取去年同月，闰日不会被平年搞崩")
        void month() {
            Window feb2028 = PeriodWindow.of("MONTH", "2028-02");
            Window base = PeriodWindow.samePeriodLastYear("MONTH", feb2028);
            assertThat(base.start()).isEqualTo("2027-02-01");
            assertThat(base.end()).isEqualTo("2027-02-28");
        }

        @Test
        @DisplayName("WEEK 按周序号对齐，而不是「起始日减一年」")
        void weekAlignsByWeekNumber() {
            // 设计意图：2025-W09 的同比基期是 2024-W09。
            // 若按"起始日减一年"会因闰年与周分布差异落到错误的周。
            Window current = PeriodWindow.of("WEEK", "2026-W09");
            Window base = PeriodWindow.samePeriodLastYear("WEEK", current);
            assertThat(base.end().toEpochDay() - base.start().toEpochDay()).isEqualTo(6);
            assertThat(base.start().getDayOfWeek().getValue()).isEqualTo(1);
            assertThat(base.start().getYear()).isEqualTo(2025);
        }

        @Test
        @DisplayName("DAY 取去年同一天")
        void day() {
            Window d = PeriodWindow.of("DAY", "2026-03-15");
            assertThat(PeriodWindow.samePeriodLastYear("DAY", d).start()).isEqualTo("2025-03-15");
        }

        @Test
        @DisplayName("YEAR 取上一年")
        void year() {
            Window y = PeriodWindow.of("YEAR", "2026");
            assertThat(PeriodWindow.samePeriodLastYear("YEAR", y).start()).isEqualTo("2025-01-01");
        }
    }

    @Nested
    @DisplayName("align：把基期裁剪成与当前周期等长（§7.6.3）")
    class Align {

        @Test
        @DisplayName("基期更长时裁剪到已过天数，并标记已对齐")
        void trimsWhenBaseIsLonger() {
            // 本月已过 10 天，上月有 28 天 -> 只取上月 1~10 日
            Aligned a = PeriodWindow.align(
                    LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), 10);
            assertThat(a.start()).isEqualTo("2026-02-01");
            assertThat(a.end()).isEqualTo("2026-02-10");
            assertThat(a.aligned()).isTrue();
        }

        @Test
        @DisplayName("已过天数超过基期长度时按整段计算，并如实标记未对齐")
        void keepsWholeBaseWhenTooShort() {
            // 3 月 30 日对齐 2 月的 28 天：基期本来就短，按整段算并告知未对齐
            Aligned a = PeriodWindow.align(
                    LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), 30);
            assertThat(a.start()).isEqualTo("2026-02-01");
            assertThat(a.end()).isEqualTo("2026-02-28");
            assertThat(a.aligned()).isFalse();
        }

        @Test
        @DisplayName("恰好等长也算已对齐")
        void exactLengthIsAligned() {
            Aligned a = PeriodWindow.align(
                    LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), 28);
            assertThat(a.aligned()).isTrue();
            assertThat(a.end()).isEqualTo("2026-02-28");
        }

        @Test
        @DisplayName("只过 1 天时基期只剩 1 天")
        void singleDay() {
            Aligned a = PeriodWindow.align(
                    LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), 1);
            assertThat(a.start()).isEqualTo(a.end());
            assertThat(a.aligned()).isTrue();
        }
    }
}
