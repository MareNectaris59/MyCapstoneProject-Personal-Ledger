package com.example.ledger.module.subject.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.ledger.entity.Entry;
import com.example.ledger.module.ledger.entity.Ledger;
import com.example.ledger.module.ledger.mapper.EntryMapper;
import com.example.ledger.module.ledger.mapper.LedgerMapper;
import com.example.ledger.module.ledger.service.LedgerCoreService;
import com.example.ledger.module.subject.dto.SubjectTreeNode;
import com.example.ledger.module.subject.entity.AccountMeta;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.AccountMetaMapper;
import com.example.ledger.module.subject.mapper.SubjectMapper;
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
 * 科目服务：科目树查询、账户余额、期初建账。
 */
@Service
public class SubjectService {

    private static final Logger log = LoggerFactory.getLogger(SubjectService.class);

    private final SubjectMapper subjectMapper;
    private final AccountMetaMapper accountMetaMapper;
    private final EntryMapper entryMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerCoreService ledgerCoreService;
    private final SubjectTemplateService subjectTemplateService;

    public SubjectService(SubjectMapper subjectMapper,
                          AccountMetaMapper accountMetaMapper,
                          EntryMapper entryMapper,
                          LedgerMapper ledgerMapper,
                          LedgerCoreService ledgerCoreService,
                          SubjectTemplateService subjectTemplateService) {
        this.subjectMapper = subjectMapper;
        this.accountMetaMapper = accountMetaMapper;
        this.entryMapper = entryMapper;
        this.ledgerMapper = ledgerMapper;
        this.ledgerCoreService = ledgerCoreService;
        this.subjectTemplateService = subjectTemplateService;
    }

    /**
     * 查询科目树。
     *
     * <p>一次查出全部科目后在内存中建树，<b>不写递归 SQL</b> ——
     * 个人记账的数据量（每用户几十到上百条）下，内存建树更快也更好调试
     * （《系统设计说明书》§7.6.6）。
     *
     * @param type 可选，按科目类型过滤（如只取费用类用于记账下拉框）
     */
    /**
     * 树节点排序：先 sortOrder，再 id（保证顺序稳定）。
     *
     * <p>注意排序对象是 {@link SubjectTreeNode} 而非 {@link Subject} ——
     * 建树的目标结构是前者，用后者的比较器会类型不匹配。
     */
    private static final Comparator<SubjectTreeNode> NODE_ORDER = Comparator
            .comparing(SubjectTreeNode::sortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SubjectTreeNode::id);

    public List<SubjectTreeNode> tree(Long userId, SubjectType type) {
        List<Subject> all = listAll(userId);
        Map<Long, AccountMeta> accountMeta = accountMetaIndex(all);

        // 分组：parentId -> 子科目
        Map<Long, List<Subject>> byParent = new LinkedHashMap<>();
        for (Subject s : all) {
            byParent.computeIfAbsent(s.getParentId(), k -> new ArrayList<>()).add(s);
        }

        List<SubjectTreeNode> roots = new ArrayList<>();
        for (Subject s : byParent.getOrDefault(0L, List.of())) {
            if (type != null && s.getSubjectType() != type) {
                continue;
            }
            roots.add(build(s, byParent, accountMeta));
        }
        roots.sort(NODE_ORDER);
        return roots;
    }

    private SubjectTreeNode build(Subject s, Map<Long, List<Subject>> byParent,
                                  Map<Long, AccountMeta> accountMetaIndex) {
        List<SubjectTreeNode> children = new ArrayList<>();
        for (Subject child : byParent.getOrDefault(s.getId(), List.of())) {
            children.add(build(child, byParent, accountMetaIndex));
        }
        children.sort(NODE_ORDER);

        AccountMeta meta = accountMetaIndex.get(s.getId());
        return new SubjectTreeNode(
                s.getId(), s.getParentId(), s.getSubjectCode(), s.getName(),
                s.getSubjectType() == null ? null : s.getSubjectType().name(),
                s.getNormalSide() == null ? null : s.getNormalSide().name(),
                s.getLevel(),
                Boolean.TRUE.equals(s.getIsLeaf()),
                Boolean.TRUE.equals(s.getBudgetable()),
                Boolean.TRUE.equals(s.getEnabled()),
                s.getIcon(), s.getColor(), s.getSortOrder(),
                meta == null ? null : meta.getAccountKind(),
                meta == null ? null : MoneyUtil.toPlainString(meta.getBalance()),
                meta == null ? null : meta.getIncludeInNetWorth(),
                children);
    }

