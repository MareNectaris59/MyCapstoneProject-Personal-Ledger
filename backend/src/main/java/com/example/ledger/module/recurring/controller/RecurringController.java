package com.example.ledger.module.recurring.controller;

import com.example.ledger.common.result.Result;
import com.example.ledger.module.recurring.dto.RecurringDtos;
import com.example.ledger.module.recurring.service.RecurringService;
import com.example.ledger.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 定期记账接口。
 *
 * <p>所有操作都限定在当前登录用户内 —— 规则 ID 是自增的，若不做归属校验，
 * 猜一个 ID 就能改别人的规则。
 */
@Tag(name = "定期记账", description = "房租、宽带、会员等固定支出的自动记账")
@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private final RecurringService recurringService;

    public RecurringController(RecurringService recurringService) {
        this.recurringService = recurringService;
    }

    @Operation(summary = "列出全部定期记账规则")
    @GetMapping
    public Result<List<RecurringDtos.View>> list(@AuthenticationPrincipal CurrentUser user) {
        return Result.success(recurringService.list(user.userId()));
    }

    @Operation(summary = "新建定期记账规则")
    @PostMapping
    public Result<RecurringDtos.View> create(@AuthenticationPrincipal CurrentUser user,
                                             @Valid @RequestBody RecurringDtos.Create req) {
        return Result.success(recurringService.create(user.userId(), req));
    }

    @Operation(summary = "修改规则（金额 / 备注 / 启用 / 结束日）")
    @PutMapping("/{id}")
    public Result<RecurringDtos.View> update(@AuthenticationPrincipal CurrentUser user,
                                             @PathVariable Long id,
                                             @Valid @RequestBody RecurringDtos.Update req) {
        return Result.success(recurringService.update(user.userId(), id, req));
    }

    @Operation(summary = "删除规则（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal CurrentUser user,
                               @PathVariable Long id) {
        recurringService.delete(user.userId(), id);
        return Result.success(null);
    }

    /**
     * 立即执行所有到期规则。
     *
     * <p>存在的意义有两个：一是让用户不必等到凌晨就能看到效果；
     * 二是让"到期判定 + 幂等 + 补跑"这段逻辑可以被端到端测试直接验证，
     * 而不必依赖系统时间。
     */
    @Operation(summary = "立即执行到期的规则",
            description = "返回新增 / 跳过 / 推进了多少条；重复调用不会重复记账")
    @PostMapping("/run")
    public Result<RecurringDtos.RunResult> runNow(@AuthenticationPrincipal CurrentUser user) {
        return Result.success(recurringService.runDueForUser(user.userId()));
    }
}
