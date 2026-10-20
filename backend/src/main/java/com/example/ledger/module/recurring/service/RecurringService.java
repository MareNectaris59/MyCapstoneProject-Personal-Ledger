package com.example.ledger.module.recurring.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ledger.common.constant.LedgerType;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.ledger.dto.LedgerRequests;
import com.example.ledger.module.ledger.service.LedgerCoreService;
import com.example.ledger.module.recurring.dto.RecurringDtos;
import com.example.ledger.module.recurring.entity.RecurringRule;
import com.example.ledger.module.recurring.mapper.RecurringRuleMapper;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.SubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 定期记账。
 *
 * <p>解决的是"每月固定支出要手记一遍"的问题：房租、宽带、视频会员、通勤月票。
 *
 * <p><b>三条设计约束</b>：
 *
 * <ol>
 *   <li><b>生成的账目必须走记账内核</b>，而不是自己拼 SQL。这样借贷平衡、
 *       叶子科目、未来日期、余额更新等全部校验自动继承，
 *       不存在"定期记账能绕过校验"的旁路。</li>
 *   <li><b>幂等键是确定性的</b>：{@code rec-<规则ID>-<执行日>}。
 *       同一期无论被定时任务、手动触发、还是重试碰到多少次，
 *       都只会存在一条账目 —— 这是补跑安全的前提。</li>
 *   <li><b>补跑要能追上</b>。应用关掉几天再打开，错过的每一期都要补上，
 *       而不是只补最近一期。用循环推进 {@code next_run_date}，
 *       上限 {@link RecurringSchedule#MAX_CATCH_UP} 期，防止长时间停机后
 *       一次刷出上千条。</li>
 * </ol>
 */
@Service
public class RecurringService {

    private static final Logger log = LoggerFactory.getLogger(RecurringService.class);

    private final RecurringRuleMapper ruleMapper;
    private final SubjectMapper subjectMapper;
    private final LedgerCoreService ledgerCoreService;

    public RecurringService(RecurringRuleMapper ruleMapper,
                            SubjectMapper subjectMapper,
                            LedgerCoreService ledgerCoreService) {
        this.ruleMapper = ruleMapper;
        this.subjectMapper = subjectMapper;
        this.ledgerCoreService = ledgerCoreService;
    }

    // ================================================================ 查询

    public List<RecurringDtos.View> list(Long userId) {
        List<RecurringRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<RecurringRule>()
                .eq(RecurringRule::getUserId, userId)
                .orderByAsc(RecurringRule::getNextRunDate)
                .orderByAsc(RecurringRule::getId));
        Map<Long, Subject> subjects = subjectIndex(userId);
        List<RecurringDtos.View> out = new ArrayList<>(rules.size());
        for (RecurringRule r : rules) {
            out.add(toView(r, subjects));
        }
        return out;
    }

    // ================================================================ 增删改

    @Transactional(rollbackFor = Exception.class)
    public RecurringDtos.View create(Long userId, RecurringDtos.Create req) {
        LedgerType type = parseType(req.ledgerType());
        MoneyUtil.validateAmount(req.amount());
        String frequency = parseFrequency(req.frequency());
        // 起始日允许在过去 —— 这是一个有意的选择，理由有两个：
        //
        // 1. 用户常常是「已经租了一年房」才来建规则，开始日自然在过去；
        // 2. 应用停机几天后重启时，错过的每一期都要补上。
        //    如果禁止过去日期，这条路就永远走不到。
        //
        // 补记量受 RecurringSchedule.MAX_CATCH_UP 限制，且「下次记账」在界面上可见，
        // 用户能看到有积压、也能随时停用，不会静默地多记几笔。
        if (req.startDate().isAfter(LocalDate.now().plusYears(10))) {
            throw BizException.of(ResultCode.PARAM_INVALID, "开始日期看起来不对，请检查");
        }
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw BizException.of(ResultCode.PARAM_INVALID, "结束日不能早于首次执行日");
        }

        Map<Long, Subject> subjects = subjectIndex(userId);
        validatePair(type, req.subjectId(), req.counterSubjectId(), subjects);

        Integer anchorDay = null;
        if ("MONTHLY".equals(frequency)) {
            anchorDay = req.anchorDay() != null ? req.anchorDay() : req.startDate().getDayOfMonth();
        }

        RecurringRule rule = new RecurringRule();
        rule.setUserId(userId);
        rule.setName(req.name().trim());
        rule.setLedgerType(type.name());
        rule.setAmount(MoneyUtil.scale(req.amount()));
        rule.setSubjectId(req.subjectId());
        rule.setCounterSubjectId(req.counterSubjectId());
        rule.setRemark(req.remark());
        rule.setFrequency(frequency);
        rule.setAnchorDay(anchorDay);
        rule.setNextRunDate(req.startDate());
        rule.setEndDate(req.endDate());
        rule.setEnabled(Boolean.TRUE);
        ruleMapper.insert(rule);

        log.info("新建定期记账规则 userId={} ruleId={} type={} 频率={} 首次执行={}",
                userId, rule.getId(), type, frequency, rule.getNextRunDate());
        return toView(rule, subjects);
    }

    @Transactional(rollbackFor = Exception.class)
    public RecurringDtos.View update(Long userId, Long ruleId, RecurringDtos.Update req) {
        RecurringRule rule = requireRule(userId, ruleId);
        if (req.amount() != null) {
            MoneyUtil.validateAmount(req.amount());
            rule.setAmount(MoneyUtil.scale(req.amount()));
        }
        if (req.remark() != null) {
            rule.setRemark(req.remark());
        }
        if (req.enabled() != null) {
            rule.setEnabled(req.enabled());
        }
        if (req.endDate() != null) {
            if (req.endDate().isBefore(rule.getNextRunDate())) {
                throw BizException.of(ResultCode.PARAM_INVALID,
                        "结束日不能早于下次执行日，否则这条规则再也不会执行");
            }
            rule.setEndDate(req.endDate());
        }
        ruleMapper.updateById(rule);
        return toView(rule, subjectIndex(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long ruleId) {
        requireRule(userId, ruleId);
        // 逻辑删除：已经由它生成的账目仍要保得住"从哪来"的线索
        ruleMapper.deleteById(ruleId);
    }

    // ================================================================ 执行

    /**
     * 执行某个用户所有到期的规则。给手动触发的接口用。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecurringDtos.RunResult runDueForUser(Long userId) {
        List<RecurringRule> due = ruleMapper.selectList(new LambdaQueryWrapper<RecurringRule>()
                .eq(RecurringRule::getUserId, userId)
                .eq(RecurringRule::getEnabled, true)
                .le(RecurringRule::getNextRunDate, LocalDate.now()));
        return runRules(due, LocalDate.now());
    }

    /**
     * 执行全部用户所有到期的规则。给定时任务用。
     *
     * <p>单条规则失败只记录日志并继续，不让一条坏规则卡住其他用户的定时记账 ——
     * 与"整批回滚"相比，这里的取舍是可用性优先。失败的那条下一轮会重试，
     * 而幂等键保证重试不会产生重复账目。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecurringDtos.RunResult runAllDue() {
        List<RecurringRule> due = ruleMapper.selectList(new LambdaQueryWrapper<RecurringRule>()
                .eq(RecurringRule::getEnabled, true)
                .le(RecurringRule::getNextRunDate, LocalDate.now()));
        if (due.isEmpty()) {
            return new RecurringDtos.RunResult(0, 0, 0);
        }
        return runRules(due, LocalDate.now());
    }

    private RecurringDtos.RunResult runRules(List<RecurringRule> rules, LocalDate today) {
        int generated = 0;
        int skipped = 0;
        int advanced = 0;

        for (RecurringRule rule : rules) {
            boolean changed = false;
            boolean failed = false;
            int guard = 0;
            try {
                while (RecurringSchedule.isDue(rule.getNextRunDate(), today)
                        && guard++ < RecurringSchedule.MAX_CATCH_UP) {

                    if (RecurringSchedule.isFinished(rule.getNextRunDate(), rule.getEndDate())) {
                        // 已过期：停用，不再扫它
                        rule.setEnabled(false);
                        changed = true;
                        break;
                    }

                    String key = "rec-" + rule.getId() + "-" + rule.getNextRunDate();
                    if (ledgerCoreService.existsByKey(rule.getUserId(), key)) {
                        skipped++;
                    } else {
                        postOne(rule, key);
                        generated++;
                    }

                    rule.setLastRunDate(rule.getNextRunDate());
                    rule.setNextRunDate(RecurringSchedule.nextAfter(
                            rule.getNextRunDate(), rule.getFrequency(), rule.getAnchorDay()));
                    changed = true;
                }
            } catch (BizException ex) {
                // 校验类失败（如科目被停用）：不推进日期，下一轮重试并保持可见
                failed = true;
                log.warn("定期记账执行失败，保留规则待重试 ruleId={} 执行日={} 原因={}",
                        rule.getId(), rule.getNextRunDate(), ex.getMessage());
            }

            if (changed) {
                if (!failed && RecurringSchedule.isFinished(rule.getNextRunDate(), rule.getEndDate())) {
                    rule.setEnabled(false);
                }
                ruleMapper.updateById(rule);
                advanced++;
            }
        }

        if (generated > 0 || skipped > 0) {
            log.info("定期记账执行完成 新增={} 跳过={} 涉及规则={}", generated, skipped, advanced);
        }
        return new RecurringDtos.RunResult(generated, skipped, advanced);
    }

    /** 真正写入一条账目，走记账内核以继承全部校验。 */
    private void postOne(RecurringRule rule, String idempotencyKey) {
        LedgerType type = LedgerType.valueOf(rule.getLedgerType());
        LocalDate date = rule.getNextRunDate();
        String remark = rule.getRemark() == null || rule.getRemark().isBlank()
                ? rule.getName()          // 没填备注就用规则名，用户才看得懂
                : rule.getRemark();
        switch (type) {
            case EXPENSE -> ledgerCoreService.postExpense(rule.getUserId(),
                    new LedgerRequests.Expense(rule.getAmount(), rule.getSubjectId(),
                            rule.getCounterSubjectId(), date, remark, idempotencyKey));
            case INCOME -> ledgerCoreService.postIncome(rule.getUserId(),
                    new LedgerRequests.Income(rule.getAmount(), rule.getCounterSubjectId(),
                            rule.getSubjectId(), date, remark, idempotencyKey));
            case TRANSFER -> ledgerCoreService.postTransfer(rule.getUserId(),
                    new LedgerRequests.Transfer(rule.getAmount(), rule.getSubjectId(),
                            rule.getCounterSubjectId(), date, remark, idempotencyKey));
            default -> throw BizException.of(ResultCode.PARAM_INVALID,
                    "定期记账不支持的类型：" + type);
        }
    }

    // ================================================================ 内部

    private RecurringRule requireRule(Long userId, Long ruleId) {
        RecurringRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<RecurringRule>()
                .eq(RecurringRule::getId, ruleId)
                .eq(RecurringRule::getUserId, userId));
        if (rule == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "定期记账规则不存在");
        }
        return rule;
    }

    private LedgerType parseType(String raw) {
        String text = raw == null ? "" : raw.trim().toUpperCase();
        try {
            LedgerType type = LedgerType.valueOf(text);
            if (type == LedgerType.OPENING || type == LedgerType.REVERSAL) {
                throw BizException.of(ResultCode.PARAM_INVALID,
                        "定期记账只支持支出 / 收入 / 转账");
            }
            return type;
        } catch (IllegalArgumentException ex) {
            throw BizException.of(ResultCode.PARAM_INVALID, "不支持的类型：" + raw);
        }
    }

    private String parseFrequency(String raw) {
        String text = raw == null ? "" : raw.trim().toUpperCase();
        if (!"WEEKLY".equals(text) && !"MONTHLY".equals(text)) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "频率只能是 WEEKLY 或 MONTHLY：" + raw);
        }
        return text;
    }

    /**
     * 校验"科目 + 对方账户"这对组合是否与类型匹配。
     *
     * <p>这套判断与 CSV 导入保持一致的口径，避免同一个用户在两个入口
     * 得到不同的结论。
     */
    private void validatePair(LedgerType type, Long subjectId, Long counterSubjectId,
                              Map<Long, Subject> subjects) {
        Subject subject = subjects.get(subjectId);
        Subject counter = subjects.get(counterSubjectId);
        if (subject == null || counter == null) {
            throw BizException.of(ResultCode.SUBJECT_NOT_FOUND, "科目不存在");
        }
        if (!Boolean.TRUE.equals(subject.getIsLeaf()) || !Boolean.TRUE.equals(counter.getIsLeaf())) {
            throw BizException.of(ResultCode.PARAM_INVALID, "只能选择末级科目");
        }
        SubjectType expectedSubject = switch (type) {
            case EXPENSE -> SubjectType.EXPENSE;
            case INCOME -> SubjectType.INCOME;
            case TRANSFER -> SubjectType.ASSET;
            default -> throw BizException.of(ResultCode.PARAM_INVALID, "不支持的类型：" + type);
        };
        if (subject.getSubjectType() != expectedSubject) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "「" + subject.getName() + "」的类型不对，这里需要" + expectedSubject);
        }
        if (counter.getSubjectType() != SubjectType.ASSET) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "「" + counter.getName() + "」不是账户，对方账户必须是资产类");
        }
        if (type == LedgerType.TRANSFER && subjectId.equals(counterSubjectId)) {
            throw BizException.of(ResultCode.PARAM_INVALID, "转出与转入不能是同一个账户");
        }
    }

    private Map<Long, Subject> subjectIndex(Long userId) {
        return subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                        .eq(Subject::getUserId, userId))
                .stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
    }

    private RecurringDtos.View toView(RecurringRule r, Map<Long, Subject> subjects) {
        Subject subject = subjects.get(r.getSubjectId());
        Subject counter = subjects.get(r.getCounterSubjectId());
        return new RecurringDtos.View(
                r.getId(),
                r.getName(),
                r.getLedgerType(),
                MoneyUtil.toPlainString(r.getAmount()),
                r.getSubjectId(),
                subject == null ? "(已删除)" : subject.getName(),
                r.getCounterSubjectId(),
                counter == null ? "(已删除)" : counter.getName(),
                r.getRemark(),
                r.getFrequency(),
                r.getAnchorDay(),
                r.getNextRunDate(),
                r.getEndDate(),
                r.getLastRunDate(),
                Boolean.TRUE.equals(r.getEnabled()),
                r.getCreatedAt());
    }
}
