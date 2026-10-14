package com.example.ledger.module.budget.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.budget.entity.BudgetPeriod;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}
