package com.example.ledger.module.analysis.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 消费分析的请求与响应体（《系统设计说明书》§6.2.6 / §7.6）。
 *
 * <p>项目统一约定：金额与比率都用 {@code String} 传输，避免 JSON 浮点丢精度。
 * 比率为 {@code null} 时前端展示"—"，<b>绝不返回 0 或 ∞</b>（§7.6.3 的零基期处理）。
 */
public final class AnalysisDtos {

    private AnalysisDtos() {
    }

    // ------------------------------------------------------------------ 总览

    /**
     * 期间总览。
     *
     * @param income    期间收入合计
     * @param expense   期间支出合计
     * @param balance   期间结余 = income − expense
     * @param netWorth  截至 endDate 的净资产（资产 + 负债 + 权益 的 signed_amount 合计）
     */
    public record Overview(
            LocalDate startDate,
            LocalDate endDate,
            String income,
            String expense,
            String balance,
            String netWorth
    ) {
    }

    // ------------------------------------------------------------------ 占比

    /**
     * 类别占比的一项。
     *
     * @param share 占比百分比字符串；分母为 0 时全部为 "0.00"（§7.6.4）
     */
    public record CategoryShareItem(
            Long subjectId,
            String subjectName,
            String amount,
            String share
    ) {
    }

    /**
     * 类别占比。
     *
     * @param total    分母（期间费用合计）
     * @param adjusted 是否对尾差做过调整（把差额并入最大项，保证饼图合计 100%）
     */
    public record CategoryShare(
            LocalDate startDate,
            LocalDate endDate,
            int level,
            String total,
            boolean adjusted,
            List<CategoryShareItem> items
    ) {
    }

    // ------------------------------------------------------------------ 趋势

    public record TrendPoint(
            String period,
            String amount
    ) {
    }

    /**
     * 消费趋势。
     *
     * <p>{@code points} 只包含<b>有数据</b>的周期，缺失周期不补 0 ——
     * 补 0 会让折线图出现"某月突然归零"的假象，而真相是那月没记账。
     * 前端若要补点应自行决定，后端不替它编造数据。
     */
    public record Trend(
            String granularity,
            LocalDate startDate,
            LocalDate endDate,
            String total,
            List<TrendPoint> points
    ) {
    }

    // ------------------------------------------------------------------ 同环比

    /**
     * 一个基期的对比结果。
     *
     * @param rateAvailable 基期为 0、负值或本期无数据时为 false，此时 {@code rate} 为 null
     * @param note          不可用时的原因说明，供前端如实展示
     */
    public record BaseComparison(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            String amount,
            String diff,
            String rate,
            boolean rateAvailable,
            String note
    ) {
    }

    /**
     * 同比 + 环比（同时返回，便于同一张图对比，§6.2.6）。
     *
     * @param windowAligned 基期窗口是否成功按当前周期已过天数对齐
     * @param alignNote     未对齐时的说明，前端须如实告知用户（§7.6.3 边界处理）
     */
    public record Compare(
            String granularity,
            String currentPeriod,
            LocalDate currentStart,
            LocalDate currentEnd,
            String currentAmount,
            BaseComparison mom,
            BaseComparison yoy,
            boolean windowAligned,
            String alignNote
    ) {
    }

    // ------------------------------------------------------------------ 预算执行

    /** 预算执行分析的一项（预算 vs 实际，单周期）。 */
    public record BudgetExecutionPoint(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            String availableAmount,
            String usedAmount,
            String executionRate
    ) {
    }

    /**
     * 预算执行分析：预算 vs 实际，多周期序列。
     *
     * <p>数据源是 {@code budget_period} 的结算结果，与 {@code /budgets/{id}/periods}
     * 同源；这里只返回图表需要的字段，并可按 {@code periodCount} 截取最近若干期。
     */
    public record BudgetExecution(
            Long budgetId,
            String budgetName,
            String scopeType,
            String subjectName,
            String baseAmount,
            List<BudgetExecutionPoint> points
    ) {
    }

    // ------------------------------------------------------------------ 贡献度

    /**
     * 贡献度的一项。
     *
     * @param isNew 基期为 0、本期有值 —— 该科目是"新增科目"
     */
    public record Contributor(
            Long subjectId,
            String subjectName,
            String current,
            String base,
            String diff,
            String contributionRate,
            boolean isNew
    ) {
    }

    /**
     * 类别贡献度：回答"本月比上月多花的钱花在哪儿了"。
     *
     * @param totalDiffRate Δ_total / totalBase × 100%，基期为 0 时为 null
     * @param othersDiff    长尾科目（按 |diff| 降序取前 10 之后的）合并差额
     */
    public record Contribution(
            String granularity,
            String currentPeriod,
            String basePeriod,
            String totalCurrent,
            String totalBase,
            String totalDiff,
            String totalDiffRate,
            List<Contributor> contributors,
            String othersDiff
    ) {
    }
}
