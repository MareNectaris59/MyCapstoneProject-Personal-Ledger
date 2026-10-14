package com.example.ledger.module.subject.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.ledger.common.constant.Direction;
import com.example.ledger.common.constant.SubjectType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会计科目（{@code subject} 表）。
 *
 * <p><b>这是整个系统最重要的一张表。</b>资金账户与消费分类都是它的行，
 * 只是 {@code subjectType} 不同。资产、收入、费用必须共处一张表，
 * 否则一笔支出「借 餐饮 / 贷 支付宝」的借方将无处安放，
 * 借贷平衡校验也就失去意义（《系统设计说明书》§5.1.1）。
 *
 * <p>层级由三个字段共同表达：
 * <ul>
 *   <li>{@code parentId} —— 唯一权威来源，0 表示一级科目</li>
 *   <li>{@code path} —— 物化路径 {@code /1/12/125/}（含自身 id，前后均带 {@code /}），
 *       子树查询 {@code path LIKE '/1/12/%'}，且不会误匹配 {@code /1/120/}</li>
 *   <li>{@code level} —— 层级，从 1 开始，冗余存储便于聚合</li>
 * </ul>
 */
@Data
@TableName("subject")
public class Subject {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户，每个用户一棵独立科目树 */
    private Long userId;

    /** 父科目 ID，0 表示一级科目 */
    private Long parentId;

    /** 科目名称 */
    private String name;

    /** 科目编码，同用户内唯一。仅用于展示与排序，不承载业务语义 */
    private String subjectCode;

    /** 科目类型：ASSET / LIABILITY / EQUITY / INCOME / EXPENSE */
    private SubjectType subjectType;

    /** 正常余额方向：由 subjectType 决定，冗余存储 */
    private Direction normalSide;

    /** 层级，从 1 开始 */
    private Integer level;

    /** 物化路径，如 /1/12/125/ */
    private String path;

    /** 是否叶子科目。只有叶子可记账（BR-10） */
    private Boolean isLeaf;

    /** 是否可作为预算范围，仅费用类为 true */
    private Boolean budgetable;

    private String icon;

    private String color;

    private Integer sortOrder;

    /** 是否启用。停用后不出现在记账下拉框，但历史分录仍可查询 */
    private Boolean enabled;

    private String remark;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    // ---------- 派生判断 ----------

    /** 是否为资产类（资金账户）。 */
    public boolean isAssetSubject() {
        return subjectType != null && subjectType.isAsset();
    }

    /** 是否为费用类科目。 */
    public boolean isExpenseSubject() {
        return subjectType != null && subjectType.isExpense();
    }
}
