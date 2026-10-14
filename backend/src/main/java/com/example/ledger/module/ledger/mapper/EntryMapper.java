package com.example.ledger.module.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.ledger.entity.Entry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 分录 Mapper。复杂聚合用注解 SQL 显式写出，便于与设计文档逐条对照。
 */
@Mapper
public interface EntryMapper extends BaseMapper<Entry> {

    /**
     * 全局借贷平衡校验（《系统设计说明书》§7.3.2）。
     *
     * <p>{@code diff} 期望恒为 0.00。非 0 即系统级严重错误。
     *
     * @return 含 totalDebit / totalCredit / diff 三项的 Map
     */
    @Select("SELECT "
            + "  COALESCE(SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END), 0) AS totalDebit, "
            + "  COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0) AS totalCredit, "
            + "  COALESCE(SUM(signed_amount), 0) AS diff "
            + "FROM entry")
    Map<String, BigDecimal> globalBalanceCheck();

    /**
     * 账户余额与分录汇总的一致性校验（《系统设计说明书》§7.3.2）。
     *
     * <p>期望返回空列表。有结果说明 {@code account_meta.balance} 与流水不一致。
     */
    @Select("SELECT s.id AS subjectId, s.name AS subjectName, am.balance AS cachedBalance, "
            + "       COALESCE(SUM(e.signed_amount), 0) AS computedBalance, "
            + "       am.balance - COALESCE(SUM(e.signed_amount), 0) AS diff "
            + "FROM subject s "
            + "JOIN account_meta am ON am.subject_id = s.id "
            + "LEFT JOIN entry e ON e.subject_id = s.id "
            + "WHERE s.subject_type = 'ASSET' AND s.deleted = 0 "
            + "GROUP BY s.id, s.name, am.balance "
            + "HAVING diff <> 0")
    List<Map<String, Object>> accountBalanceDrift();

    /**
     * 单个科目的余额（由分录汇总得出，即"真相"）。
     */
    @Select("SELECT COALESCE(SUM(signed_amount), 0) FROM entry WHERE subject_id = #{subjectId}")
    BigDecimal sumSignedAmountBySubject(@Param("subjectId") Long subjectId);

    /**
     * 某科目及其全部子树在指定期间的借方净发生额（费用口径）。
     *
     * <p>冲正分录以相反方向存在，其 {@code signed_amount} 为负值，天然抵消，
     * 因此<b>无需任何"排除已冲正"的过滤</b>。
     *
     * @param subjectPath 物化路径前缀，如 {@code /5/12/}；传 null 表示全部科目
     */
    @Select("<script>"
            + "SELECT COALESCE(SUM(e.signed_amount), 0) "
            + "FROM entry e "
            + "JOIN ledger  l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate} "
            + "  AND s.subject_type = 'EXPENSE' "
            + "<if test='subjectPath != null'> AND s.path LIKE CONCAT(#{subjectPath}, '%') </if>"
            + "</script>")
    BigDecimal sumExpenseAmount(@Param("userId") Long userId,
                                @Param("startDate") String startDate,
                                @Param("endDate") String endDate,
                                @Param("subjectPath") String subjectPath);
}
