package com.example.ledger.module.budget.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ledger.module.budget.entity.Budget;
import com.example.ledger.module.budget.mapper.BudgetMapper;
import com.example.ledger.module.budget.mapper.BudgetPeriodMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 预算置脏标记器 —— 记账内核与预算服务之间的<b>单向桥</b>。
 *
 * <p><b>为什么单独成一个类？</b>
 * 记账成功后需要把受影响的预算周期标为待重算，但"哪些预算受影响"的规则
 * 属于预算模块的知识。若把这个逻辑留在 {@code LedgerCoreService}，
 * 预算模块就永远无法在不产生循环依赖的前提下复用记账能力
 * （账务 → 预算 → 账务）。
 *
 * <p>本类<b>只依赖预算自己的两个 Mapper</b>，不依赖任何账务服务，
 * 因此可以被 {@code LedgerCoreService} 与 {@code BudgetService} 同时注入，
 * 依赖图保持为有向无环。
 *
 * <p>语义上它只做一件事：把落在某预算某周期内的那些周期行标脏
 * （{@code dirty_since} 取最早日期）。<b>不做增量扣减</b> ——
 * 增量累加一旦某次失败或重复执行就会永久漂移，由 {@code entry} 重算才永远正确
 * （《系统设计说明书》§7.5.4）。
 */
@Service
public class BudgetDirtyMarker {

    private final BudgetMapper budgetMapper;
    private final BudgetPeriodMapper periodMapper;

    public BudgetDirtyMarker(BudgetMapper budgetMapper, BudgetPeriodMapper periodMapper) {
        this.budgetMapper = budgetMapper;
        this.periodMapper = periodMapper;
    }

    /**
     * 把 {@code occurredDate} 落在范围内的预算周期标为待重算。
     *
     * <p>两种触发时机都应调用：
     * <ul>
     *   <li>新记一笔账（可能落在当前周期，也可能补记到历史周期）</li>
     *   <li>撤销一笔账（冲正单的日期可能与原单不同）</li>
     * </ul>
     *
     * @return 受影响的预算数，便于日志与调试
     */
    public int markDirty(Long userId, LocalDate occurredDate) {
        if (occurredDate == null) {
            return 0;
        }
        List<Budget> budgets = budgetMapper.selectList(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .eq(Budget::getEnabled, true)
                .le(Budget::getStartDate, occurredDate));

        int affected = 0;
        for (Budget b : budgets) {
            if (b.getEndDate() != null && b.getEndDate().isBefore(occurredDate)) {
                continue;
            }
            affected += periodMapper.markDirty(b.getId(), occurredDate);
        }
        return affected;
    }
}
