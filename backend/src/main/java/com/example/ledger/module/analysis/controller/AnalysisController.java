package com.example.ledger.module.analysis.controller;

import com.example.ledger.common.result.Result;
import com.example.ledger.module.analysis.dto.AnalysisDtos;
import com.example.ledger.module.analysis.service.AnalysisService;
import com.example.ledger.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 消费分析接口（《系统设计说明书》§6.2.6）。
 *
 * <p>本模块<b>全部是只读查询</b>，没有写接口，因此不需要幂等键，
 * 也不存在权属变更风险 —— 但每个查询仍然按 {@code userId} 过滤，
 * 由 Mapper 的 SQL 保证（见 {@code AnalysisMapper}），
 * 避免"按 id 查别人数据"的越权。
 *
 * <p>日期参数统一用 {@code @DateTimeFormat(iso = DATE)} 接收 {@code yyyy-MM-dd}，
 * 而不是让 Spring 按当前 Locale 猜格式。项目约定所有日期归属基于 Asia/Shanghai，
 * {@code ledger.occurred_date} 存 DATE 类型，避免跨日错位（§7.6.1）。
 */
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * 预算执行分析：预算 vs 实际，多周期。
     *
     * @param budgetId    必填，要分析的预算
     * @param periodCount 只返回最近 N 期，为空表示全部
     */
    @GetMapping("/budget-execution")
    public Result<AnalysisDtos.BudgetExecution> budgetExecution(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam Long budgetId,
            @RequestParam(required = false) Integer periodCount) {
        return Result.success(analysisService.budgetExecution(user.userId(), budgetId, periodCount));
    }

    /**
     * 导出分析结果（CSV）。
     *
     * <p>这是本模块唯一返回非 JSON 的接口 —— 文件下载无法塞进统一响应体。
     * 因此直接用 {@code ResponseEntity<byte[]>} 输出 {@code text/csv}。
     *
     * <p><b>必须带 UTF-8 BOM。</b>Excel 打开不带 BOM 的 UTF-8 CSV 时，
     * 会按本地编码（简体中文 Windows 上是 GBK）解释，中文列全部变乱码。
     * 这是最常见的"导出的表格打不开/是乱码"投诉来源，一个字节就能避免。
     *
     * @param type trend（趋势）或 category（类别占比），默认 trend
     */
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false, defaultValue = "trend") String type,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer level) {

        String csv = analysisService.exportCsv(user.userId(), type, granularity, startDate, endDate, level);

        // BOM: EF BB BF。缺了它 Excel 会把 UTF-8 当 GBK 解，中文全乱码。
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, payload, 0, bom.length);
        System.arraycopy(body, 0, payload, bom.length, body.length);

        // 文件名用纯 ASCII：HTTP 头里放中文要 RFC 5987 编码，
        // 而 Excel/WPS 对 filename* 的支持参差，不值得为此冒险。
        String filename = "analysis-" + ("category".equalsIgnoreCase(type) ? "category" : "trend") + ".csv";

        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(payload);
    }

    /** 期间总览：收入、支出、结余、净资产。 */
    @GetMapping("/overview")
    public Result<AnalysisDtos.Overview> overview(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(analysisService.overview(user.userId(), startDate, endDate));
    }

    /**
     * 类别占比（饼图/树图），支持下钻。
     *
     * @param level    聚合到第几层，默认 1
     * @param parentId 下钻到该科目子树，为空表示全部
     */
    @GetMapping("/category-share")
    public Result<AnalysisDtos.CategoryShare> categoryShare(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Long parentId) {
        return Result.success(analysisService.categoryShare(user.userId(), startDate, endDate, level, parentId));
    }

    /** 消费趋势序列。 */
    @GetMapping("/trend")
    public Result<AnalysisDtos.Trend> trend(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false, defaultValue = "MONTH") String granularity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long subjectId) {
        return Result.success(analysisService.trend(user.userId(), granularity, startDate, endDate, subjectId));
    }

    /**
     * 同比 + 环比（同时返回，便于同图对比）。
     *
     * @param alignWindow 未结束的当前周期是否把基期裁剪为等长窗口，默认 true
     */
    @GetMapping("/compare")
    public Result<AnalysisDtos.Compare> compare(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false, defaultValue = "MONTH") String granularity,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false, defaultValue = "true") boolean alignWindow) {
        return Result.success(analysisService.compare(user.userId(), granularity, period, subjectId, alignWindow));
    }

    /**
     * 类别贡献度：谁造成了增减。
     *
     * @param basePeriod 为空时自动取当前周期的前一个相邻周期
     */
    @GetMapping("/contribution")
    public Result<AnalysisDtos.Contribution> contribution(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false, defaultValue = "MONTH") String granularity,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String basePeriod) {
        return Result.success(analysisService.contribution(user.userId(), granularity, period, basePeriod));
    }

    /**
     * 周/月报表。
     *
     * <p>当前实现按 {@code granularity} 取该周期窗口的总览 ——
     * 即"某个月/某一周的收入支出结余"。这与 {@code /overview} 的区别是
     * 用<b>周期标识</b>（{@code 2026-10}、{@code 2026-W42}）指定窗口，
     * 而不是给两个日期，便于前端从下拉框直接调用。
     */
    @GetMapping("/period-report")
    public Result<AnalysisDtos.Overview> periodReport(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false, defaultValue = "MONTH") String granularity,
            @RequestParam(required = false) String period) {
        var w = com.example.ledger.module.analysis.service.PeriodWindow.of(granularity, period);
        return Result.success(analysisService.overview(user.userId(), w.start(), w.end()));
    }
}
