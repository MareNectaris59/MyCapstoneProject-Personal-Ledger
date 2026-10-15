package com.example.ledger.module.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 预算模块的请求与响应体。
 *
 * <p>与全项目一致的约定（《系统设计说明书》§6.1.4）：
 * <ul>
 *   <li>金额用 {@code String} 传输，避免 JSON 浮点丢精度</li>
 *   <li>业务错误走 HTTP 200 + 非零 {@code code}</li>
 *   <li>比率为 {@code String}（如 {@code "90.00"}）；<b>分母为 0 时为 {@code null}，
 *       不是 0 也不是 ∞</b> —— 前端据此显示"—"而不是"0%"</li>
 * </ul>
 */
public final class BudgetDtos {

    private BudgetDtos() {
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 新建预算。
     *
     * <p>没有 {@code rolloverEnabled} 字段：是否结转由 {@code rolloverMode} 表达
     * （{@code NONE} 即不结转）。两个字段表达同一件事会漂移，
     * 表结构也只保留 {@code rollover_mode}。
     */
    public record CreateRequest(
            String name,
            /** TOTAL / SUBJECT */
            String scopeType,
            /** scopeType = SUBJECT 时必填 */
            Long subjectId,
            /** WEEK / MONTH / CUSTOM */
            String periodType,
            /** CUSTOM 时的天数 */
            Integer periodValue,
            LocalDate startDate,
            /** 可空，表示长期有效 */
            LocalDate endDate,
            /** 每期额度 */
            BigDecimal amount,
            /** NONE / SURPLUS_ONLY / FULL，可空，默认 SURPLUS_ONLY */
            String rolloverMode,
            /** 预警阈值百分比，可空，默认 80.00 */
            BigDecimal alertThreshold
    ) {
    }

    /** 修改预算。只允许改额度、阈值、结转模式与启用状态 —— 周期结构不可变。 */
    public record UpdateRequest(
            BigDecimal amount,
            BigDecimal alertThreshold,
            String rolloverMode,
            Boolean enabled,
            String name
    ) {
    }

    // ------------------------------------------------------------------ 响应

    /**
     * 预算主视图。
     *
     * @param period        当前周期（按今天落在哪个周期窗口定位；预算未生效时为 null）
     * @param totalPeriods  已生成的周期数
     */
    public record BudgetView(
            Long id,
            String name,
            String scopeType,
            Long subjectId,
            String subjectName,
            String periodType,
            Integer periodValue,
            LocalDate startDate,
            LocalDate endDate,
            String amount,
            String rolloverMode,
            String alertThreshold,
            boolean enabled,
            PeriodView period,
            int totalPeriods
    ) {
    }

    /**
     * 单个周期的执行情况。
     *
     * @param remaining    本期结余 = available − used，<b>可为负</b>
     * @param executionRate 执行率百分比字符串；available = 0 时为 null
     * @param carryFloor   被下限截断的部分，用于如实展示"累计超支 X 元"
     * @param overspent    available 被截断为 0 时展示给用户的累计超支额（否则为 null）
     */
    public record PeriodView(
            Long id,
            String periodLabel,
            LocalDate startDate,
            LocalDate endDate,
            String baseAmount,
            String carryIn,
            String availableAmount,
            String usedAmount,
            String remaining,
            String executionRate,
            String carryFloor,
            String overspent,
            String settleState
    ) {
    }

    /** 预算详情：主视图 + 全部周期的趋势序列（供图表）。 */
    public record BudgetDetail(
            BudgetView budget,
            List<PeriodView> periods
    ) {
    }

    /**
     * 当前周期执行情况（{@code /budgets/{id}/execution}）。
     *
     * @param trend 最近若干周期的执行率序列，顺序为由早到晚
     */
    public record ExecutionView(
            BudgetView budget,
            PeriodView current,
            List<PeriodView> trend,
            List<BudgetAlertView> alerts
    ) {
    }

    /** 预警记录。 */
    public record BudgetAlertView(
            Long id,
            Long budgetId,
            String budgetName,
            Long budgetPeriodId,
            String alertLevel,
            LocalDate alertDate,
            String executionRate,
            String usedAmount,
            String availableAmount,
            String message,
            boolean read
    ) {
    }
}
