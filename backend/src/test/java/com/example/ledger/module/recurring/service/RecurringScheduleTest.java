package com.example.ledger.module.recurring.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定期记账日期推进单元测试。
 *
 * <p>重点在**月末锚定**：房租定在 31 号时，2 月退让到 28 号之后，
 * 3 月必须回到 31 号，而不是一路停在 28 号。
 * 这个错误不会让任何接口报错，只会在几个月后表现为"房租日期越来越偏"。
 */
class RecurringScheduleTest {

    @Nested
    @DisplayName("每周")
    class Weekly {

        @Test
        @DisplayName("固定推进 7 天")
        void addsSevenDays() {
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2026, 3, 5), "WEEKLY", null))
                    .isEqualTo(LocalDate.of(2026, 3, 12));
        }

        @Test
        @DisplayName("跨月、跨年都正确")
        void crossesMonthAndYear() {
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2026, 1, 29), "WEEKLY", null))
                    .isEqualTo(LocalDate.of(2026, 2, 5));
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2026, 12, 29), "WEEKLY", null))
                    .isEqualTo(LocalDate.of(2027, 1, 5));
        }

        @Test
        @DisplayName("闰年 2 月 29 日也能正常跨")
        void leapDay() {
            // 2028 是闰年
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2028, 2, 29), "WEEKLY", null))
                    .isEqualTo(LocalDate.of(2028, 3, 7));
        }
    }

    @Nested
    @DisplayName("每月（含月末锚定）")
    class Monthly {

        @Test
        @DisplayName("普通日期直接进位")
        void simpleMonthly() {
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2026, 3, 15), "MONTHLY", 15))
                    .isEqualTo(LocalDate.of(2026, 4, 15));
        }

        @Test
        @DisplayName("锚定 31 号：2 月退让到 28 号，3 月回到 31 号")
        void anchorThirtyFirstRecoversAfterShortMonth() {
            LocalDate jan31 = LocalDate.of(2026, 1, 31);
            LocalDate feb28 = RecurringSchedule.nextAfter(jan31, "MONTHLY", 31);
            LocalDate mar31 = RecurringSchedule.nextAfter(feb28, "MONTHLY", 31);

            assertThat(feb28).isEqualTo(LocalDate.of(2026, 2, 28));
            // 关键断言：回到 31，而不是停在 28（用 plusMonths 就会错成 3/28）
            assertThat(mar31).isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        @DisplayName("锚定 31 号：4 月退让到 30 号，5 月再回到 31 号")
        void anchorThirtyFirstAcrossThirtyDayMonth() {
            LocalDate mar31 = LocalDate.of(2026, 3, 31);
            LocalDate apr30 = RecurringSchedule.nextAfter(mar31, "MONTHLY", 31);
            LocalDate may31 = RecurringSchedule.nextAfter(apr30, "MONTHLY", 31);

            assertThat(apr30).isEqualTo(LocalDate.of(2026, 4, 30));
            assertThat(may31).isEqualTo(LocalDate.of(2026, 5, 31));
        }

        @Test
        @DisplayName("闰年 2 月锚定 29 号：平年退让到 28 号")
        void anchorTwentyNinthOnLeapAndCommonYear() {
            // 2028 闰年 1 月 29 日 -> 2 月 29 日存在
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2028, 1, 29), "MONTHLY", 29))
                    .isEqualTo(LocalDate.of(2028, 2, 29));
            // 2026 平年 2 月没有 29 日 -> 退让到 28 日
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2026, 1, 29), "MONTHLY", 29))
                    .isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName("跨年：12 月进到次年 1 月")
        void crossesYear() {
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2026, 12, 31), "MONTHLY", 31))
                    .isEqualTo(LocalDate.of(2027, 1, 31));
        }

        @Test
        @DisplayName("锚定日为空时取当前执行日的日号")
        void nullAnchorUsesCurrentDay() {
            assertThat(RecurringSchedule.nextAfter(LocalDate.of(2026, 3, 15), "MONTHLY", null))
                    .isEqualTo(LocalDate.of(2026, 4, 15));
        }
    }

    @Nested
    @DisplayName("到期判定")
    class Due {

        @Test
        @DisplayName("下次执行日等于今天即到期（不等一天）")
        void todayIsDue() {
            LocalDate today = LocalDate.of(2026, 10, 20);
            assertThat(RecurringSchedule.isDue(today, today)).isTrue();
        }

        @Test
        @DisplayName("昨天到期，明天不到期")
        void yesterdayAndTomorrow() {
            LocalDate today = LocalDate.of(2026, 10, 20);
            assertThat(RecurringSchedule.isDue(today.minusDays(1), today)).isTrue();
            assertThat(RecurringSchedule.isDue(today.plusDays(1), today)).isFalse();
        }

        @Test
        @DisplayName("空的下次执行日不算到期")
        void nullIsNotDue() {
            assertThat(RecurringSchedule.isDue(null, LocalDate.now())).isFalse();
        }
    }

    @Nested
    @DisplayName("走完判定（自动停用）")
    class Finished {

        @Test
        @DisplayName("下次执行日超过结束日即为走完")
        void beyondEndDate() {
            assertThat(RecurringSchedule.isFinished(
                    LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31))).isTrue();
        }

        @Test
        @DisplayName("正好等于结束日还没走完（那一天仍要执行）")
        void equalToEndDateIsNotFinished() {
            assertThat(RecurringSchedule.isFinished(
                    LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31))).isFalse();
        }

        @Test
        @DisplayName("结束日为空表示长期有效，永不走完")
        void nullEndDateNeverFinishes() {
            assertThat(RecurringSchedule.isFinished(LocalDate.of(2099, 1, 1), null)).isFalse();
        }
    }

    @Nested
    @DisplayName("非法入参")
    class Guards {

        @ParameterizedTest
        @ValueSource(strings = {"DAILY", "YEARLY", ""})
        @DisplayName("不支持的频率直接抛异常，而不是悄悄按某种默认处理")
        void rejectsUnknownFrequency(String frequency) {
            assertThatThrownBy(() -> RecurringSchedule.nextAfter(
                    LocalDate.of(2026, 3, 1), frequency, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @CsvSource({"0", "32", "-1"})
        @DisplayName("锚定日超出 1-31 直接抛异常")
        void rejectsInvalidAnchorDay(int anchor) {
            assertThatThrownBy(() -> RecurringSchedule.nextAfter(
                    LocalDate.of(2026, 3, 1), "MONTHLY", anchor))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("当前执行日为空抛异常")
        void rejectsNullCurrent() {
            assertThatThrownBy(() -> RecurringSchedule.nextAfter(null, "MONTHLY", 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
