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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // 新增科目若未指定父级，挂到该类型的根节点下（新增账户 → 挂在「资产」下）。
        // 不能让它成为根的兄弟：那样它在科目树里会以一级节点身份出现，
        // 编码也会脱离「根编码 + 4 位序号」的规则。
        Subject typeRoot = findTypeRoot(userId, type);
        if (typeRoot == null) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "该类型的根科目不存在，无法新增科目");
        }

        Long parent = parentId == null ? typeRoot.getId() : parentId;
        if (parent.equals(typeRoot.getId())) {
            subject.setParentId(typeRoot.getId());
            subject.setLevel(typeRoot.getLevel() + 1);
            subject.setSubjectCode(nextCode(userId, type, typeRoot));
            subject.setIsLeaf(true);
            subject.setBudgetable(type.isExpense());
            subject.setPath("/");
            subjectMapper.insert(subject);
            subject.setPath(typeRoot.getPath() + subject.getId() + "/");
            subjectMapper.updateById(subject);
        } else {
            Subject p = requireOwned(userId, parent);
            subject.setParentId(p.getId());
            subject.setLevel(p.getLevel() + 1);
            subject.setSubjectCode(nextCode(userId, type, p));
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
     * 生成下一个科目编码。
     *
     * <p><b>编码结构</b>（与《数据库设计.md》§6 的预置模板一致）：
     * <pre>
     *   根（level 1）       "1" 资产、"5" 费用        1 位，纯展示段
     *   level 2            100101 现金 ～ 100105 储蓄  6 位
     *                      500101 餐饮 ～ 500109 其他
     *   level 3            50010101 早餐              8 位 = 父编码 + 2 位
     * </pre>
     *
     * <p><b>规则：末 2 位是序号，前面所有位是分组段（prefix）。</b>
     *
     * <p><b>本方法的历史坑（务必不要再犯）</b>：根编码只有 1 位，子科目是 6 位，
     * 两者长度<b>不成固定关系</b>。早先几版都试图用长度公式推导，全部失败：
     * <ul>
     *   <li>{@code rootCode.length() + 4} = 5，而真实子科目是 6 位 →
     *       所有候选都被长度判断排除，prefix 取不到，回落到 "0100"，
     *       产出 {@code "1"+"0100"+"01" = 10101}</li>
     *   <li>{@code Integer.parseInt(rootCode + "0100")} = 10100（少一位），
     *       而正确基数是 100100</li>
     *   <li>取"第一个遇到的"而非最小分组段 → 被历史脏数据带偏</li>
     * </ul>
     * 结论：<b>不要计算长度，用"末 2 位是序号"这一条结构规则。</b>
     */
    private String nextCode(Long userId, SubjectType type, Subject parent) {
        String parentCode = parent.getSubjectCode();
        if (parentCode == null || parentCode.isEmpty()) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "父科目的编码为空，无法自动生成子科目编码");
        }

        // 已占用编码集合。
        //
        // **必须包含已逻辑删除的行。** subject 上的唯一索引 uk_user_code
        // (user_id, subject_code) 不区分 deleted，逻辑删除后编码仍占用索引位。
        // 若只统计未删除的行，就会算出与已删除行重复的编码，插入时撞唯一索引，
        // 用户看到"系统开小差"。
        // 实测场景：新建账户 → 删除该账户 → 再新建账户，第二次必然失败。
        // 因此这里用显式 SQL 绕过 @TableLogic 的自动过滤。
        Set<String> taken = new HashSet<>();
        for (String code : subjectMapper.selectAllCodesIncludingDeleted(userId, type)) {
            if (code != null) {
                taken.add(code);
            }
        }

        String prefix;
        if (parent.getLevel() != null && parent.getLevel() == 1) {
            // level 2：prefix = 某个既有同级子科目前缀（去掉末 2 位）；
            // 一个都没有时用根编码 + "0100"，即首个编码为 <root>0100 01。
            prefix = null;
            for (String code : subjectMapper.selectChildCodesIncludingDeleted(userId, parent.getId())) {
                if (code != null && code.length() > 2) {
                    String head = code.substring(0, code.length() - 2);
                    // 取最小前缀，避开脏数据（如孤儿 1010001 的前缀 1010）
                    if (prefix == null || head.compareTo(prefix) < 0) {
                        prefix = head;
                    }
                }
            }
            if (prefix == null) {
                prefix = parentCode + "0100";
            }
        } else {
            // level 3+：prefix 就是父编码
            prefix = parentCode;
        }

        // 求同前缀兄弟里的最大序号
        int max = 0;
        for (String code : taken) {
            if (code.length() != prefix.length() + 2 || !code.startsWith(prefix)) {
                continue;
            }
            String seq = code.substring(prefix.length());
            if (isAllDigits(seq)) {
                max = Math.max(max, Integer.parseInt(seq));
            }
        }

        // 逐位向后找空位。序号溢出 2 位时，把末 2 位并进前缀继续进位，
        // 编码始终是连续十进制数，不会与既有值重复。
        String candidate = prefix + String.format("%02d", max + 1);
        int guard = 0;
        while (taken.contains(candidate) && guard++ < 1000) {
            max++;
            candidate = String.valueOf(Long.parseLong(prefix) * 100 + (max + 1));
        }

        log.debug("生成科目编码 type={} parent={} level={} prefix={} -> {}",
                type, parentCode, parent.getLevel(), prefix, candidate);
        return candidate;
    }

    /**
     * 取得某类型的根科目（{@code parent_id = 0} 且 {@code level = 1}）。
     *
     * <p>新增科目若未指定 {@code parentId}，必须挂到根下面；否则它会成为根的
     * <b>兄弟</b>而不是子科目 —— 在科目树里以一级节点身份出现，
     * 编码也会脱离 {@code 根编码 + 分组段 + 序号} 的规则。
     */
    private Subject findTypeRoot(Long userId, SubjectType type) {
        return subjectMapper.selectOne(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId)
                .eq(Subject::getSubjectType, type)
                .eq(Subject::getParentId, 0L)
                .eq(Subject::getLevel, 1)
                .last("LIMIT 1"));
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** 科目类型的字符串名，null 安全。 */
    private static String typeName(Subject s) {
        return s.getSubjectType() == null ? null : s.getSubjectType().name();
    }
}
