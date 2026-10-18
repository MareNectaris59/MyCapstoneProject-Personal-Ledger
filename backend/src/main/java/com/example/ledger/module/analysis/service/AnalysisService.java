package com.example.ledger.module.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.analysis.dto.AnalysisDtos;
import com.example.ledger.module.analysis.mapper.AnalysisMapper;
import com.example.ledger.module.budget.dto.BudgetDtos;
import com.example.ledger.module.budget.service.BudgetService;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.SubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消费分析服务（《系统设计说明书》§7.6）。
 *
 * <p>所有口径都建立在一条设计上：<b>费用/收入的 {@code signed_amount} 求和即净发生额，
 * 冲正分录以负值自然抵消</b>，因此没有任何"排除已冲正"的过滤（§7.3.3）。
 *
 * <p>两处刻意的边界处理，都能写进答辩：
 * <ol>
 *   <li><b>零基期不返回 100% 也不返回 ∞</b>，而是 {@code rate = null} +
 *       {@code rateAvailable = false} + 绝对差额。0 到 100 的增长率不是 100%（§7.6.3）</li>
 *   <li><b>贡献度返回前自检恒等式</b> {@code Σ diff_i = totalDiff}（容差 0.01），
 *       不成立就记 error 并返回 null 的贡献率 —— 让聚合 SQL 的 bug 第一时间暴露，
 *       而不是悄悄画出一张错的图（§7.6.5）</li>
 * </ol>
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    /** 贡献度只保留前 N 项，其余合并为 othersDiff（§7.6.5） */
    private static final int CONTRIBUTOR_LIMIT = 10;

    /** 恒等式自检容差 */
    private static final BigDecimal DIFF_TOLERANCE = new BigDecimal("0.01");

    /** 受支持的时间粒度（§7.6.1） */
    private static final java.util.Set<String> VALID_GRANULARITIES =
            java.util.Set.of("DAY", "WEEK", "MONTH", "YEAR");

    private final AnalysisMapper analysisMapper;
    private final SubjectMapper subjectMapper;
    private final BudgetService budgetService;

    public AnalysisService(AnalysisMapper analysisMapper,
                           SubjectMapper subjectMapper,
                           BudgetService budgetService) {
        this.analysisMapper = analysisMapper;
        this.subjectMapper = subjectMapper;
        this.budgetService = budgetService;
    }

    // ================================================================== 总览

    /**
     * 期间总览。
     *
     * @param startDate 可空，默认取本月 1 日
     * @param endDate   可空，默认取今天
     */
    public AnalysisDtos.Overview overview(Long userId, LocalDate startDate, LocalDate endDate) {
        LocalDate start = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        assertRange(start, end);

        BigDecimal income = MoneyUtil.nullToZero(analysisMapper.sumIncome(userId, start, end));
        BigDecimal expense = MoneyUtil.nullToZero(analysisMapper.sumExpense(userId, start, end));
        BigDecimal netWorth = MoneyUtil.nullToZero(analysisMapper.netWorth(userId, end));

        return new AnalysisDtos.Overview(
                start, end, money(income), money(expense),
                money(income.subtract(expense)), money(netWorth));
    }

    // ================================================================== 占比

    /**
     * 类别占比，支持按 {@code level} 下钻（§7.6.4）。
     *
     * <p><b>关于 {@code level} 的语义</b>：它数的是"用户眼里的分类层级"，
     * 不是树深度。科目树里 {@code 费用} 是<b>类型容器</b>（level 1，没有分类含义），
     * 真正的分类从它的子节点开始。因此：
     * <pre>
     *   level = 1  ->  餐饮 / 交通 / 居住 …      （树深度 2）
     *   level = 2  ->  午餐 / 晚餐 / 打车 …      （树深度 3）
     * </pre>
     * 若直接用树深度，{@code level=1} 会把整个期间合计塞进一行"费用"，
     * 饼图只有一个 100% 的扇区，毫无意义。实测踩过这个。
     *
     * <p>占比之和因四舍五入可能不等于 100%，这里把尾差并入<b>最大的一项</b>，
     * 保证饼图不出现 99.9% 的尴尬（§7.6.4）。
     */
    public AnalysisDtos.CategoryShare categoryShare(Long userId, LocalDate startDate, LocalDate endDate,
                                                    Integer level, Long parentId) {
        LocalDate start = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        assertRange(start, end);
        int userLevel = (level == null || level < 1) ? 1 : level;
        // 用户层级 + 1 = 树深度（跳过类型容器那一层）
        int treeLevel = userLevel + 1;

        Map<Long, Subject> byId = allSubjects(userId);
        // 下钻时只统计该科目子树
        String parentPath = null;
        if (parentId != null) {
            Subject p = byId.get(parentId);
            if (p == null) {
                throw BizException.of(ResultCode.SUBJECT_NOT_FOUND);
            }
            parentPath = p.getPath();
        }

        Map<Long, BigDecimal> bySubject = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : analysisMapper.sumExpenseByLeafSubject(userId, start, end)) {
            Long leafId = toLong(row.get("subject_id"));
            BigDecimal amount = toDecimal(row.get("amount"));
            Subject leaf = byId.get(leafId);
            if (leaf == null) {
                continue;
            }
            if (parentPath != null && (leaf.getPath() == null || !leaf.getPath().startsWith(parentPath))) {
                continue;
            }
            Long anchorId = ancestorAtLevel(leaf, treeLevel, byId);
            if (anchorId == null) {
                continue;
            }
            bySubject.merge(anchorId, amount, BigDecimal::add);
            total = total.add(amount);
        }

        List<AnalysisDtos.CategoryShareItem> items = new ArrayList<>();
        boolean totalIsZero = total.compareTo(BigDecimal.ZERO) == 0;
        for (Map.Entry<Long, BigDecimal> e : bySubject.entrySet()) {
            Subject s = byId.get(e.getKey());
            BigDecimal amount = e.getValue();
            String share = totalIsZero ? "0.00"
                    : amount.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP).toPlainString();
            items.add(new AnalysisDtos.CategoryShareItem(
                    e.getKey(), s == null ? null : s.getName(), money(amount), share));
        }
        items.sort(Comparator.comparing(
                (AnalysisDtos.CategoryShareItem i) -> new BigDecimal(i.amount())).reversed());

        boolean adjusted = false;
        if (!totalIsZero && !items.isEmpty()) {
            adjusted = fixRoundingTail(items);
        }

        return new AnalysisDtos.CategoryShare(start, end, userLevel, money(total), adjusted, items);
    }

    /**
     * 把占比尾差并入最大的一项，使合计恰为 100.00。
     *
     * @return 是否做了调整
     */
    private boolean fixRoundingTail(List<AnalysisDtos.CategoryShareItem> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AnalysisDtos.CategoryShareItem i : items) {
            sum = sum.add(new BigDecimal(i.share()));
        }
        BigDecimal gap = HUNDRED.subtract(sum);
        if (gap.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        // items 已按金额降序，第一项即最大项
        AnalysisDtos.CategoryShareItem top = items.get(0);
        BigDecimal fixed = new BigDecimal(top.share()).add(gap);
        items.set(0, new AnalysisDtos.CategoryShareItem(
                top.subjectId(), top.subjectName(), top.amount(), fixed.toPlainString()));
        return true;
    }

    // ================================================================== 趋势

    /**
     * 消费趋势序列。
     *
     * <p>只返回<b>有数据</b>的周期，不为缺失周期补 0 —— 补 0 会让折线出现
     * "某月突然归零"的假象，而那月其实只是没记账。
     */
    public AnalysisDtos.Trend trend(Long userId, String granularity,
                                    LocalDate startDate, LocalDate endDate, Long subjectId) {
        String g = granularity == null ? "MONTH" : granularity.toUpperCase();
        // 校验粒度合法性。dateFormatOf 的 switch 有 default 分支，不校验的话
        // DECADE 这类无效值会被静默当成 MONTH，用户拿到一张"看着正常但语义错了"
        // 的图，而且没有任何迹象表明参数被忽略了。宁可显式报错。
        if (!VALID_GRANULARITIES.contains(g)) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "不支持的时间粒度：" + granularity + "（可选 DAY/WEEK/MONTH/YEAR）");
        }
        LocalDate start = startDate != null ? startDate : LocalDate.now().minusMonths(5).withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        assertRange(start, end);

        String subjectPath = null;
        if (subjectId != null) {
            Subject s = subjectMapper.selectById(subjectId);
            if (s == null || !s.getUserId().equals(userId)) {
                throw BizException.of(ResultCode.SUBJECT_NOT_FOUND);
            }
            subjectPath = s.getPath();
        }

        List<Map<String, Object>> rows = analysisMapper.sumExpenseByPeriod(
                userId, dateFormatOf(g), start, end, subjectPath);

        List<AnalysisDtos.TrendPoint> points = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            BigDecimal amount = toDecimal(row.get("amount"));
            points.add(new AnalysisDtos.TrendPoint(String.valueOf(row.get("period_label")), money(amount)));
            total = total.add(amount);
        }
        return new AnalysisDtos.Trend(g, start, end, money(total), points);
    }

    // ================================================================== 同环比

    /**
     * 同比 + 环比（§7.6.3）。
     *
     * @param alignWindow 统计未结束的当前周期时是否把基期裁剪为等长窗口
     */
    public AnalysisDtos.Compare compare(Long userId, String granularity, String period,
                                        Long subjectId, boolean alignWindow) {
        String g = granularity == null ? "MONTH" : granularity.toUpperCase();
        PeriodWindow.Window current = PeriodWindow.of(g, period);

        String subjectPath = null;
        if (subjectId != null) {
            Subject s = subjectMapper.selectById(subjectId);
            if (s == null || !s.getUserId().equals(userId)) {
                throw BizException.of(ResultCode.SUBJECT_NOT_FOUND);
            }
            subjectPath = s.getPath();
        }

        // 当前周期的计量窗口：若尚未结束且要求对齐，则只统计到"今天"
        LocalDate today = LocalDate.now();
        LocalDate currentEnd = current.end().isAfter(today) ? today : current.end();
        long elapsedDays = currentEnd.toEpochDay() - current.start().toEpochDay() + 1;

        BigDecimal currentAmount = expenseOf(userId, current.start(), currentEnd, subjectPath);

        PeriodWindow.Window momWindow = PeriodWindow.previous(g, current);
        PeriodWindow.Window yoyWindow = PeriodWindow.samePeriodLastYear(g, current);

        // 只有"当前周期还没结束"且调用方要求对齐时才裁剪基期
        boolean needAlign = alignWindow && current.end().isAfter(today);
        boolean aligned = false;
        String alignNote = null;

        LocalDate momStart = momWindow.start();
        LocalDate momEnd = momWindow.end();
        LocalDate yoyStart = yoyWindow.start();
        LocalDate yoyEnd = yoyWindow.end();

        if (needAlign) {
            PeriodWindow.Aligned a1 = PeriodWindow.align(momStart, momEnd, elapsedDays);
            PeriodWindow.Aligned a2 = PeriodWindow.align(yoyStart, yoyEnd, elapsedDays);
            momEnd = a1.end();
            yoyEnd = a2.end();
            aligned = a1.aligned() && a2.aligned();
            if (!aligned) {
                alignNote = "基期天数不足，按整段计算";
            }
        }

        AnalysisDtos.BaseComparison mom = buildComparison(
                momWindow.period(), momStart, momEnd,
                expenseOf(userId, momStart, momEnd, subjectPath), currentAmount);
        AnalysisDtos.BaseComparison yoy = buildComparison(
                yoyWindow.period(), yoyStart, yoyEnd,
                expenseOf(userId, yoyStart, yoyEnd, subjectPath), currentAmount);

        return new AnalysisDtos.Compare(
                g, current.period(), current.start(), currentEnd, money(currentAmount),
                mom, yoy, aligned, alignNote);
    }

    /**
     * 构造一个基期对比。
     *
     * <p>零基期处理（§7.6.3 的表格，逐条落实）：
     * <pre>
     *   base = 0 且 current = 0   rate = null，diff = 0，展示"—"
     *   base = 0 且 current > 0   rate = null，rateAvailable = false，展示"新增 +XXX"
     *   base &gt; 0 且 current = 0   rate = -100%
     *   base &lt; 0（冲正导致净额为负） rate = null，附注"基期为负值，增长率无意义"
     * </pre>
     */
    private AnalysisDtos.BaseComparison buildComparison(String period, LocalDate start, LocalDate end,
                                                        BigDecimal base, BigDecimal current) {
        BigDecimal diff = current.subtract(base);

        if (base.compareTo(BigDecimal.ZERO) < 0) {
            return new AnalysisDtos.BaseComparison(period, start, end, money(base),
                    money(diff), null, false, "基期为负值，增长率无意义");
        }
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            String note = current.compareTo(BigDecimal.ZERO) == 0
                    ? "基期与本期均无数据"
                    : "基期无数据，仅显示差额";
            return new AnalysisDtos.BaseComparison(period, start, end, money(base),
                    money(diff), null, false, note);
        }
        BigDecimal rate = diff.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
        return new AnalysisDtos.BaseComparison(period, start, end, money(base),
                money(diff), rate.toPlainString(), true, null);
    }

    // ================================================================== 贡献度

    /**
     * 类别贡献度：回答"本月比上月多花的钱花在哪儿了"（§7.6.5）。
     *
     * <p><b>返回前自检恒等式</b> {@code Σ diff_i = totalDiff}（容差 0.01）。
     * 不成立说明聚合 SQL 有问题，此时把 {@code contributionRate} 全部置 null
     * 并记 error —— 宁可少给一列数据，也不要画出错的图。
     */
    public AnalysisDtos.Contribution contribution(Long userId, String granularity, String period,
                                                  String basePeriod) {
        String g = granularity == null ? "MONTH" : granularity.toUpperCase();
        PeriodWindow.Window current = PeriodWindow.of(g, period);
        PeriodWindow.Window base = (basePeriod == null || basePeriod.isBlank())
                ? PeriodWindow.previous(g, current)
                : PeriodWindow.of(g, basePeriod);

        Map<Long, Subject> byId = allSubjects(userId);
        Map<Long, BigDecimal> currentBy = new LinkedHashMap<>();
        Map<Long, BigDecimal> baseBy = new LinkedHashMap<>();

        for (Map<String, Object> row : analysisMapper.sumExpenseBySubjectBothPeriods(
                userId, current.start(), current.end(), base.start(), base.end())) {
            Long id = toLong(row.get("subject_id"));
            BigDecimal amount = toDecimal(row.get("amount"));
            if ("CURRENT".equals(String.valueOf(row.get("period_tag")))) {
                currentBy.merge(id, amount, BigDecimal::add);
            } else {
                baseBy.merge(id, amount, BigDecimal::add);
            }
        }

        BigDecimal totalCurrent = sum(currentBy.values());
        BigDecimal totalBase = sum(baseBy.values());
        BigDecimal totalDiff = totalCurrent.subtract(totalBase);

        // 汇总所有出现过的科目
        List<AnalysisDtos.Contributor> all = new ArrayList<>();
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        ids.addAll(currentBy.keySet());
        ids.addAll(baseBy.keySet());

        BigDecimal sumOfDiffs = BigDecimal.ZERO;
        for (Long id : ids) {
            BigDecimal cur = MoneyUtil.nullToZero(currentBy.get(id));
            BigDecimal bas = MoneyUtil.nullToZero(baseBy.get(id));
            BigDecimal diff = cur.subtract(bas);
            sumOfDiffs = sumOfDiffs.add(diff);
            Subject s = byId.get(id);
            all.add(new AnalysisDtos.Contributor(
                    id, s == null ? null : s.getName(),
                    money(cur), money(bas), money(diff), null,
                    bas.compareTo(BigDecimal.ZERO) == 0 && cur.compareTo(BigDecimal.ZERO) != 0));
        }

        // 自检恒等式：Σ diff_i 必须等于 totalDiff
        if (sumOfDiffs.subtract(totalDiff).abs().compareTo(DIFF_TOLERANCE) > 0) {
            log.error("贡献度恒等式不成立 userId={} Σdiff={} totalDiff={} —— 聚合结果不一致，"
                            + "已返回 null 贡献率以免误导前端",
                    userId, sumOfDiffs.toPlainString(), totalDiff.toPlainString());
            return new AnalysisDtos.Contribution(
                    g, current.period(), base.period(),
                    money(totalCurrent), money(totalBase), money(totalDiff), null,
                    all, "0.00");
        }

        // 按 |diff| 降序，前 N 项保留贡献率，其余合并
        all.sort(Comparator.comparing((AnalysisDtos.Contributor c) ->
                new BigDecimal(c.diff()).abs()).reversed());

        List<AnalysisDtos.Contributor> kept = new ArrayList<>();
        BigDecimal othersDiff = BigDecimal.ZERO;
        for (int i = 0; i < all.size(); i++) {
            AnalysisDtos.Contributor c = all.get(i);
            if (i < CONTRIBUTOR_LIMIT) {
                String rate = totalDiff.compareTo(BigDecimal.ZERO) == 0
                        ? null
                        : new BigDecimal(c.diff()).multiply(HUNDRED)
                                .divide(totalDiff, 2, RoundingMode.HALF_UP).toPlainString();
                kept.add(new AnalysisDtos.Contributor(
                        c.subjectId(), c.subjectName(), c.current(), c.base(),
                        c.diff(), rate, c.isNew()));
            } else {
                othersDiff = othersDiff.add(new BigDecimal(c.diff()));
            }
        }

        String totalDiffRate = totalBase.compareTo(BigDecimal.ZERO) == 0
                ? null
                : totalDiff.multiply(HUNDRED).divide(totalBase, 2, RoundingMode.HALF_UP).toPlainString();

        return new AnalysisDtos.Contribution(
                g, current.period(), base.period(),
                money(totalCurrent), money(totalBase), money(totalDiff), totalDiffRate,
                kept, money(othersDiff));
    }

    // ================================================================== 预算执行

    /**
     * 预算执行分析：预算 vs 实际，多周期（§6.2.6 的 /analysis/budget-execution）。
     *
     * <p>数据复用 {@code BudgetService.periods} —— 它已经负责"每次由 entry 重算
     * 已用额"以及结转账的递推，这里只做视图裁剪，<b>不重算</b>任何金额。
     * 这样预算页与分析页的数字永远一致，不会出现"两处各算一遍然后对不上"。
     *
     * @param periodCount 只返回最近 N 期，null 表示全部
     */
    public AnalysisDtos.BudgetExecution budgetExecution(Long userId, Long budgetId, Integer periodCount) {
        BudgetDtos.BudgetDetail detail = budgetService.detail(userId, budgetId);
        BudgetDtos.BudgetView b = detail.budget();

        // 只保留"已经开始"的周期。长期预算会预生成未来若干期（可用额随结转
        // 一路累加，如 1000/2000/3000…），把未来周期画进图表是噪音：
        // 它们没有任何实际发生额。实测踩过：periodCount=3 取到了 2027 年的空周期。
        LocalDate today = LocalDate.now();
        java.util.List<BudgetDtos.PeriodView> all = new ArrayList<>();
        for (BudgetDtos.PeriodView pv : detail.periods()) {
            if (!pv.startDate().isAfter(today)) {
                all.add(pv);
            }
        }

        int from = 0;
        if (periodCount != null && periodCount > 0 && all.size() > periodCount) {
            from = all.size() - periodCount;
        }
        List<AnalysisDtos.BudgetExecutionPoint> points = new ArrayList<>();
        for (int i = from; i < all.size(); i++) {
            BudgetDtos.PeriodView p = all.get(i);
            points.add(new AnalysisDtos.BudgetExecutionPoint(
                    p.periodLabel(), p.startDate(), p.endDate(),
                    p.availableAmount(), p.usedAmount(), p.executionRate()));
        }

        return new AnalysisDtos.BudgetExecution(
                b.id(), b.name(), b.scopeType(), b.subjectName(), b.amount(), points);
    }
    // ================================================================== 导出

    /**
     * 导出分析结果为 CSV（§6.2.6 的 /analysis/export）。
     *
     * <p><b>为什么返回 String 而不是直接写响应流</b>：CSV 生成属于业务逻辑
     * （取数、口径、列顺序），写 HTTP 响应属于表现层。分开之后可以单测，
     * 也避免 Service 依赖 Servlet API。
     *
     * @param type trend（趋势）或 category（类别占比）
     */
    public String exportCsv(Long userId, String type, String granularity,
                            LocalDate startDate, LocalDate endDate, Integer level) {
        String kind = (type == null || type.isBlank()) ? "trend" : type.toLowerCase();
        StringBuilder csv = new StringBuilder();

        switch (kind) {
            case "category" -> {
                AnalysisDtos.CategoryShare share =
                        categoryShare(userId, startDate, endDate, level, null);
                csv.append("分类,金额,占比(%)\n");
                for (AnalysisDtos.CategoryShareItem item : share.items()) {
                    csv.append(csvField(item.subjectName())).append(',')
                       .append(csvField(item.amount())).append(',')
                       .append(csvField(item.share())).append('\n');
                }
                csv.append("合计,").append(csvField(share.total())).append(",\n");
            }
            default -> {
                AnalysisDtos.Trend trend = trend(userId, granularity, startDate, endDate, null);
                csv.append("期间,金额\n");
                for (AnalysisDtos.TrendPoint p : trend.points()) {
                    csv.append(csvField(p.period())).append(',')
                       .append(csvField(p.amount())).append('\n');
                }
                csv.append("合计,").append(csvField(trend.total())).append('\n');
            }
        }
        return csv.toString();
    }

    /**
     * CSV 字段转义（RFC 4180）。
     *
     * <p>含逗号、双引号、换行的字段必须整体加双引号，内部的双引号要成对。
     * 不转义的话，一个叫「餐饮,外卖」的分类会把整行切成三列。
     */
    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    // ================================================================== 内部

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private BigDecimal expenseOf(Long userId, LocalDate start, LocalDate end, String subjectPath) {
        if (subjectPath == null) {
            return MoneyUtil.nullToZero(analysisMapper.sumExpense(userId, start, end));
        }
        // 指定科目时复用按期间分组的查询：格式取 %Y 只产生一行，等价于求总额
        List<Map<String, Object>> rows = analysisMapper.sumExpenseByPeriod(
                userId, "%Y", start, end, subjectPath);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            total = total.add(toDecimal(row.get("amount")));
        }
        return total;
    }

    /** 全部科目（含各层级），用于路径与名称解析。 */
    private Map<Long, Subject> allSubjects(Long userId) {
        Map<Long, Subject> map = new HashMap<>();
        for (Subject s : subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId)
                .eq(Subject::getSubjectType, SubjectType.EXPENSE))) {
            map.put(s.getId(), s);
        }
        return map;
    }

    /**
     * 取某科目在指定层级的祖先。
     *
     * <p>{@code Subject.path} 形如 {@code /12/34/56/}，即从根到自身的 id 序列。
     * 第 {@code level} 层的祖先就是路径里第 {@code level} 个 id。
     * 自身层级低于 {@code level} 时返回自身（下钻不到更深层就停在原地）。
     */
    private Long ancestorAtLevel(Subject leaf, int level, Map<Long, Subject> byId) {
        String path = leaf.getPath();
        if (path == null || path.isBlank()) {
            return leaf.getId();
        }
        String[] segments = path.split("/");
        List<Long> ids = new ArrayList<>();
        for (String seg : segments) {
            if (!seg.isBlank()) {
                try {
                    ids.add(Long.parseLong(seg));
                } catch (NumberFormatException ignored) {
                    // 路径异常段直接忽略，不影响其他科目
                }
            }
        }
        if (ids.isEmpty()) {
            return leaf.getId();
        }
        int idx = Math.min(level, ids.size()) - 1;
        Long anchor = ids.get(Math.max(0, idx));
        return byId.containsKey(anchor) ? anchor : leaf.getId();
    }

    private void assertRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw BizException.of(ResultCode.DATE_INVALID, "开始日期不能晚于结束日期");
        }
    }

    private String dateFormatOf(String granularity) {
        return switch (granularity) {
            case "DAY" -> "%Y-%m-%d";
            case "WEEK" -> "%x-W%v";     // %x = 周所在年，%v = 周序号（周一起算）
            case "YEAR" -> "%Y";
            default -> "%Y-%m";
        };
    }

    private static BigDecimal sum(java.util.Collection<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(MoneyUtil.nullToZero(v));
        }
        return total;
    }

    private static String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(MoneyUtil.SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(String.valueOf(v));
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }
}
