package com.example.ledger.module.ledger.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.ledger.common.constant.LedgerType;
import com.example.ledger.common.result.Result;
import com.example.ledger.common.util.PageResult;
import com.example.ledger.module.ledger.dto.LedgerDetail;
import com.example.ledger.module.ledger.dto.LedgerRequests;
import com.example.ledger.module.ledger.entity.Ledger;
import com.example.ledger.module.ledger.service.LedgerCoreService;
import com.example.ledger.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 账务接口（《系统设计说明书》§6.2.4）。
 *
 * <p>注意这里<b>没有 DELETE 接口</b>：物理删除在架构上被禁止，
 * 纠错只能通过冲正。这不是遗漏，是设计。
 */
@RestController
@RequestMapping("/api/v1/ledgers")
public class LedgerController {

    private final LedgerCoreService ledgerCoreService;

    public LedgerController(LedgerCoreService ledgerCoreService) {
        this.ledgerCoreService = ledgerCoreService;
    }

    /** 记一笔支出 */
    @PostMapping("/expense")
    public Result<LedgerDetail> expense(@AuthenticationPrincipal CurrentUser user,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @Valid @RequestBody LedgerRequests.Expense req) {
        return Result.success(ledgerCoreService.postExpense(user.userId(),
                req.withIdempotencyKey(idempotencyKey)));
    }

    /** 记一笔收入 */
    @PostMapping("/income")
    public Result<LedgerDetail> income(@AuthenticationPrincipal CurrentUser user,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @Valid @RequestBody LedgerRequests.Income req) {
        return Result.success(ledgerCoreService.postIncome(user.userId(),
                req.withIdempotencyKey(idempotencyKey)));
    }

    /** 记一笔转账 */
    @PostMapping("/transfer")
    public Result<LedgerDetail> transfer(@AuthenticationPrincipal CurrentUser user,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @Valid @RequestBody LedgerRequests.Transfer req) {
        return Result.success(ledgerCoreService.postTransfer(user.userId(),
                req.withIdempotencyKey(idempotencyKey)));
    }

    /** 账目分页查询 */
    @GetMapping
    public Result<PageResult<Ledger>> page(@AuthenticationPrincipal CurrentUser user,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) LocalDate startDate,
                                          @RequestParam(required = false) LocalDate endDate,
                                          @RequestParam(required = false) LedgerType ledgerType) {
        IPage<Ledger> result = ledgerCoreService.page(user.userId(), page, size, startDate, endDate, ledgerType);
        return Result.success(PageResult.of(result));
    }

    /** 凭证详情（含借贷两条分录） */
    @GetMapping("/{id:\\d+}")
    public Result<LedgerDetail> detail(@AuthenticationPrincipal CurrentUser user,
                                      @PathVariable Long id) {
        return Result.success(ledgerCoreService.getDetail(user.userId(), id));
    }

    /**
     * 冲正。body 中 amount 为 null 时表示全额冲正。
     */
    @PostMapping("/{id:\\d+}/reverse")
    public Result<LedgerDetail> reverse(@AuthenticationPrincipal CurrentUser user,
                                       @PathVariable Long id,
                                       @RequestBody(required = false) LedgerRequests.Reverse req) {
        LedgerRequests.Reverse body = req == null ? new LedgerRequests.Reverse(null, null) : req;
        return Result.success(ledgerCoreService.reverse(user.userId(), id, body.amount(), body.reason()));
    }

    /** 借贷平衡全局校验（开发自检用） */
    @GetMapping("/balance-check")
    public Result<Map<String, Object>> balanceCheck(@AuthenticationPrincipal CurrentUser user) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        Map<String, java.math.BigDecimal> global = ledgerCoreService.globalBalanceCheck();
        List<Map<String, Object>> drift = ledgerCoreService.accountBalanceDrift();
        result.put("globalBalance", global);
        result.put("globalBalanced", global.get("diff") == null
                || global.get("diff").compareTo(java.math.BigDecimal.ZERO) == 0);
        result.put("accountDrift", drift);
        result.put("accountBalanced", drift.isEmpty());
        return Result.success(result);
    }
}
