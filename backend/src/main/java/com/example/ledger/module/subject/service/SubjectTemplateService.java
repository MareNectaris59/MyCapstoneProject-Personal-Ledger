package com.example.ledger.module.subject.service;

import com.example.ledger.common.constant.Direction;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.module.subject.entity.AccountMeta;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.AccountMetaMapper;
import com.example.ledger.module.subject.mapper.SubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 预置科目模板（《数据库设计.md》§6）。
 *
 * <p>新用户注册时初始化整套科目树。层级规则为「一级 4 位编码，每下一级追加
 * 2 位序号」，与设计文档一致。
 *
 * <p><b>数量：</b>4 个一级容器（资产/权益/收入/费用）+ 35 个明细科目
 * = <b>39 条</b>。设计文档 §6 只列了明细科目共 35 条，加上容器才是树的总节点数，
 * {@link #templateSize()} 与 {@code initDefaultTree} 的返回值都按树节点计。
 *
 * <p>实现要点：
 * <ol>
 *   <li>先插一级科目，拿到 id 后写 {@code path = "/" + id + "/"}</li>
 *   <li>再插子科目：{@code path = parent.path + id + "/"}，同时把父节点
 *       {@code is_leaf} 置 0</li>
 *   <li><b>整个过程在同一事务内</b>，任一步失败全部回滚，
 *       避免出现"半个科目树"</li>
 * </ol>
 *
 * <p>编码仅用于展示与排序，不承载业务语义；层级关系只由
 * {@code parentId} / {@code path} / {@code level} 表达。
 */
@Service
public class SubjectTemplateService {

    private static final Logger log = LoggerFactory.getLogger(SubjectTemplateService.class);

    /** 系统保留标记，写在 {@code subject.remark} 上，接口层据此禁止删除改名 */
    public static final String SYSTEM_RESERVED = "SYSTEM_RESERVED";

    private final SubjectMapper subjectMapper;
    private final AccountMetaMapper accountMetaMapper;

    public SubjectTemplateService(SubjectMapper subjectMapper, AccountMetaMapper accountMetaMapper) {
        this.subjectMapper = subjectMapper;
        this.accountMetaMapper = accountMetaMapper;
    }

    /** 模板节点定义 */
    private record Node(String code, String name, SubjectType type, String accountKind,
                        String color, boolean budgetable, boolean reserved, List<Node> children) {

        static Node group(String code, String name, SubjectType type, List<Node> children) {
            return new Node(code, name, type, null, null, false, false, children);
        }

        static Node leaf(String code, String name, SubjectType type) {
            return new Node(code, name, type, null, null, type.isExpense(), false, List.of());
        }

        static Node account(String code, String name, String kind, String color) {
            return new Node(code, name, SubjectType.ASSET, kind, color, false, false, List.of());
        }

        static Node reservedLeaf(String code, String name, SubjectType type) {
            return new Node(code, name, type, null, null, false, true, List.of());
        }
    }

    /** 完整的预置模板：5 个一级科目 + 29 个明细科目 = 34 条 */
    private static List<Node> template() {
        return List.of(
                Node.group("1", "资产", SubjectType.ASSET, List.of(
                        Node.account("100101", "现金", "CASH", "#8D6E63"),
                        Node.account("100102", "银行卡", "BANK", "#42A5F5"),
                        Node.account("100103", "微信零钱", "WECHAT", "#66BB6A"),
                        Node.account("100104", "支付宝", "ALIPAY", "#29B6F6"),
                        Node.account("100105", "储蓄账户", "SAVING", "#AB47BC")
                )),
                Node.group("3", "权益", SubjectType.EQUITY, List.of(
                        // 期初建账的对方科目，系统保留
                        Node.reservedLeaf("300101", "期初余额", SubjectType.EQUITY)
                )),
                Node.group("4", "收入", SubjectType.INCOME, List.of(
                        Node.leaf("400101", "工资", SubjectType.INCOME),
                        Node.leaf("400102", "奖金", SubjectType.INCOME),
                        Node.leaf("400103", "利息", SubjectType.INCOME),
                        Node.leaf("400104", "其他收入", SubjectType.INCOME)
                )),
                Node.group("5", "费用", SubjectType.EXPENSE, List.of(
                        Node.group("500101", "餐饮", SubjectType.EXPENSE, List.of(
                                Node.leaf("50010101", "早餐", SubjectType.EXPENSE),
                                Node.leaf("50010102", "午餐", SubjectType.EXPENSE),
                                Node.leaf("50010103", "晚餐", SubjectType.EXPENSE),
                                Node.leaf("50010104", "零食饮料", SubjectType.EXPENSE)
                        )),
                        Node.group("500102", "交通", SubjectType.EXPENSE, List.of(
                                Node.leaf("50010201", "公共交通", SubjectType.EXPENSE),
                                Node.leaf("50010202", "打车", SubjectType.EXPENSE),
                                Node.leaf("50010203", "加油停车", SubjectType.EXPENSE)
                        )),
                        Node.group("500103", "居住", SubjectType.EXPENSE, List.of(
                                Node.leaf("50010301", "房租", SubjectType.EXPENSE),
                                Node.leaf("50010302", "水电燃气", SubjectType.EXPENSE),
                                Node.leaf("50010303", "物业网络", SubjectType.EXPENSE)
                        )),
                        Node.group("500104", "购物", SubjectType.EXPENSE, List.of(
                                Node.leaf("50010401", "服饰", SubjectType.EXPENSE),
                                Node.leaf("50010402", "日用品", SubjectType.EXPENSE),
                                Node.leaf("50010403", "数码电子", SubjectType.EXPENSE)
                        )),
                        Node.group("500105", "娱乐", SubjectType.EXPENSE, List.of(
                                Node.leaf("50010501", "旅行", SubjectType.EXPENSE),
                                Node.leaf("50010502", "游戏影音", SubjectType.EXPENSE),
                                Node.leaf("50010503", "运动健身", SubjectType.EXPENSE)
                        )),
                        Node.leaf("500106", "医疗健康", SubjectType.EXPENSE),
                        Node.leaf("500107", "教育学习", SubjectType.EXPENSE),
                        Node.leaf("500108", "人情往来", SubjectType.EXPENSE),
                        // 兜底科目：保证"不知道归哪类"的支出总有地方可记
                        Node.reservedLeaf("500109", "其他支出", SubjectType.EXPENSE)
                ))
        );
    }

    /**
     * 为用户初始化整套预置科目树。
     *
     * @return 创建的科目条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int initDefaultTree(Long userId) {
        List<Subject> all = new ArrayList<>();
        for (Node root : template()) {
            insertNode(userId, root, 0L, "/", 1, all);
        }
        log.debug("用户 {} 科目树初始化完成，共 {} 条", userId, all.size());
        return all.size();
    }

    /**
     * 递归插入节点。
     *
     * @param parentPath 父节点的物化路径（一级科目传 {@code "/"}）
     * @param out        收集所有已插入科目，供调用方统计与日志
     */
    private void insertNode(Long userId, Node node, Long parentId, String parentPath,
                            int level, List<Subject> out) {
        Subject subject = new Subject();
        subject.setUserId(userId);
        subject.setParentId(parentId);
        subject.setName(node.name());
        subject.setSubjectCode(node.code());
        subject.setSubjectType(node.type());
        // normal_side 由 subject_type 决定，冗余存储便于 SQL 层按方向聚合
        subject.setNormalSide(node.type().getNormalSide());
        subject.setLevel(level);
        subject.setPath("/");  // 占位，插入拿到 id 后立刻回填
        subject.setIsLeaf(true);
        subject.setBudgetable(node.budgetable());
        subject.setColor(node.color());
        subject.setSortOrder(0);
        subject.setEnabled(true);
        subject.setDeleted(0);
        if (node.reserved()) {
            subject.setRemark(SYSTEM_RESERVED);
        }
        subjectMapper.insert(subject);

        // 回填物化路径：/父路径/自身id/
        String path = parentPath + subject.getId() + "/";
        subject.setPath(path);
        subjectMapper.updateById(subject);
        out.add(subject);

        if (node.children().isEmpty()) {
            // 资产类科目需要 account_meta 扩展行；没有它余额更新会因影响行数为 0 而失败
            if (node.type().isAsset()) {
                createAccountMeta(userId, subject, node.accountKind());
            }
            return;
        }

        // 有子科目：自己变成非叶子，且不可记账、不可设预算
        subject.setIsLeaf(false);
        subject.setBudgetable(false);
        subjectMapper.updateById(subject);

        int childLevel = level + 1;
        for (Node child : node.children()) {
            insertNode(userId, child, subject.getId(), path, childLevel, out);
        }
    }

    private void createAccountMeta(Long userId, Subject subject, String accountKind) {
        AccountMeta meta = new AccountMeta();
        meta.setSubjectId(subject.getId());
        meta.setUserId(userId);
        meta.setAccountKind(accountKind == null ? "OTHER" : accountKind);
        meta.setOpeningBalance(BigDecimal.ZERO);
        meta.setBalance(BigDecimal.ZERO);
        meta.setIncludeInNetWorth(true);
        accountMetaMapper.insert(meta);
    }

    /** 供测试与文档引用：模板条数（应为 34）。 */
    public static int templateSize() {
        int[] count = {0};
        for (Node root : template()) {
            countNode(root, count);
        }
        return count[0];
    }

    private static void countNode(Node node, int[] count) {
        count[0]++;
        for (Node child : node.children()) {
            countNode(child, count);
        }
    }
}
