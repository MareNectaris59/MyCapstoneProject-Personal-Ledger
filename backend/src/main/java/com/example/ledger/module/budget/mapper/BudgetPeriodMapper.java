package com.example.ledger.module.budget.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.budget.entity.BudgetPeriod;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;

@Mapper
public interface BudgetPeriodMapper extends BaseMapper<BudgetPeriod> {

    /**
     * 将预算周期标记为"待重算"。
     *
     * <p>记账/冲正事务提交后调用，<b>只置脏标记，不做增量扣减</b>。
     * 增量累加一旦某次失败或重复执行就会永久漂移，重算才永远正确
     * （《系统设计说明书》§7.5.4）。
     *
     * <p>{@code LEAST} 保证多次置脏时保留<b>最早</b>的日期，作为级联重算的起点。
     */
    @Update("UPDATE budget_period "
            + "SET dirty_since = LEAST(COALESCE(dirty_since, #{occurredDate}), #{occurredDate}) "
            + "WHERE budget_id = #{budgetId} "
            + "  AND #{occurredDate} BETWEEN start_date AND end_date")
    int markDirty(@Param("budgetId") Long budgetId, @Param("occurredDate") LocalDate occurredDate);

    /**
     * 统计某周期内的费用发生额（总预算口径）。
     *
     * <p>取所有 {@code EXPENSE} 类科目的 {@code SUM(signed_amount)}。
     * 因为借方为正、贷方为负，<b>冲正分录会自动以负值抵消</b>，
     * 不需要任何"排除已冲正"的过滤（《系统设计说明书》§7.3.3、
     * 以及 {@code Entry.signedAmount} 的注释）。
     *
     * <p>{@code COALESCE} 保证没有分录时返回 0 而不是 null。
     */
    @Select("SELECT COALESCE(SUM(e.signed_amount), 0) FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} "
            + "  AND s.subject_type = 'EXPENSE' "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate}")
    BigDecimal sumExpenseTotal(@Param("userId") Long userId,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate);

    /**
     * 统计某科目<b>及其全部后代</b>在周期内的费用发生额（分类预算口径）。
     *
     * <p>按物化路径匹配子树：{@code s.path LIKE '<父路径>%'}。
     * 路径形如 {@code /12/34/}（见 {@code Subject.path}），因此前缀匹配
     * 天然包含所有后代，无需递归查询。
     *
     * <p>同样按 {@code signed_amount} 求和，冲正自动抵消。
     */
    @Select("SELECT COALESCE(SUM(e.signed_amount), 0) FROM entry e "
            + "JOIN ledger l ON l.id = e.ledger_id "
            + "JOIN subject s ON s.id = e.subject_id "
            + "WHERE l.user_id = #{userId} "
            + "  AND s.subject_type = 'EXPENSE' "
            + "  AND s.path LIKE CONCAT(#{subjectPath}, '%') "
            + "  AND l.occurred_date BETWEEN #{startDate} AND #{endDate}")
    BigDecimal sumExpenseByPath(@Param("userId") Long userId,
                                @Param("subjectPath") String subjectPath,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);
}
