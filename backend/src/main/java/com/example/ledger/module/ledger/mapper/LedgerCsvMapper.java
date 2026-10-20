package com.example.ledger.module.ledger.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 台账 CSV 导出/导入用的查询。
 *
 * <p>与 {@code LedgerMapper} 分开，是因为这里的查询是<b>为导出服务</b>的：
 * 它一次 join 出「凭证 + 分录 + 科目编码」，按凭证分组后直接翻译成 CSV 行，
 * 不参与任何业务写入。放在 LedgerMapper 里会让那个接口承担两种职责。
 */
@Mapper
public interface LedgerCsvMapper {

    /**
     * 取指定期间的业务凭证及其分录科目编码。
     *
     * <p>三个刻意的过滤条件：
     * <ul>
     *   <li>只要 EXPENSE / INCOME / TRANSFER —— 期初余额属于"账户初始化"、
     *       冲正属于"审计修正"，都不是可复制粘贴的交易，所以不进 CSV</li>
     *   <li>只要 state = 'POSTED' —— 已被冲正的记录代表「已撤销的业务」，
     *       导出它们会导致再导入时把撤销过的账又记回来</li>
     *   <li>按 entry_no 排序，保证同一凭证的分录相邻，便于按凭证分组</li>
     * </ul>
     *
     * @param startDate 可空，为空表示不限起始
     * @param endDate   可空，为空表示不限结束
     */
    @Select("SELECT l.id           AS ledger_id, "
            + "       l.occurred_date AS occurred_date, "
            + "       l.ledger_type  AS ledger_type, "
            + "       l.amount       AS amount, "
            + "       l.remark       AS remark, "
            + "       e.direction    AS direction, "
            + "       s.subject_code AS subject_code "
            + "FROM ledger l "
            + "JOIN entry e ON e.ledger_id = l.id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} "
            + "  AND l.ledger_type IN ('EXPENSE', 'INCOME', 'TRANSFER') "
            + "  AND l.state = 'POSTED' "
            + "  AND (#{startDate} IS NULL OR l.occurred_date >= #{startDate}) "
            + "  AND (#{endDate} IS NULL OR l.occurred_date <= #{endDate}) "
            + "ORDER BY l.occurred_date, l.id, e.entry_no")
    List<Map<String, Object>> selectExportRows(@Param("userId") Long userId,
                                               @Param("startDate") LocalDate startDate,
                                               @Param("endDate") LocalDate endDate);

    /**
     * 某个幂等键是否已存在。
     *
     * <p>导入时用它在写入前判断"这一行是不是已经导过了"，
     * 从而把重复导入计成 skipped 而不是报错。
     */
    @Select("SELECT COUNT(*) FROM ledger WHERE user_id = #{userId} AND idempotency_key = #{key}")
    int countByKey(@Param("userId") Long userId, @Param("key") String key);
}
