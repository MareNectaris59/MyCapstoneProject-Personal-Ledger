package com.example.ledger.module.analysis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 消费分析聚合查询（《系统设计说明书》§7.6）。
 *
 * <p><b>所有查询都按 {@code signed_amount} 求和。</b>费用科目借方为正、贷方为负，
 * 因此冲正分录会自动以负值抵消，统计口径无需任何"排除已冲正"的过滤
 * （§7.3.3）。这是 {@code Entry.signedAmount} 这个冗余字段存在的全部意义。
 *
 * <p>返回 {@code Map} 而不是自定义 DTO：这些是即席聚合结果，字段随查询而变，
 * 为每种形状定义一个 record 只会增加噪音。金额一律由调用方转为字符串输出。
 */
@Mapper
public interface AnalysisMapper {

    /**
     * 期间总收入（收入类科目 signed_amount 之和；收入正常在贷方，故取反得到正数）。
     */
    @Select("SELECT COALESCE(-SUM(e.signed_amount), 0) FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} AND s.subject_type = 'INCOME' "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate}")
    BigDecimal sumIncome(@Param("userId") Long userId,
                         @Param("startDate") LocalDate startDate,
                         @Param("endDate") LocalDate endDate);

    /**
     * 期间总支出（费用类科目 signed_amount 之和；费用正常在借方，本就为正）。
     */
    @Select("SELECT COALESCE(SUM(e.signed_amount), 0) FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} AND s.subject_type = 'EXPENSE' "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate}")
    BigDecimal sumExpense(@Param("userId") Long userId,
                          @Param("startDate") LocalDate startDate,
                          @Param("endDate") LocalDate endDate);

    /**
     * 净资产 = 资产 + 负债 + 权益 的 signed_amount 合计。
     *
     * <p>会计恒等式：资产 = 负债 + 权益，而权益包含当期损益，
     * 因此"资产 − 负债"就等于净资产。这里直接用三类科目 signed_amount 求和：
     * 资产借方为正，负债与权益贷方为负，三者相加即得净资产。
     *
     * <p>不计入收入与费用科目 —— 它们的净额已经通过权益（期初余额）体现，
     * 重复计入会双倍计算。
     */
    @Select("SELECT COALESCE(SUM(e.signed_amount), 0) FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} "
            + "  AND s.subject_type IN ('ASSET', 'LIABILITY', 'EQUITY') "
            + "  AND l.occurred_date <= #{asOfDate}")
    BigDecimal netWorth(@Param("userId") Long userId,
                        @Param("asOfDate") LocalDate asOfDate);

    /**
     * 通用期间聚合：按 {@code DATE_FORMAT(occurred_date, format)} 分组求费用额。
     *
     * <p>{@code dateFormat} 由调用方给出（如 {@code %Y-%m}、{@code %Y-%m-%d}），
     * <b>不能用参数拼接给用户</b> —— 它是服务端常量，不来自请求。
     *
     * <p>{@code subjectPath} 为 null 时统计全部费用科目；给出时按物化路径
     * 匹配子树（{@code path LIKE '<path>%'}），天然包含所有后代，无需递归。
     */
    @Select("SELECT DATE_FORMAT(l.occurred_date, #{dateFormat}) AS period_label, "
            + "       COALESCE(SUM(e.signed_amount), 0) AS amount "
            + "FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} AND s.subject_type = 'EXPENSE' "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate} "
            + "  AND (#{subjectPath} IS NULL OR s.path LIKE CONCAT(#{subjectPath}, '%')) "
            + "GROUP BY period_label "
            + "ORDER BY period_label")
    List<Map<String, Object>> sumExpenseByPeriod(@Param("userId") Long userId,
                                                 @Param("dateFormat") String dateFormat,
                                                 @Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate,
                                                 @Param("subjectPath") String subjectPath);

    /**
     * 期间内每个<b>叶子科目</b>的费用额（逐科目明细，不聚合层级）。
     *
     * <p>层级聚合刻意放在 Java 侧做，而不是用 SQL 的 {@code SUBSTRING_INDEX + LIKE}
     * 拼路径：路径形如 {@code /12/34/}，用 {@code LIKE} 匹配前缀时
     * {@code /10/} 会误匹配 {@code /100/}，而按层级截取字符串又依赖
     * 路径段数与 level 严格对应。用 {@code Subject.path} 在 Java 里精确取祖先
     * 既简单又不会错，代价只是一次内存归集 —— 个人记账的数据量完全可接受。
     *
     * <p>冲正自动抵消（按 {@code signed_amount} 求和）。
     */
    @Select("SELECT s.id AS subject_id, COALESCE(SUM(e.signed_amount), 0) AS amount "
            + "FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} AND s.subject_type = 'EXPENSE' "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate} "
            + "GROUP BY s.id "
            + "ORDER BY amount DESC")
    List<Map<String, Object>> sumExpenseByLeafSubject(@Param("userId") Long userId,
                                                      @Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate);

    /**
     * 期间窗口内最后一次有记账的日期，用于判断"当前周期是否已结束"。
     *
     * <p>对齐窗口（§7.6.3）需要知道当前周期实际过了多久。返回 null 表示期间内无数据。
     */
    @Select("SELECT MAX(l.occurred_date) FROM ledger l "
            + "WHERE l.user_id = #{userId} "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate}")
    LocalDate lastActivityDate(@Param("userId") Long userId,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate);

    /**
     * <b>一次查询</b>取出两个期间的逐叶子科目费用额，用于贡献度分析。
     *
     * <p>为什么用一条 SQL 而不是调两次 {@link #sumExpenseByLeafSubject}：
     * 贡献度必须把"本期"与"基期"的同一批科目对齐比较，两次独立查询在
     * 两次查询之间若有写入会读到不一致的快照；而且合并成一条也能省一次往返。
     *
     * <p>返回行含 {@code period_tag}（{@code CURRENT} / {@code BASE}）用于区分，
     * {@code amount} 为该科目在该期间的净发生额。某科目只在一个期间出现时，
     * 另一个期间不会有行 —— 调用方须把缺失视为 0（这正是"新增科目"的情形）。
     */
    @Select("SELECT s.id AS subject_id, "
            + "       CASE WHEN l.occurred_date >= #{curStart} AND l.occurred_date <= #{curEnd} "
            + "            THEN 'CURRENT' ELSE 'BASE' END AS period_tag, "
            + "       COALESCE(SUM(e.signed_amount), 0) AS amount "
            + "FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} AND s.subject_type = 'EXPENSE' "
            + "  AND ( (l.occurred_date >= #{curStart} AND l.occurred_date <= #{curEnd}) "
            + "     OR (l.occurred_date >= #{baseStart} AND l.occurred_date <= #{baseEnd}) ) "
            + "GROUP BY s.id, period_tag")
    List<Map<String, Object>> sumExpenseBySubjectBothPeriods(
            @Param("userId") Long userId,
            @Param("curStart") LocalDate curStart,
            @Param("curEnd") LocalDate curEnd,
            @Param("baseStart") LocalDate baseStart,
            @Param("baseEnd") LocalDate baseEnd);
}
