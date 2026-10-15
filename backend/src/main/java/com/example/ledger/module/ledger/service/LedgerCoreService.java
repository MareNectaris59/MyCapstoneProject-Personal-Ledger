package com.example.ledger.module.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ledger.common.constant.Direction;
import com.example.ledger.common.constant.LedgerState;
import com.example.ledger.common.constant.LedgerType;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.budget.service.BudgetDirtyMarker;
import com.example.ledger.module.ledger.dto.LedgerDetail;
import com.example.ledger.module.ledger.dto.LedgerRequests;
import com.example.ledger.module.ledger.entity.Entry;
import com.example.ledger.module.ledger.entity.Ledger;
import com.example.ledger.module.ledger.mapper.EntryMapper;
import com.example.ledger.module.ledger.mapper.LedgerMapper;
import com.example.ledger.module.subject.entity.AccountMeta;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.AccountMetaMapper;
import com.example.ledger.module.subject.mapper.SubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记账内核。
 *
 * <p>这是整个系统的心脏，承担：分录构造 → 借贷平衡校验 → 分录落库 →
 * 账户余额更新 → 预算置脏。所有写方法都在<b>单个事务</b>内完成，
 * 要么全成功要么全回滚。
 *
 * <p>事务边界与并发控制见《系统设计说明书》§7.7：
 * <ul>
 *   <li>隔离级别取 {@code READ_COMMITTED}。MySQL 默认的 RR 在记账场景下
 *       会带来更多间隙锁与死锁风险，配合行锁原子更新 RC 已经足够</li>
 *   <li>余额更新依赖 {@code balance = balance + delta} 的原子性，
 *       无需显式 {@code SELECT ... FOR UPDATE}</li>
 *   <li>多科目更新<b>按 subjectId 升序</b>，保证所有事务加锁顺序一致，
 *       从根上消除死锁</li>
 *   <li>事务内<b>不做</b> HTTP 调用、邮件、预警计算等外部动作</li>
 * </ul>
 */
@Service
public class LedgerCoreService {

    private static final Logger log = LoggerFactory.getLogger(LedgerCoreService.class);

    private final LedgerMapper ledgerMapper;
    private final EntryMapper entryMapper;
    private final SubjectMapper subjectMapper;
    private final AccountMetaMapper accountMetaMapper;
    private final BudgetDirtyMarker budgetDirtyMarker;
    private final BalanceValidator balanceValidator;

    public LedgerCoreService(LedgerMapper ledgerMapper,
                             EntryMapper entryMapper,
                             SubjectMapper subjectMapper,
                             AccountMetaMapper accountMetaMapper,
                             BudgetDirtyMarker budgetDirtyMarker,
                             BalanceValidator balanceValidator) {
        this.ledgerMapper = ledgerMapper;
        this.entryMapper = entryMapper;
        this.subjectMapper = subjectMapper;
        this.accountMetaMapper = accountMetaMapper;
        this.budgetDirtyMarker = budgetDirtyMarker;
        this.balanceValidator = balanceValidator;
    }

    // ================================================================
    // 记账
    // ================================================================

