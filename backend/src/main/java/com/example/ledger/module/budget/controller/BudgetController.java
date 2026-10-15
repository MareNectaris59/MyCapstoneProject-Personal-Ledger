package com.example.ledger.module.budget.controller;

import com.example.ledger.common.result.Result;
import com.example.ledger.module.budget.dto.BudgetDtos;
import com.example.ledger.module.budget.service.BudgetService;
import com.example.ledger.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预算接口（《系统设计说明书》§6.2.5）。
 *
 * <p>路径参数统一加 {@code \\d+} 约束。这不是装饰：不加约束时
 * {@code /budgets/alerts} 会被 {@code /budgets/{id}} 抢先匹配，
 * 导致 "alerts" 被尝试解析成 Long 而失败。项目早期在科目接口上踩过同样的坑
 * （{@code /subjects/balance-check} 被 {@code /subjects/{id}} 吞掉）。
 *
 * <p>所有接口都以 {@code @AuthenticationPrincipal} 取当前用户，
 * 再在 Service 层用 {@code requireOwned} 校验归属，避免越权读取他人预算。
 */
@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /**
     * 预算列表。
     *
     * <p>必须在 {@code /{id}} 之前声明才对，但因为路径不同（无 id 段）
     * Spring 不会混淆；这里保留在首位是为了可读性。
     */
    @GetMapping
    public Result<List<BudgetDtos.BudgetView>> list(@AuthenticationPrincipal CurrentUser user) {
        return Result.success(budgetService.list(user.userId()));
    }

    /** 新建预算。 */
    @PostMapping
    public Result<BudgetDtos.BudgetView> create(@AuthenticationPrincipal CurrentUser user,
                                                @RequestBody BudgetDtos.CreateRequest req) {
        return Result.success(budgetService.create(user.userId(), req));
    }

    /**
     * 未读预警列表。
     *
     * <p>路径是 {@code /alerts} 而不是 {@code /{id}/alerts}：预警是跨预算的，
     * 前端"预警中心"需要一次拿到全部。{@code \\d+} 约束保证它不会被
     * {@code /{id}} 吞掉。
     */
    @GetMapping("/alerts")
    public Result<List<BudgetDtos.BudgetAlertView>> alerts(@AuthenticationPrincipal CurrentUser user) {
        return Result.success(budgetService.alerts(user.userId(), null));
    }

    /** 标记预警已读。 */
    @PutMapping("/alerts/{id:\\d+}/read")
    public Result<Void> markAlertRead(@AuthenticationPrincipal CurrentUser user,
                                      @PathVariable Long id) {
        budgetService.markAlertRead(user.userId(), id);
        return Result.success();
    }

    /** 预算详情（含全部周期，供趋势图）。 */
    @GetMapping("/{id:\\d+}")
    public Result<BudgetDtos.BudgetDetail> detail(@AuthenticationPrincipal CurrentUser user,
                                                  @PathVariable Long id) {
        return Result.success(budgetService.detail(user.userId(), id));
    }

    /** 修改额度 / 阈值 / 结转模式 / 启用状态 / 名称。 */
    @PutMapping("/{id:\\d+}")
    public Result<BudgetDtos.BudgetView> update(@AuthenticationPrincipal CurrentUser user,
                                                @PathVariable Long id,
                                                @RequestBody BudgetDtos.UpdateRequest req) {
        return Result.success(budgetService.update(user.userId(), id, req));
    }

    /**
     * 删除预算。
     *
     * <p>逻辑删除预算定义，<b>保留历史周期行</b> —— 删掉就无法回溯
     * "当时预算执行得如何"。
     */
    @DeleteMapping("/{id:\\d+}")
    public Result<Void> delete(@AuthenticationPrincipal CurrentUser user,
                               @PathVariable Long id) {
        budgetService.delete(user.userId(), id);
        return Result.success();
    }

    /** 该预算的周期列表。 */
    @GetMapping("/{id:\\d+}/periods")
    public Result<List<BudgetDtos.PeriodView>> periods(@AuthenticationPrincipal CurrentUser user,
                                                       @PathVariable Long id) {
        return Result.success(budgetService.periods(user.userId(), id));
    }

    /** 当前周期执行情况 + 趋势序列 + 相关预警。 */
    @GetMapping("/{id:\\d+}/execution")
    public Result<BudgetDtos.ExecutionView> execution(@AuthenticationPrincipal CurrentUser user,
                                                      @PathVariable Long id) {
        return Result.success(budgetService.execution(user.userId(), id));
    }
}