    /** 全部资产科目（资金账户）及其余额，用于账户选择器与净资产卡片。 */
    public List<SubjectTreeNode> assetAccounts(Long userId) {
        List<Subject> assets = subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId)
                .eq(Subject::getSubjectType, SubjectType.ASSET)
                .orderByAsc(Subject::getSortOrder)
                .orderByAsc(Subject::getId));
        Map<Long, AccountMeta> meta = accountMetaIndex(assets);

        List<SubjectTreeNode> result = new ArrayList<>();
        for (Subject a : assets) {
            if (!Boolean.TRUE.equals(a.getIsLeaf())) {
                continue;
            }
            AccountMeta m = meta.get(a.getId());
            result.add(new SubjectTreeNode(
                    a.getId(), a.getParentId(), a.getSubjectCode(), a.getName(),
                    a.getSubjectType().name(), a.getNormalSide().name(), a.getLevel(),
                    true, false, Boolean.TRUE.equals(a.getEnabled()),
                    a.getIcon(), a.getColor(), a.getSortOrder(),
                    m == null ? null : m.getAccountKind(),
                    m == null ? "0.00" : MoneyUtil.toPlainString(m.getBalance()),
                    m == null ? null : m.getIncludeInNetWorth(),
                    List.of()));
        }
        return result;
    }

    /**
     * 科目余额。
     *
     * <p>同时返回缓存值与由分录重算的值，两者不一致即为数据漂移，
     * 这是对账逻辑在接口层的直接体现（《系统设计说明书》§7.3.2）。
     */
    public Map<String, Object> balance(Long userId, Long subjectId) {
        Subject s = requireOwned(userId, subjectId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subjectId", s.getId());
        result.put("subjectName", s.getName());
        result.put("subjectType", typeName(s));
        result.put("signedSum", MoneyUtil.toPlainString(entryMapper.sumSignedAmountBySubject(subjectId)));

        AccountMeta meta = findAccountMeta(subjectId);
        result.put("cachedBalance", meta == null ? null : MoneyUtil.toPlainString(meta.getBalance()));
        result.put("openingBalance", meta == null ? null : MoneyUtil.toPlainString(meta.getOpeningBalance()));
        result.put("accountKind", meta == null ? null : meta.getAccountKind());

        if (meta != null) {
            BigDecimal computed = MoneyUtil.nullToZero(entryMapper.sumSignedAmountBySubject(subjectId));
            boolean consistent = meta.getBalance().compareTo(computed) == 0;
            result.put("consistent", consistent);
            result.put("drift", consistent ? "0.00"
                    : meta.getBalance().subtract(computed).toPlainString());
        }
        return result;
    }

    /**
     * 某个资产科目的资金变动明细（分页）。
     */
    public List<Map<String, Object>> accountEntries(Long userId, Long subjectId, int limit) {
        requireOwned(userId, subjectId);
        List<Entry> entries = entryMapper.selectList(new LambdaQueryWrapper<Entry>()
                .eq(Entry::getSubjectId, subjectId)
                .orderByDesc(Entry::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry e : entries) {
            Ledger ledger = ledgerMapper.selectById(e.getLedgerId());
            if (ledger == null || !ledger.getUserId().equals(userId)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entryId", e.getId());
            row.put("ledgerId", ledger.getId());
            row.put("ledgerNo", ledger.getLedgerNo());
            row.put("ledgerType", ledger.getLedgerType() == null ? null : ledger.getLedgerType().name());
            row.put("state", ledger.getState() == null ? null : ledger.getState().name());
            row.put("occurredDate", ledger.getOccurredDate() == null ? null : ledger.getOccurredDate().toString());
            row.put("direction", e.getDirection() == null ? null : e.getDirection().name());
            row.put("amount", MoneyUtil.toPlainString(e.getAmount()));
            row.put("signedAmount", MoneyUtil.toPlainString(e.getSignedAmount()));
            row.put("summary", e.getSummary());
            row.put("remark", ledger.getRemark());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 设置期初余额：生成一张「借 资产 / 贷 权益·期初余额」的建账凭证。
     *
     * <p>这是资产负债表能"平"的起点 —— 账户里的钱不是凭空出现的，
     * 它的来源是权益。同时更新 {@code account_meta.opening_balance}。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setOpeningBalance(Long userId, Long assetSubjectId, BigDecimal amount,
                                                 LocalDate occurredDate) {
        Subject asset = requireOwned(userId, assetSubjectId);
        if (!asset.isAssetSubject()) {
            throw BizException.of(ResultCode.SUBJECT_TYPE_INVALID, "只有资产类科目可以设置期初余额");
        }
        if (!Boolean.TRUE.equals(asset.getIsLeaf())) {
            throw BizException.of(ResultCode.SUBJECT_NOT_LEAF);
        }
        if (!MoneyUtil.isPositive(amount)) {
            throw BizException.of(ResultCode.AMOUNT_INVALID, "期初余额必须大于 0");
        }

        Subject equity = subjectMapper.selectOne(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId)
                .eq(Subject::getSubjectType, SubjectType.EQUITY)
                .eq(Subject::getIsLeaf, true)
                .orderByAsc(Subject::getId)
                .last("LIMIT 1"));
        if (equity == null) {
            throw BizException.of(ResultCode.SUBJECT_NOT_FOUND, "缺少权益类科目，无法建账");
        }

        LocalDate date = occurredDate == null ? LocalDate.now() : occurredDate;
        var detail = ledgerCoreService.postOpening(userId, asset.getId(), equity.getId(), amount, date);

        AccountMeta meta = findAccountMeta(asset.getId());
        if (meta != null) {
            meta.setOpeningBalance(MoneyUtil.scale(amount));
            accountMetaMapper.updateById(meta);
        }

        log.info("期初建账完成 userId={} subjectId={} amount={}", userId, asset.getId(),
                MoneyUtil.toPlainString(amount));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ledger", detail);
        result.put("balance", MoneyUtil.toPlainString(ledgerCoreService.cachedBalance(asset.getId())));
        return result;
    }

    /** 新增科目。 */
    @Transactional(rollbackFor = Exception.class)
    public Subject create(Long userId, Long parentId, String name, SubjectType type,
                          String accountKind, String color) {
        if (name == null || name.isBlank()) {
            throw BizException.of(ResultCode.PARAM_INVALID, "科目名称不能为空");
        }
        Subject subject = new Subject();
        subject.setUserId(userId);
        subject.setName(name.trim());
        subject.setSubjectType(type);
        subject.setNormalSide(type.getNormalSide());
        subject.setColor(color);
        subject.setSortOrder(0);
        subject.setEnabled(true);
        subject.setDeleted(0);

        Long parent = parentId == null ? 0L : parentId;
        if (parent == 0L) {
            subject.setParentId(0L);
            subject.setLevel(1);
            subject.setSubjectCode(nextCode(userId, type, null));
            subject.setIsLeaf(true);
            subject.setBudgetable(type.isExpense());
            subject.setPath("/");
            subjectMapper.insert(subject);
            subject.setPath("/" + subject.getId() + "/");
            subjectMapper.updateById(subject);
        } else {
            Subject p = requireOwned(userId, parent);
            subject.setParentId(p.getId());
            subject.setLevel(p.getLevel() + 1);
            subject.setSubjectCode(nextCode(userId, type, p.getSubjectCode()));
            subject.setIsLeaf(true);
            subject.setBudgetable(type.isExpense());
            subject.setPath("/");
            subjectMapper.insert(subject);
            subject.setPath(p.getPath() + subject.getId() + "/");
            subjectMapper.updateById(subject);

            // 父节点不再可记账、不再可设预算
            p.setIsLeaf(false);
            p.setBudgetable(false);
            subjectMapper.updateById(p);
        }

        if (type.isAsset()) {
            AccountMeta meta = new AccountMeta();
            meta.setSubjectId(subject.getId());
            meta.setUserId(userId);
            meta.setAccountKind(accountKind == null ? "OTHER" : accountKind);
            meta.setOpeningBalance(BigDecimal.ZERO);
            meta.setBalance(BigDecimal.ZERO);
            meta.setIncludeInNetWorth(true);
            accountMetaMapper.insert(meta);
        }
        return subject;
    }

    /**
     * 删除科目。
     *
     * <p>受两条约束（FR-A-04）：
     * <ul>
     *   <li>有子科目 → 拒绝</li>
     *   <li><b>已被分录引用 → 拒绝</b>，只允许停用</li>
     * </ul>
     * 第二条是账务不可变原则的延伸：分录里引用的科目若被删掉，
     * 历史报表就会失去科目名称。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long subjectId) {
        Subject s = requireOwned(userId, subjectId);
        if (SubjectTemplateService.SYSTEM_RESERVED.equals(s.getRemark())) {
            throw BizException.of(ResultCode.SUBJECT_IN_USE, "系统保留科目不能删除");
        }
        Long children = subjectMapper.selectCount(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getParentId, subjectId));
        if (children != null && children > 0) {
            throw BizException.of(ResultCode.SUBJECT_HAS_CHILDREN);
        }
        Long used = entryMapper.selectCount(new LambdaQueryWrapper<Entry>()
                .eq(Entry::getSubjectId, subjectId));
        if (used != null && used > 0) {
            throw BizException.of(ResultCode.SUBJECT_IN_USE,
                    "该科目已被 " + used + " 条分录使用，只能停用不能删除");
        }

        // 资产科目连同扩展行一起逻辑删除
        if (s.isAssetSubject()) {
            AccountMeta meta = findAccountMeta(subjectId);
            if (meta != null) {
                accountMetaMapper.deleteById(meta.getId());
            }
        }
        subjectMapper.deleteById(subjectId);

        // 若父节点已无子科目，恢复为叶子
        if (s.getParentId() != null && s.getParentId() != 0L) {
            Subject parent = subjectMapper.selectById(s.getParentId());
            if (parent != null && parent.getUserId().equals(userId)) {
                Long remaining = subjectMapper.selectCount(new LambdaQueryWrapper<Subject>()
                        .eq(Subject::getParentId, parent.getId()));
                if (remaining == null || remaining == 0) {
                    parent.setIsLeaf(true);
                    parent.setBudgetable(parent.isExpenseSubject());
                    subjectMapper.updateById(parent);
                }
            }
        }
    }

    /** 启用/停用科目。停用后不出现在记账下拉框，但历史分录仍可查询。 */
    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long userId, Long subjectId, boolean enabled) {
        Subject s = requireOwned(userId, subjectId);
        s.setEnabled(enabled);
        subjectMapper.updateById(s);
    }

    // ---------------- 内部辅助 ----------------

    private List<Subject> listAll(Long userId) {
        return subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId)
                .orderByAsc(Subject::getSortOrder)
                .orderByAsc(Subject::getId));
    }

    private Map<Long, AccountMeta> accountMetaIndex(List<Subject> subjects) {
        List<Long> assetIds = subjects.stream()
                .filter(Subject::isAssetSubject)
                .map(Subject::getId)
                .toList();
        Map<Long, AccountMeta> map = new HashMap<>();
        if (assetIds.isEmpty()) {
            return map;
        }
        for (AccountMeta m : accountMetaMapper.selectList(new LambdaQueryWrapper<AccountMeta>()
                .in(AccountMeta::getSubjectId, assetIds))) {
            map.put(m.getSubjectId(), m);
        }
        return map;
    }

    private AccountMeta findAccountMeta(Long subjectId) {
        return accountMetaMapper.selectOne(new LambdaQueryWrapper<AccountMeta>()
                .eq(AccountMeta::getSubjectId, subjectId));
    }

    private Subject requireOwned(Long userId, Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.SUBJECT_NOT_FOUND);
        }
        return s;
    }

    /**
     * 生成下一个科目编码：父编码 + 2 位序号，序号取同级最大值 + 1。
     */
    private String nextCode(Long userId, SubjectType type, String parentCode) {
        String prefix = parentCode == null ? type.getCodePrefix() + "00" : parentCode;
        List<Subject> siblings = subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId)
                .likeRight(Subject::getSubjectCode, prefix));
        int max = 0;
        for (Subject s : siblings) {
            String code = s.getSubjectCode();
            if (code == null || !code.startsWith(prefix)) {
                continue;
            }
            String tail = code.substring(prefix.length());
            if (tail.length() == 2 && tail.chars().allMatch(Character::isDigit)) {
                max = Math.max(max, Integer.parseInt(tail));
            }
        }
        return prefix + String.format("%02d", max + 1);
    }

    /** 科目类型的字符串名，null 安全。 */
    private static String typeName(Subject s) {
        return s.getSubjectType() == null ? null : s.getSubjectType().name();
    }
}