    /** 记一笔支出：借 费用科目 / 贷 资产科目。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public LedgerDetail postExpense(Long userId, LedgerRequests.Expense req) {
        MoneyUtil.validateAmount(req.amount());
        checkNotFuture(req.occurredDate());

        Ledger existing = findExistingByKey(userId, req.idempotencyKey());
        if (existing != null) {
            return getDetail(userId, existing.getId());
        }

        List<Subject> subjects = loadSubjects(userId, req.expenseSubjectId(), req.assetSubjectId());
        Subject expense = pick(subjects, req.expenseSubjectId());
        Subject asset = pick(subjects, req.assetSubjectId());

        Ledger ledger = newLedger(userId, LedgerType.EXPENSE, req.amount(), req.occurredDate(),
                req.remark(), req.idempotencyKey());

        List<Entry> entries = List.of(
                Entry.of(ledger.getId(), 1, expense.getId(), Direction.DEBIT, req.amount(), expense.getName()),
                Entry.of(ledger.getId(), 2, asset.getId(), Direction.CREDIT, req.amount(), asset.getName()));

        return commit(userId, ledger, entries, subjects, true);
    }

    /** 记一笔收入：借 资产科目 / 贷 收入科目。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public LedgerDetail postIncome(Long userId, LedgerRequests.Income req) {
        MoneyUtil.validateAmount(req.amount());
        checkNotFuture(req.occurredDate());

        Ledger existing = findExistingByKey(userId, req.idempotencyKey());
        if (existing != null) {
            return getDetail(userId, existing.getId());
        }

        List<Subject> subjects = loadSubjects(userId, req.assetSubjectId(), req.incomeSubjectId());
        Subject asset = pick(subjects, req.assetSubjectId());
        Subject income = pick(subjects, req.incomeSubjectId());

        Ledger ledger = newLedger(userId, LedgerType.INCOME, req.amount(), req.occurredDate(),
                req.remark(), req.idempotencyKey());

        List<Entry> entries = List.of(
                Entry.of(ledger.getId(), 1, asset.getId(), Direction.DEBIT, req.amount(), asset.getName()),
                Entry.of(ledger.getId(), 2, income.getId(), Direction.CREDIT, req.amount(), income.getName()));

        return commit(userId, ledger, entries, subjects, false);
    }

    /**
     * 记一笔转账：借 转入账户 / 贷 转出账户。
     *
     * <p>转账不产生费用/收入分录，因此<b>不影响任何预算</b>，也不计入消费分析
     * （BR-04、BR-05）。它是复式记账相对单式记账最直观的收益点：
     * 转账前后全部资产科目余额之和不变，而这个恒等式可被系统自动验证。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public LedgerDetail postTransfer(Long userId, LedgerRequests.Transfer req) {
        MoneyUtil.validateAmount(req.amount());
        checkNotFuture(req.occurredDate());
        if (req.fromSubjectId().equals(req.toSubjectId())) {
            throw BizException.of(ResultCode.TRANSFER_SAME_ACCOUNT);
        }

        Ledger existing = findExistingByKey(userId, req.idempotencyKey());
        if (existing != null) {
            return getDetail(userId, existing.getId());
        }

        List<Subject> subjects = loadSubjects(userId, req.fromSubjectId(), req.toSubjectId());
        Subject from = pick(subjects, req.fromSubjectId());
        Subject to = pick(subjects, req.toSubjectId());

        Ledger ledger = newLedger(userId, LedgerType.TRANSFER, req.amount(), req.occurredDate(),
                req.remark(), req.idempotencyKey());

        List<Entry> entries = List.of(
                Entry.of(ledger.getId(), 1, to.getId(), Direction.DEBIT, req.amount(), to.getName()),
                Entry.of(ledger.getId(), 2, from.getId(), Direction.CREDIT, req.amount(), from.getName()));

        return commit(userId, ledger, entries, subjects, false);
    }

    /**
     * 期初建账：借 资产科目 / 贷 权益·期初余额。
     *
     * <p>由 {@code SubjectService} 设置期初余额时调用，同时更新
     * {@code account_meta.opening_balance}。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public LedgerDetail postOpening(Long userId, Long assetSubjectId, Long equitySubjectId,
                                    BigDecimal amount, LocalDate occurredDate) {
        MoneyUtil.validateAmount(amount);
        checkNotFuture(occurredDate);

        List<Subject> subjects = loadSubjects(userId, assetSubjectId, equitySubjectId);
        Subject asset = pick(subjects, assetSubjectId);
        Subject equity = pick(subjects, equitySubjectId);

        Ledger ledger = newLedger(userId, LedgerType.OPENING, amount, occurredDate, "期初建账", null);

        List<Entry> entries = List.of(
                Entry.of(ledger.getId(), 1, asset.getId(), Direction.DEBIT, amount, asset.getName()),
                Entry.of(ledger.getId(), 2, equity.getId(), Direction.CREDIT, amount, equity.getName()));

        return commit(userId, ledger, entries, subjects, false);
    }

    // ================================================================
    // 冲正
    // ================================================================

    /**
     * 冲正一笔已记账凭证（《系统设计说明书》§7.4）。
     *
     * <p>采用<b>反向分录法</b>：新增一张独立凭证，其分录方向与原凭证逐条相反、
     * 金额相等；原分录<b>永不修改或删除</b>。账务因此保持 append-only，
     * 任何时点的账目状态都可还原与审计。
     *
     * <p>统计口径上原分录与反向分录代数和为 0，等价于"该笔业务未发生"，
     * 所以所有统计 SQL 都不需要额外的"排除已冲正"过滤。
     *
     * @param amount 冲正金额；null 表示全额冲正
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public LedgerDetail reverse(Long userId, Long ledgerId, BigDecimal amount, String reason) {
        // 行锁原凭证，串行化并发冲正（§7.4.3）
        Ledger original = ledgerMapper.selectForUpdate(ledgerId);
        if (original == null || !original.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.LEDGER_NOT_FOUND);
        }
        if (original.getLedgerType() == LedgerType.REVERSAL) {
            throw BizException.of(ResultCode.LEDGER_STATE_INVALID, "冲正单不能再被冲正");
        }
        if (!original.isReversible()) {
            throw BizException.of(ResultCode.LEDGER_ALREADY_REVERSED);
        }

        BigDecimal reversible = original.reversibleAmount();
        BigDecimal reversalAmount = amount == null ? reversible : amount;
        MoneyUtil.validateAmount(reversalAmount);
        if (reversalAmount.compareTo(reversible) > 0) {
            throw BizException.of(ResultCode.REVERSAL_AMOUNT_EXCEEDED,
                    "最多可冲正 " + MoneyUtil.toPlainString(reversible));
        }

        List<Entry> originalEntries = listEntries(ledgerId);
        List<Subject> subjects = loadSubjectsByIds(userId,
                originalEntries.stream().map(Entry::getSubjectId).distinct().toList());

        Ledger reversal = newLedger(userId, LedgerType.REVERSAL, reversalAmount,
                LocalDate.now(), reason, null);
        reversal.setReversalOfId(original.getId());
        reversal.setReversalReason(reason);
        ledgerMapper.updateById(reversal);

        // 构造反向分录，金额按比例拆分，末条承担舍入差额（§7.4.2 R4）
        List<Entry> reversalEntries = buildReversalEntries(
                original, originalEntries, reversal.getId(), reversalAmount);

        // 冲正单同样要过平衡校验（会跳过 C6 科目组合校验，见 BalanceValidator）
        balanceValidator.validate(LedgerType.REVERSAL, reversalEntries, subjects, userId);
        for (Entry e : reversalEntries) {
            entryMapper.insert(e);
        }
        applyBalanceDeltas(userId, reversalEntries);

        // 更新原凭证的已冲正金额与状态
        BigDecimal newReversed = MoneyUtil.nullToZero(original.getReversedAmount()).add(reversalAmount);
        original.setReversedAmount(newReversed);
        original.setState(LedgerState.resolve(original.getAmount(), newReversed));
        ledgerMapper.updateById(original);

        // 冲正影响原记账日期所属周期的预算，需重新标记
        markBudgetsDirty(userId, original.getOccurredDate());

        log.info("冲正完成 userId={} originalId={} reversalId={} amount={} 原状态={}",
                userId, original.getId(), reversal.getId(),
                MoneyUtil.toPlainString(reversalAmount), original.getState());

        return getDetail(userId, reversal.getId());
    }

    /**
     * 按比例把冲正金额拆分到各条原分录上。
     *
     * <p>原凭证金额 100.00、两条分录各 100.00，用户冲正 33.33 时两条反向分录
     * 各 33.33，恰好平衡。若出现除不尽，前 n−1 条按 HALF_UP 舍入，
     * 最后一条用「冲正金额 − 本方向已分配合计」补齐，保证冲正单自身借贷平衡。
     */
    private List<Entry> buildReversalEntries(Ledger original, List<Entry> originalEntries,
                                             Long reversalLedgerId, BigDecimal reversalAmount) {
        BigDecimal originalAmount = original.getAmount();
        boolean fullReversal = reversalAmount.compareTo(originalAmount) == 0;

        List<Entry> result = new ArrayList<>(originalEntries.size());
        BigDecimal debitAssigned = BigDecimal.ZERO;
        BigDecimal creditAssigned = BigDecimal.ZERO;

        for (int i = 0; i < originalEntries.size(); i++) {
            Entry src = originalEntries.get(i);
            boolean last = (i == originalEntries.size() - 1);

            BigDecimal piece;
            if (fullReversal) {
                piece = src.getAmount();
            } else if (last) {
                BigDecimal already = src.getDirection() == Direction.DEBIT ? debitAssigned : creditAssigned;
                piece = reversalAmount.subtract(already);
            } else {
                piece = src.getAmount()
                        .multiply(reversalAmount)
                        .divide(originalAmount, MoneyUtil.SCALE, RoundingMode.HALF_UP);
            }

            if (src.getDirection() == Direction.DEBIT) {
                debitAssigned = debitAssigned.add(piece);
            } else {
                creditAssigned = creditAssigned.add(piece);
            }

            result.add(Entry.reversalOf(reversalLedgerId, src.getEntryNo(), src, piece));
        }
        return result;
    }

