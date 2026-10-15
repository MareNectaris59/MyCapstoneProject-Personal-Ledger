package com.example.ledger.module.budget.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.budget.dto.BudgetDtos;
import com.example.ledger.module.budget.entity.Budget;
import com.example.ledger.module.budget.entity.BudgetAlert;
import com.example.ledger.module.budget.entity.BudgetPeriod;
import com.example.ledger.module.budget.mapper.BudgetAlertMapper;
import com.example.ledger.module.budget.mapper.BudgetMapper;
import com.example.ledger.module.budget.mapper.BudgetPeriodMapper;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.SubjectMapper;
import com.example.ledger.module.user.entity.UserProfile;
import com.example.ledger.module.user.mapper.UserProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 预算服务（《系统设计说明书》§7.5）。
 *
 * <p>核心算法两点：
 * <ol>
 *   <li><b>已用额每次由 entry 重算</b>，不做增量累加。冲正与补记历史账目都会让
 *       累加值漂移且无法自愈，重算则永远正确（§7.5.4）</li>
 *   <li><b>结转按公式递推</b>，并带下限保护：{@code available} 为负时展示层截断为 0，
 *       被截断的部分记入 {@code carryFloor}，同时以 {@code overspent} 如实告知用户。
 *       宁可少扣，也不让数字失去可解释性（§7.5.3）</li>
 * </ol>
 *
 * <p>周期行的生成范围：从预算生效日连续铺到 {@code endDate}（或今天，
 * 长期预算只铺到当前周期为止，避免为未来若干年预生成大量空行）。
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    /** 长期预算向前铺多少期。取值权衡：够用即可，避免一次插入几百行 */
    private static final int LONG_TERM_LOOKAHEAD = 12;

    /** 一次生成周期行的硬上限，防御性保护（CUSTOM 天数为 1 时周期数会很大） */
    private static final int MAX_PERIOD_ROWS = 400;

    private static final String SCOPE_TOTAL = "TOTAL";
    private static final String SCOPE_SUBJECT = "SUBJECT";

    private final BudgetMapper budgetMapper;
    private final BudgetPeriodMapper periodMapper;
    private final BudgetAlertMapper alertMapper;
    private final SubjectMapper subjectMapper;
    private final UserProfileMapper profileMapper;
    private final BudgetDirtyMarker dirtyMarker;

    public BudgetService(BudgetMapper budgetMapper,
                         BudgetPeriodMapper periodMapper,
                         BudgetAlertMapper alertMapper,
                         SubjectMapper subjectMapper,
                         UserProfileMapper profileMapper,
                         BudgetDirtyMarker dirtyMarker) {
        this.budgetMapper = budgetMapper;
        this.periodMapper = periodMapper;
        this.alertMapper = alertMapper;
        this.subjectMapper = subjectMapper;
        this.profileMapper = profileMapper;
        this.dirtyMarker = dirtyMarker;
    }

    // ================================================================== 查询

    /** 预算列表：每个预算附带"当前周期"的执行情况。 */
    public List<BudgetDtos.BudgetView> list(Long userId) {
        List<Budget> budgets = budgetMapper.selectList(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .orderByDesc(Budget::getId));

        LocalDate today = LocalDate.now();
        Map<Long, String> subjectNames = subjectNames(userId, budgets);

        List<BudgetDtos.BudgetView> out = new ArrayList<>(budgets.size());
        for (Budget b : budgets) {
            out.add(toView(userId, b, today, subjectNames));
        }
        return out;
    }

    /** 预算详情：主视图 + 全部周期（趋势序列）。 */
    public BudgetDtos.BudgetDetail detail(Long userId, Long budgetId) {
        Budget b = requireOwned(userId, budgetId);
        LocalDate today = LocalDate.now();
        Map<Long, String> names = subjectNames(userId, List.of(b));

        // 详情页要看到完整趋势，因此先结算再返回，保证数字是最新的
        settle(userId, b);
        List<BudgetPeriod> periods = loadPeriods(b.getId());

        return new BudgetDtos.BudgetDetail(
                toView(userId, b, today, names),
                periods.stream().map(this::toPeriodView).toList());
    }

    /** 某预算的周期列表（不含主视图）。 */
    public List<BudgetDtos.PeriodView> periods(Long userId, Long budgetId) {
        Budget b = requireOwned(userId, budgetId);
        settle(userId, b);
        return loadPeriods(b.getId()).stream().map(this::toPeriodView).toList();
    }

    /** 当前周期执行情况 + 趋势 + 未读预警。 */
    public BudgetDtos.ExecutionView execution(Long userId, Long budgetId) {
        Budget b = requireOwned(userId, budgetId);
        settle(userId, b);

        LocalDate today = LocalDate.now();
        List<BudgetPeriod> all = loadPeriods(b.getId());
        BudgetPeriod current = all.stream()
                .filter(p -> !today.isBefore(p.getStartDate()) && !today.isAfter(p.getEndDate()))
                .findFirst()
                .orElse(null);

        // 趋势取到当前周期为止的最后 12 期
        List<BudgetPeriod> upto = all.stream()
                .filter(p -> !p.getStartDate().isAfter(today))
                .toList();
        List<BudgetPeriod> trend = upto.size() > 12
                ? upto.subList(upto.size() - 12, upto.size())
                : upto;

        return new BudgetDtos.ExecutionView(
                toView(userId, b, today, subjectNames(userId, List.of(b))),
                current == null ? null : toPeriodView(current),
                trend.stream().map(this::toPeriodView).toList(),
                alerts(userId, budgetId));
    }

    /**
     * 预警列表。
     *
     * <p><b>必须先结算再查询。</b>记账只置脏标记（{@code dirty_since}），
     * 真正的重算发生在读取时；而预警是重算的副产品。若这里直接查
     * {@code budget_alert}，用户记完一笔账立刻打开预警列表会看到<b>空列表</b>，
     * 而同一时刻 {@code /execution} 却能返回预警 —— 两个接口互相矛盾。
     * 实测踩过这个不一致。
     *
     * <p>结算范围是该用户全部启用的预算：预警本就是跨预算的列表视图，
     * 不针对某一个预算，因此无法只结算一个。
     */
    public List<BudgetDtos.BudgetAlertView> alerts(Long userId, Long budgetId) {
        List<Budget> enabled = budgetMapper.selectList(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .eq(Budget::getEnabled, true));
        for (Budget b : enabled) {
            settle(userId, b);
        }

        LambdaQueryWrapper<BudgetAlert> w = new LambdaQueryWrapper<BudgetAlert>()
                .eq(BudgetAlert::getUserId, userId)
                .orderByDesc(BudgetAlert::getId);
        if (budgetId != null) {
            w.eq(BudgetAlert::getBudgetId, budgetId);
        }
        List<BudgetAlert> list = alertMapper.selectList(w);

        Map<Long, String> names = new HashMap<>();
        for (Budget b : budgetMapper.selectList(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId))) {
            names.put(b.getId(), b.getName());
        }

        List<BudgetDtos.BudgetAlertView> out = new ArrayList<>(list.size());
        for (BudgetAlert a : list) {
            out.add(new BudgetDtos.BudgetAlertView(
                    a.getId(), a.getBudgetId(), names.get(a.getBudgetId()),
                    a.getBudgetPeriodId(), a.getAlertLevel(), a.getAlertDate(),
                    money(a.getExecutionRate()), money(a.getUsedAmount()),
                    money(a.getAvailableAmount()), a.getMessage(),
                    Boolean.TRUE.equals(a.getIsRead())));
        }
        return out;
    }

    // ================================================================== 写操作

    /** 新建预算，并生成周期行。 */
    @Transactional(rollbackFor = Exception.class)
    public BudgetDtos.BudgetView create(Long userId, BudgetDtos.CreateRequest req) {
        String scopeType = req.scopeType() == null ? SCOPE_TOTAL : req.scopeType().toUpperCase();
        if (!SCOPE_TOTAL.equals(scopeType) && !SCOPE_SUBJECT.equals(scopeType)) {
            throw BizException.of(ResultCode.PARAM_INVALID, "预算范围只能是 TOTAL 或 SUBJECT");
        }
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ResultCode.BUDGET_AMOUNT_INVALID, "预算额度不能为负数");
        }
        if (req.startDate() == null) {
            throw BizException.of(ResultCode.PARAM_INVALID, "预算生效日不能为空");
        }
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw BizException.of(ResultCode.PARAM_INVALID, "结束日不能早于生效日");
        }

        // 分类预算：科目必须存在、属于本人、且是费用类
        if (SCOPE_SUBJECT.equals(scopeType)) {
            if (req.subjectId() == null) {
                throw BizException.of(ResultCode.PARAM_INVALID, "分类预算必须指定科目");
            }
            Subject s = requireOwnedSubject(userId, req.subjectId());
            if (s.getSubjectType() != SubjectType.EXPENSE) {
                throw BizException.of(ResultCode.BUDGET_SUBJECT_TYPE_INVALID);
            }
            // 同一科目在时间范围上不允许重叠（同科目同周期只允许一个有效预算）
            assertNoOverlap(userId, req.subjectId(), req.startDate(), req.endDate(), null);
        } else {
            // 总预算同样不允许重叠：口径重叠会让"已用额"被重复统计
            assertNoOverlap(userId, null, req.startDate(), req.endDate(), null);
        }

        Budget b = new Budget();
        b.setUserId(userId);
        b.setName(req.name() == null || req.name().isBlank()
                ? defaultName(scopeType, req.subjectId(), userId) : req.name().trim());
        b.setScopeType(scopeType);
        b.setSubjectId(SCOPE_SUBJECT.equals(scopeType) ? req.subjectId() : null);
        b.setPeriodType(req.periodType() == null ? "MONTH" : req.periodType().toUpperCase());
        b.setPeriodValue(req.periodValue());
        b.setStartDate(req.startDate());
        b.setEndDate(req.endDate());
        b.setAmount(MoneyUtil.scale(req.amount()));
        b.setRolloverMode(normalizeRollover(req.rolloverMode()));
        b.setAlertThreshold(req.alertThreshold() == null
                ? new BigDecimal("80.00") : req.alertThreshold());
        b.setEnabled(true);
        budgetMapper.insert(b);

        generatePeriods(userId, b);
        settle(userId, b);
        log.info("预算已创建 userId={} budgetId={} scope={} period={} 周期数={}",
                userId, b.getId(), scopeType, b.getPeriodType(), loadPeriods(b.getId()).size());
        return toView(userId, b, LocalDate.now(), subjectNames(userId, List.of(b)));
    }

    /**
     * 修改预算。
     *
     * <p>只允许改额度、阈值、结转模式、启用状态与名称。
     * <b>周期结构（类型/起止日期/范围/科目）不可改</b> —— 改了会让已生成的周期行
     * 与新的窗口定义不一致，历史执行率随之失真。需要改结构时应当新建预算并把旧的停用。
     */
    @Transactional(rollbackFor = Exception.class)
    public BudgetDtos.BudgetView update(Long userId, Long budgetId, BudgetDtos.UpdateRequest req) {
        Budget b = requireOwned(userId, budgetId);

        if (req.name() != null && !req.name().isBlank()) {
            b.setName(req.name().trim());
        }
        if (req.amount() != null) {
            if (req.amount().compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.of(ResultCode.BUDGET_AMOUNT_INVALID, "预算额度不能为负数");
            }
            b.setAmount(MoneyUtil.scale(req.amount()));
        }
        if (req.alertThreshold() != null) {
            if (req.alertThreshold().compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.of(ResultCode.PARAM_INVALID, "预警阈值不能为负数");
            }
            b.setAlertThreshold(req.alertThreshold());
        }
        if (req.rolloverMode() != null) {
            b.setRolloverMode(normalizeRollover(req.rolloverMode()));
        }
        if (req.enabled() != null) {
            b.setEnabled(req.enabled());
        }
        budgetMapper.updateById(b);

        // 额度或结转模式变了，历史周期的可用额与执行率都要跟着变
        if (req.amount() != null || req.rolloverMode() != null) {
            List<BudgetPeriod> periods = loadPeriods(b.getId());
            LocalDate from = periods.isEmpty() ? b.getStartDate() : periods.get(0).getStartDate();
            periodMapper.markDirty(b.getId(), from);
        }
        settle(userId, b);
        return toView(userId, b, LocalDate.now(), subjectNames(userId, List.of(b)));
    }

    /**
     * 删除预算。
     *
     * <p>逻辑删除预算定义，但<b>保留 {@code budget_period} 历史周期行</b> ——
     * 它们记录了过去的额度与执行率，删掉就无法回溯"当时预算执行得如何"。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long budgetId) {
        Budget b = requireOwned(userId, budgetId);
        budgetMapper.deleteById(b.getId());
        log.info("预算已删除（周期历史保留）userId={} budgetId={}", userId, budgetId);
    }

    /** 标记预警已读。 */
    @Transactional(rollbackFor = Exception.class)
    public void markAlertRead(Long userId, Long alertId) {
        BudgetAlert a = alertMapper.selectById(alertId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.BUDGET_NOT_FOUND, "预警不存在");
        }
        a.setIsRead(true);
        alertMapper.updateById(a);
    }

    /** 供记账内核调用：某笔账落在这个预算的某个周期上，需要重算。 */
    public void markDirtyForOccurredDate(Long userId, LocalDate occurredDate) {
        dirtyMarker.markDirty(userId, occurredDate);
    }

    // ================================================================== 内部

    /**
     * 生成周期行。从 <b>生效日所在窗口</b> 开始连续铺开。
     *
     * <p>为何从"生效日所在窗口"而不是"生效日本身"：用户 3 月 15 日建的月预算，
     * 语义是"3 月这一个月"，窗口应是 3-01 ~ 3-31。若从 3-15 起算会切出
     * 半个月的怪窗口，且与下个月的 4-01 不连续。
     *
     * <p>长期预算（{@code endDate = null}）只向前铺 {@link #LONG_TERM_LOOKAHEAD} 期，
     * 避免为未来若干年预生成大量空行；后续周期在结算时按需追加。
     */
    private void generatePeriods(Long userId, Budget b) {
        int weekStart = weekStartOf(userId);
        List<BudgetPeriod> existing = loadPeriods(b.getId());
        Map<LocalDate, BudgetPeriod> byStart = new HashMap<>();
        for (BudgetPeriod p : existing) {
            byStart.put(p.getStartDate(), p);
        }

        LocalDate limit;
        if (b.getEndDate() != null) {
            limit = b.getEndDate();
        } else {
            // 长期预算：铺到"今天所在窗口之后再 LOOKAHEAD 期"
            PeriodWindows.Window todayWindow = PeriodWindows.of(
                    b.getPeriodType(), LocalDate.now(), weekStart, b.getPeriodValue());
            PeriodWindows.Window cursor = todayWindow;
            for (int i = 0; i < LONG_TERM_LOOKAHEAD; i++) {
                cursor = PeriodWindows.next(b.getPeriodType(), cursor, weekStart, b.getPeriodValue());
            }
            limit = cursor.end();
        }

        PeriodWindows.Window w = PeriodWindows.of(
                b.getPeriodType(), b.getStartDate(), weekStart, b.getPeriodValue());

        int guard = 0;
        while (!w.start().isAfter(limit) && guard++ < MAX_PERIOD_ROWS) {
            if (!byStart.containsKey(w.start())) {
                BudgetPeriod p = new BudgetPeriod();
                p.setBudgetId(b.getId());
                p.setUserId(userId);
                p.setPeriodLabel(w.label());
                p.setStartDate(w.start());
                p.setEndDate(w.end());
                p.setBaseAmount(MoneyUtil.scale(b.getAmount()));
                p.setCarryIn(MoneyUtil.ZERO);
                p.setCarryFloor(MoneyUtil.ZERO);
                p.setAvailableAmount(MoneyUtil.scale(b.getAmount()));
                p.setUsedAmount(MoneyUtil.ZERO);
                p.setSettleState("OPEN");
                periodMapper.insert(p);
            }
            w = PeriodWindows.next(b.getPeriodType(), w, weekStart, b.getPeriodValue());
        }
    }

    /**
     * 结算：把该预算所有周期重算一遍（§7.5.4 的五步级联）。
     *
     * <p>不区分"是否置脏"，直接整体重算。理由：个人记账的周期数有限
     * （一个预算 3 年不超过 160 期），整体重算的代价可忽略，
     * 而"只重算脏周期"的逻辑一旦漏判就会留下静默错误。
     * 简洁换正确性，在这个量级上是正确的取舍。
     */
    @Transactional(rollbackFor = Exception.class)
    public void settle(Long userId, Budget budget) {
        generatePeriods(userId, budget);

        List<BudgetPeriod> periods = loadPeriods(budget.getId());
        if (periods.isEmpty()) {
            return;
        }

        String subjectPath = null;
        if (SCOPE_SUBJECT.equals(budget.getScopeType()) && budget.getSubjectId() != null) {
            Subject s = subjectMapper.selectById(budget.getSubjectId());
            if (s == null) {
                log.warn("预算 {} 引用的科目 {} 已不存在，已用额按 0 计",
                        budget.getId(), budget.getSubjectId());
            } else {
                subjectPath = s.getPath();
            }
        }

        // 按时间升序递推：carry_in 依赖上一期的 remaining
        periods.sort(Comparator.comparing(BudgetPeriod::getStartDate));

        BigDecimal prevRemaining = BigDecimal.ZERO;
        boolean first = true;

        for (BudgetPeriod p : periods) {
            // base_amount 追随预算当前额度。
            //
            // 必须每次刷新，不能只在生成周期行时写一次：用户改了预算额度后，
            // 已存在的周期行若仍持有旧额度，执行率会按旧额度计算，
            // 界面显示 "额度 2000 / 可用 1000 / 执行率 90%" 这种自相矛盾的数字。
            // 实测踩过：PUT /budgets/{id} amount=2000 之后 baseAmount 仍是 1000.00。
            p.setBaseAmount(MoneyUtil.scale(budget.getAmount()));

            BigDecimal used = sumUsed(userId, budget, subjectPath, p.getStartDate(), p.getEndDate());
            p.setUsedAmount(MoneyUtil.scale(used));

            // 本期期初结转：第一期不结转（没有"上一期"）
            BigDecimal carry = first ? BigDecimal.ZERO : carryFrom(prevRemaining, budget.getRolloverMode());
            p.setCarryIn(MoneyUtil.scale(carry));

            BigDecimal rawAvailable = MoneyUtil.scale(MoneyUtil.nullToZero(p.getBaseAmount()).add(carry));
            p.setAvailableAmount(rawAvailable);

            // 下限保护：可用额为负时，展示层截断为 0，被截断的部分记入 carryFloor
            if (rawAvailable.compareTo(BigDecimal.ZERO) < 0) {
                p.setCarryFloor(rawAvailable.negate());
            } else {
                p.setCarryFloor(MoneyUtil.ZERO);
            }

            p.setExecutionRate(executionRate(p.getUsedAmount(), rawAvailable));
            p.setDirtySince(null);
            periodMapper.updateById(p);

            prevRemaining = MoneyUtil.nullToZero(p.getAvailableAmount())
                    .subtract(MoneyUtil.nullToZero(p.getUsedAmount()));
            first = false;

            evaluateAlert(userId, budget, p);
        }
    }

    /** 结转公式（§7.5.3）。 */
    private BigDecimal carryFrom(BigDecimal prevRemaining, String mode) {
        BigDecimal r = MoneyUtil.nullToZero(prevRemaining);
        return switch (mode == null ? "SURPLUS_ONLY" : mode) {
            case "NONE" -> BigDecimal.ZERO;
            case "FULL" -> r;                                   // 超支以负数转下期
            default -> r.compareTo(BigDecimal.ZERO) > 0 ? r : BigDecimal.ZERO;  // SURPLUS_ONLY
        };
    }

    /**
     * 执行率 = used / available × 100%，保留 2 位。
     *
     * <p><b>available = 0 时返回 null</b>，不返回 0 也不返回 ∞：
     * "没设额度"与"额度用完"是两件事，用 0% 表达会误导（§7.5.3）。
     */
    private BigDecimal executionRate(BigDecimal used, BigDecimal available) {
        if (available == null || available.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return MoneyUtil.nullToZero(used)
                .multiply(new BigDecimal("100"))
                .divide(available, 2, RoundingMode.HALF_UP);
    }

    /** 按范围口径取该周期的费用发生额。 */
    private BigDecimal sumUsed(Long userId, Budget budget, String subjectPath,
                               LocalDate start, LocalDate end) {
        if (SCOPE_SUBJECT.equals(budget.getScopeType())) {
            if (subjectPath == null) {
                return BigDecimal.ZERO;
            }
            return periodMapper.sumExpenseByPath(userId, subjectPath, start, end);
        }
        return periodMapper.sumExpenseTotal(userId, start, end);
    }

    /**
     * 阈值预警。同一周期同级别每天最多一条（由 {@code uk_period_level_date} 兜底）。
     *
     * <p>级别：执行率 ≥100% 为 DANGER，≥用户阈值 为 WARNING。
     */
    private void evaluateAlert(Long userId, Budget budget, BudgetPeriod p) {
        BigDecimal rate = p.getExecutionRate();
        if (rate == null || budget.getAlertThreshold() == null) {
            return;
        }
        String level;
        if (rate.compareTo(new BigDecimal("100")) >= 0) {
            level = "DANGER";
        } else if (rate.compareTo(budget.getAlertThreshold()) >= 0) {
            level = "WARNING";
        } else {
            return;
        }

        LocalDate today = LocalDate.now();
        Long exists = alertMapper.selectCount(new LambdaQueryWrapper<BudgetAlert>()
                .eq(BudgetAlert::getBudgetPeriodId, p.getId())
                .eq(BudgetAlert::getAlertLevel, level)
                .eq(BudgetAlert::getAlertDate, today));
        if (exists != null && exists > 0) {
            return;
        }

        BudgetAlert a = new BudgetAlert();
        a.setUserId(userId);
        a.setBudgetId(budget.getId());
        a.setBudgetPeriodId(p.getId());
        a.setAlertLevel(level);
        a.setAlertDate(today);
        a.setExecutionRate(rate);
        a.setUsedAmount(p.getUsedAmount());
        a.setAvailableAmount(p.getAvailableAmount());
        a.setMessage(String.format("预算「%s」%s 执行率已达 %s%%（已用 %s / 可用 %s）",
                budget.getName(), p.getPeriodLabel(), rate.toPlainString(),
                money(p.getUsedAmount()), money(p.getAvailableAmount())));
        a.setIsRead(false);
        alertMapper.insert(a);
    }

    private List<BudgetPeriod> loadPeriods(Long budgetId) {
        return periodMapper.selectList(new LambdaQueryWrapper<BudgetPeriod>()
                .eq(BudgetPeriod::getBudgetId, budgetId)
                .orderByAsc(BudgetPeriod::getStartDate));
    }

    private Budget requireOwned(Long userId, Long budgetId) {
        Budget b = budgetMapper.selectById(budgetId);
        if (b == null || !b.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.BUDGET_NOT_FOUND);
        }
        return b;
    }

    private Subject requireOwnedSubject(Long userId, Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.SUBJECT_NOT_FOUND);
        }
        return s;
    }

    /**
     * 时间范围重叠校验。
     *
     * <p>判据：两段区间相交。{@code endDate = null} 视为长期有效（用 {@code 9999-12-31} 代替）。
     * {@code excludeId} 用于更新场景排除自身。
     */
    private void assertNoOverlap(Long userId, Long subjectId,
                                 LocalDate start, LocalDate end, Long excludeId) {
        LocalDate endSafe = end == null ? LocalDate.of(9999, 12, 31) : end;

        LambdaQueryWrapper<Budget> w = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .eq(Budget::getEnabled, true);
        if (subjectId == null) {
            w.isNull(Budget::getSubjectId);
        } else {
            w.eq(Budget::getSubjectId, subjectId);
        }
        if (excludeId != null) {
            w.ne(Budget::getId, excludeId);
        }

        for (Budget other : budgetMapper.selectList(w)) {
            LocalDate otherEnd = other.getEndDate() == null
                    ? LocalDate.of(9999, 12, 31) : other.getEndDate();
            boolean overlaps = !start.isAfter(otherEnd) && !other.getStartDate().isAfter(endSafe);
            if (overlaps) {
                throw BizException.of(ResultCode.BUDGET_PERIOD_OVERLAP,
                        "该范围在 " + other.getStartDate() + " 起已有预算，时间不可重叠");
            }
        }
    }

    private String normalizeRollover(String mode) {
        if (mode == null || mode.isBlank()) {
            return "SURPLUS_ONLY";   // 设计文档推荐默认值
        }
        String m = mode.toUpperCase();
        if (!m.equals("NONE") && !m.equals("SURPLUS_ONLY") && !m.equals("FULL")) {
            throw BizException.of(ResultCode.PARAM_INVALID, "结转模式不合法：" + mode);
        }
        return m;
    }

    /** 用户配置的周起始日（1=周一 … 7=周日），取自 user_profile.week_start。 */
    private int weekStartOf(Long userId) {
        UserProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId));
        Integer ws = profile == null ? null : profile.getWeekStart();
        return (ws != null && ws >= 1 && ws <= 7) ? ws : 1;
    }

    private String defaultName(String scopeType, Long subjectId, Long userId) {
        if (SCOPE_SUBJECT.equals(scopeType) && subjectId != null) {
            Subject s = subjectMapper.selectById(subjectId);
            return s == null ? "分类预算" : s.getName() + " 预算";
        }
        return "总预算";
    }

    private Map<Long, String> subjectNames(Long userId, List<Budget> budgets) {
        List<Long> ids = budgets.stream()
                .map(Budget::getSubjectId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new LinkedHashMap<>();
        for (Subject s : subjectMapper.selectBatchIds(ids)) {
            map.put(s.getId(), s.getName());
        }
        return map;
    }

    // ------------------------------------------------------------- 视图组装

    private BudgetDtos.BudgetView toView(Long userId, Budget b, LocalDate today,
                                         Map<Long, String> subjectNames) {
        List<BudgetPeriod> periods = loadPeriods(b.getId());
        BudgetPeriod current = periods.stream()
                .filter(p -> !today.isBefore(p.getStartDate()) && !today.isAfter(p.getEndDate()))
                .findFirst()
                .orElse(null);

        return new BudgetDtos.BudgetView(
                b.getId(), b.getName(), b.getScopeType(), b.getSubjectId(),
                b.getSubjectId() == null ? null : subjectNames.get(b.getSubjectId()),
                b.getPeriodType(), b.getPeriodValue(), b.getStartDate(), b.getEndDate(),
                money(b.getAmount()), b.getRolloverMode(),
                b.getAlertThreshold() == null ? null : b.getAlertThreshold().toPlainString(),
                Boolean.TRUE.equals(b.getEnabled()),
                current == null ? null : toPeriodView(current),
                periods.size());
    }

    private BudgetDtos.PeriodView toPeriodView(BudgetPeriod p) {
        BigDecimal available = MoneyUtil.nullToZero(p.getAvailableAmount());
        BigDecimal used = MoneyUtil.nullToZero(p.getUsedAmount());
        BigDecimal remaining = available.subtract(used);

        // 展示层截断：可用额为负时，前端显示 0 并靠 overspent 提示累计超支
        String overspent = available.compareTo(BigDecimal.ZERO) < 0
                ? available.negate().toPlainString() : null;

        return new BudgetDtos.PeriodView(
                p.getId(), p.getPeriodLabel(), p.getStartDate(), p.getEndDate(),
                money(p.getBaseAmount()), money(p.getCarryIn()),
                money(available.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : available),
                money(used), money(remaining),
                p.getExecutionRate() == null ? null : p.getExecutionRate().toPlainString(),
                money(p.getCarryFloor()), overspent, p.getSettleState());
    }

    private static String money(BigDecimal v) {
        return v == null ? null : v.setScale(MoneyUtil.SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