    // ================================================================
    // 查询
    // ================================================================

    /** 凭证详情（含借贷分录与相关科目信息）。 */
    @Transactional(readOnly = true)
    public LedgerDetail getDetail(Long userId, Long ledgerId) {
        Ledger ledger = ledgerMapper.selectById(ledgerId);
        if (ledger == null || !ledger.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.LEDGER_NOT_FOUND);
        }
        List<Entry> entries = listEntries(ledgerId);
        return LedgerDetail.of(ledger, entries, subjectIndex(userId, entries));
    }

    /** 账目分页查询。 */
    @Transactional(readOnly = true)
    public IPage<Ledger> page(Long userId, long pageNo, long pageSize,
                             LocalDate startDate, LocalDate endDate, LedgerType ledgerType) {
        LambdaQueryWrapper<Ledger> qw = new LambdaQueryWrapper<Ledger>()
                .eq(Ledger::getUserId, userId)
                .ge(startDate != null, Ledger::getOccurredDate, startDate)
                .le(endDate != null, Ledger::getOccurredDate, endDate)
                .eq(ledgerType != null, Ledger::getLedgerType, ledgerType)
                .orderByDesc(Ledger::getOccurredDate)
                .orderByDesc(Ledger::getId);
        return ledgerMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /** 取某凭证的全部分录，按序号升序。 */
    public List<Entry> listEntries(Long ledgerId) {
        return entryMapper.selectList(new LambdaQueryWrapper<Entry>()
                .eq(Entry::getLedgerId, ledgerId)
                .orderByAsc(Entry::getEntryNo));
    }

    /** 某科目的最新余额缓存值。 */
    public BigDecimal cachedBalance(Long subjectId) {
        AccountMeta meta = accountMetaMapper.selectOne(new LambdaQueryWrapper<AccountMeta>()
                .eq(AccountMeta::getSubjectId, subjectId));
        return meta == null ? MoneyUtil.ZERO : meta.getBalance();
    }

    /** 由分录汇总得出的科目余额（"真相"），用于与缓存比对。 */
    public BigDecimal computedBalance(Long subjectId) {
        return MoneyUtil.nullToZero(entryMapper.sumSignedAmountBySubject(subjectId));
    }

    /**
     * 全局借贷平衡校验（《系统设计说明书》§7.3.2）。
     *
     * <p>期望 {@code diff = 0.00}，非 0 即为系统级严重错误。
     */
    public Map<String, BigDecimal> globalBalanceCheck() {
        return entryMapper.globalBalanceCheck();
    }

    /**
     * 账户余额与分录汇总的一致性校验。
     *
     * <p>期望返回<b>空列表</b>。有结果说明 {@code account_meta.balance}
     * 这个缓存与流水不一致，需要重算修复。
     */
    public List<Map<String, Object>> accountBalanceDrift() {
        return entryMapper.accountBalanceDrift();
    }

    // ================================================================
    // 内部实现
    // ================================================================

    /**
     * 公共落库流程：校验 → 插分录 → 更新余额 → 预算置脏。
     * 凭证主记录已由 {@code newLedger} 插入。
     *
     * @param affectsBudget 是否影响预算（支出为 true，收入/转账/建账为 false）
     */
    private LedgerDetail commit(Long userId, Ledger ledger, List<Entry> entries,
                               List<Subject> subjects, boolean affectsBudget) {
        balanceValidator.validate(ledger.getLedgerType(), entries, subjects, userId);

        for (Entry e : entries) {
            entryMapper.insert(e);
        }
        applyBalanceDeltas(userId, entries);

        if (affectsBudget) {
            markBudgetsDirty(userId, ledger.getOccurredDate());
        }

        log.info("记账完成 userId={} ledgerId={} type={} amount={}",
                userId, ledger.getId(), ledger.getLedgerType(),
                MoneyUtil.toPlainString(ledger.getAmount()));

        return getDetail(userId, ledger.getId());
    }

    /**
     * 创建凭证主记录。
     *
     * <p>凭证号在<b>插入前</b>生成（{@code ledger_no} 是 NOT NULL，无默认值）。
     * 格式为 {@code LE + yyyyMMdd + HHmmss + 3 位序号}，唯一性由
     * {@code uk_user_no} 与时间精度共同保证。
     *
     * <p>刻意不使用 {@code COUNT(*)+1} 做当日流水：并发下两个请求会算出同一个号，
     * 虽有唯一索引兜底不致脏数据，但会表现为随机失败
     * （《数据库设计.md》§3.5 设计说明 5 的推荐方案）。
     *
     * <p>状态直接落为 {@code POSTED}：{@code CREATED} 只应存在于事务内，
     * 避免多一次无意义的 UPDATE，也避免提交后仍残留 CREATED 状态。
     */
    private Ledger newLedger(Long userId, LedgerType type, BigDecimal amount,
                             LocalDate occurredDate, String remark, String idempotencyKey) {
        Ledger ledger = new Ledger();
        ledger.setUserId(userId);
        ledger.setLedgerType(type);
        // 状态直接落 POSTED —— CREATED 只是事务内的瞬时态
        ledger.setState(LedgerState.POSTED);
        ledger.setAmount(MoneyUtil.scale(amount));
        ledger.setReversedAmount(MoneyUtil.ZERO);
        ledger.setOccurredDate(occurredDate);
        ledger.setRemark(remark);
        ledger.setPostedAt(LocalDateTime.now());
        ledger.setLedgerNo(buildLedgerNo(userId, occurredDate));
        ledger.setIdempotencyKey(idempotencyKey);
        ledgerMapper.insert(ledger);
        return ledger;
    }

    /**
     * 按用户与日期递增的当日流水号。
     *
     * <p>键为 {@code userId + "@" + yyyyMMdd}，值为已分配到的最大序号。
     * 用 {@link ConcurrentHashMap#merge} 做原子自增，无需额外加锁。
     */
    private final ConcurrentHashMap<String, AtomicInteger> dailySeq = new ConcurrentHashMap<>();

    /**
     * 生成凭证号：{@code LE + 业务发生日 yyyyMMdd + 4 位当日流水}。
     *
     * <p>日期段取 <b>{@code occurredDate}（业务发生日）</b>，不是记账当天 ——
     * 补记上个月的账时，凭证号应当属于上个月，否则按凭证号排序会与账期错位。
     * 流水号按"用户 + 业务发生日"独立计数。
     *
     * <p><b>为什么不用"时间戳 + 毫秒低 3 位"？</b>
     * 早期实现是 {@code LE + yyyyMMdd + HHmmss + 毫秒低3位}，注释称"唯一性由
     * uk_user_no 与时间精度共同保证"。这个假设在并发下不成立：
     * 物理上不可能给同一毫秒内的多个请求分配不同的 3 位序号，于是它们算出
     * <b>完全相同的凭证号</b>，撞 {@code uk_user_no} 报
     * {@code Duplicate entry '<userId>-LE...'}，对外表现为 500 或随机失败。
     *
     * <p>实测：100 并发各记一笔，约 25%–50% 的请求因此失败（AC-07）。
     * 单机顺序测试永远发现不了这个问题 —— 同一毫秒不会发出两个请求。
     *
     * <p><b>为什么不用 {@code COUNT(*)+1}？</b>
     * 并发下两个事务会读到同一个计数，正是上面同类问题的另一种写法。
     *
     * <p><b>内存计数 + 数据库兜底</b>：序号主体来自 {@link #dailySeq}
     * （{@link ConcurrentHashMap#merge} 原子自增，并发安全）。但纯内存计数
     * 在应用重启后会从 0 重新开始，与当天已存在的凭证号冲突。
     * 因此每次取号时都与数据库中该用户该日的最大流水号取较大者：
     * <ol>
     *   <li>{@code ledgerMapper.selectMaxLedgerNo} 取 {@code MAX(ledger_no)}</li>
     *   <li>解析出末 4 位流水号，与内存计数比较</li>
     *   <li>首次遇到某 (用户, 日期) 时用数据库值作为起点</li>
     * </ol>
     * 这样重启、甚至换实例后只要读取一次数据库就能接上已有流水。
     * 代价是每次记账多一条 {@code MAX} 查询 —— 该列上有索引
     * （{@code uk_user_no} 的最左前缀 user_id），开销可接受。
     *
     * <p><b>剩下的局限</b>：多实例同时写同一天会有竞态（各自读到相同的 MAX）。
     * 本项目单实例部署，不受影响；多实例应改用 Redis INCR 或数据库序列。
     */
    private String buildLedgerNo(Long userId, LocalDate occurredDate) {
        String day = occurredDate.toString().replace("-", "");
        String prefix = "LE" + day;
        String key = userId + "@" + day;

        // 数据库里已有的最大流水号（重启后靠它接上，避免从 0001 重来）
        int dbMax = 0;
        String maxNo = ledgerMapper.selectMaxLedgerNo(userId, prefix + "%");
        if (maxNo != null && maxNo.length() == prefix.length() + 4) {
            String tail = maxNo.substring(prefix.length());
            if (tail.chars().allMatch(Character::isDigit)) {
                dbMax = Integer.parseInt(tail);
            }
        }

        final int floor = dbMax;
        // merge 的 remapping 函数里把内存计数抬到不低于 dbMax，再自增
        int seq = dailySeq.merge(key, new AtomicInteger(Math.max(floor, 0) + 1), (existing, seed) -> {
            if (existing.get() < floor) {
                existing.set(floor);
            }
            existing.incrementAndGet();
            return existing;
        }).get();

        return prefix + String.format("%04d", seq);
    }

    /**
     * 更新账户余额缓存。
     *
     * <p>只有资产类科目维护 {@code account_meta.balance}；费用/收入/权益类
     * 科目不维护余额缓存，需要时由 {@code entry} 汇总得出。
     *
     * <p><b>按 subjectId 升序更新</b>，保证所有事务加锁顺序一致，消除死锁。
     */
    private void applyBalanceDeltas(Long userId, List<Entry> entries) {
        Map<Long, BigDecimal> deltaBySubject = new LinkedHashMap<>();
        for (Entry e : entries) {
            deltaBySubject.merge(e.getSubjectId(), e.getSignedAmount(), BigDecimal::add);
        }

        List<Long> assetSubjectIds = subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                        .eq(Subject::getUserId, userId)
                        .in(Subject::getId, deltaBySubject.keySet())
                        .eq(Subject::getSubjectType, SubjectType.ASSET))
                .stream().map(Subject::getId).sorted().toList();

        for (Long subjectId : assetSubjectIds) {
            int affected = accountMetaMapper.applyDelta(subjectId, deltaBySubject.get(subjectId));
            if (affected != 1) {
                // 影响行数不为 1 说明账户扩展行缺失或已被删除，视为并发冲突，回滚整个事务
                throw BizException.of(ResultCode.DB_ERROR,
                        "账户余额更新失败，受影响行数=" + affected + "，科目ID=" + subjectId);
            }
        }
    }

    /**
     * 把受影响的预算周期标为待重算。
     *
     * <p><b>只置脏标记，不做增量扣减。</b>增量累加一旦某次失败或重复执行
     * 就会永久漂移且无法自愈；由 {@code entry} 重算则永远正确
     * （《系统设计说明书》§7.5.4）。
     *
     * <p>规则本身属于预算模块（"哪些预算受影响"），因此实现在
     * {@link BudgetDirtyMarker} 里。这样账务不必知道预算的范围口径，
     * 依赖方向也不成环（账务 → 预算置脏器，预算服务 → 账务数据由 SQL 直读）。
     */
    private void markBudgetsDirty(Long userId, LocalDate occurredDate) {
        budgetDirtyMarker.markDirty(userId, occurredDate);
    }

    private Map<Long, Subject> subjectIndex(Long userId, List<Entry> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = entries.stream().map(Entry::getSubjectId).distinct().toList();
        Map<Long, Subject> map = new HashMap<>();
        for (Subject s : loadSubjectsByIds(userId, ids)) {
            map.put(s.getId(), s);
        }
        return map;
    }

    private List<Subject> loadSubjects(Long userId, Long... subjectIds) {
        return loadSubjectsByIds(userId, List.of(subjectIds));
    }

    private List<Subject> loadSubjectsByIds(Long userId, List<Long> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            throw BizException.of(ResultCode.SUBJECT_NOT_FOUND, "未指定科目");
        }
        return subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId)
                .in(Subject::getId, subjectIds));
    }

    /**
     * 幂等查询：同一用户 + 同一幂等键已有凭证时直接返回它。
     *
     * <p>这是幂等防护的第一层（第二层是 {@code uk_idem} 唯一索引兜底，
     * 见《系统设计说明书》§7.7.3）。注意<b>必须带 user_id</b> 一起查 ——
     * 幂等键由客户端生成，不同用户可能撞上同一个 UUID。
     *
     * <p>命中后返回的是「当时的凭证」，而不是重放当时的完整响应体快照。
     * 对记账接口而言这正是我们想要的语义：重复提交不会重复记账，
     * 而返回的凭证内容与首次一致（凭证 0 落库后不可变）。
     */
    private Ledger findExistingByKey(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        Ledger existing = ledgerMapper.selectOne(new LambdaQueryWrapper<Ledger>()
                .eq(Ledger::getUserId, userId)
                .eq(Ledger::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("幂等命中，直接返回已有凭证 userId={} key={} ledgerId={}",
                    userId, idempotencyKey, existing.getId());
        }
        return existing;
    }

    private Subject pick(List<Subject> subjects, Long id) {
        return subjects.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> BizException.of(ResultCode.SUBJECT_NOT_FOUND, "科目不存在：" + id));
    }

    /** 记账日期不得晚于服务器当前日期 + 1 天（BR-02，容忍时区差异）。 */
    private void checkNotFuture(LocalDate occurredDate) {
        if (occurredDate == null) {
            throw BizException.of(ResultCode.DATE_INVALID, "记账日期不能为空");
        }
        if (occurredDate.isAfter(LocalDate.now().plusDays(1))) {
            throw BizException.of(ResultCode.DATE_INVALID, "记账日期不能晚于今天");
        }
    }
}
