package com.example.ledger.module.ledger.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.ledger.common.constant.LedgerType;
import com.example.ledger.common.result.Result;
import com.example.ledger.common.util.PageResult;
import com.example.ledger.module.ledger.dto.LedgerDetail;
import com.example.ledger.module.ledger.dto.LedgerRequests;
import com.example.ledger.module.ledger.entity.Ledger;
import com.example.ledger.module.ledger.service.LedgerCoreService;
import com.example.ledger.module.ledger.service.LedgerCsvService;
import com.example.ledger.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 账务接口（《系统设计说明书》§6.2.4）。
 *
 * <p>注意这里<b>没有 DELETE 接口</b>：物理删除在架构上被禁止，
 * 纠错只能通过冲正。这不是遗漏，是设计。
 */
@Validated
@RestController
@RequestMapping("/api/v1/ledgers")
public class LedgerController {

    private final LedgerCoreService ledgerCoreService;
    private final LedgerCsvService ledgerCsvService;

    public LedgerController(LedgerCoreService ledgerCoreService,
                            LedgerCsvService ledgerCsvService) {
        this.ledgerCoreService = ledgerCoreService;
        this.ledgerCsvService = ledgerCsvService;
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

    /**
     * 导出台账为 CSV。
     *
     * <p>与分析模块的导出同一约定：带 UTF-8 BOM（否则 Excel 打开中文乱码），
     * 返回 {@code text/csv} 而不是统一响应体 —— 文件下载无法塞进 JSON。
     */
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            LocalDate endDate) {

        byte[] payload = ledgerCsvService.exportCsv(user.userId(), startDate, endDate);
        String filename = "ledgers.csv";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(payload);
    }

    /**
     * 导入台账 CSV。
     *
     * <p>请求体直接是 CSV 文本（{@code text/csv}），不用 multipart：
     * 前端用 FileReader 读出文本即可，服务端也少一层解析。
     *
     * <p>整份文件先校验后写入 —— 有一行不合格就一条都不写，
     * 避免"导了一半"这种最难收拾的状态。重复导入同一份文件是安全的
     * （幂等键由文件哈希 + 行号确定性生成，第二次全部计为 skipped）。
     */
    @PostMapping(value = "/import", consumes = "text/csv")
    public Result<LedgerCsvService.ImportResult> importCsv(
            @AuthenticationPrincipal CurrentUser user,
            @RequestBody String csv) {
        return Result.success(ledgerCsvService.importCsv(user.userId(), csv));
    }

    /** 账目分页查询 */
    @GetMapping
    public Result<PageResult<Ledger>> page(@AuthenticationPrincipal CurrentUser user,
                                          @RequestParam(defaultValue = "1")
                                          @Min(value = 1, message = "页码至少为 1")
                                          long page,
                                          @RequestParam(defaultValue = "20")
                                          @Min(value = 1, message = "每页条数至少为 1")
                                          @Max(value = 200, message = "每页条数最多 200")
                                          long size,
                                          @RequestParam(required = false) LocalDate startDate,
                                          @RequestParam(required = false) LocalDate endDate,
                                          @RequestParam(required = false) LedgerType ledgerType,
                                          @RequestParam(required = false) Long subjectId,
                                          @RequestParam(required = false) BigDecimal minAmount,
                                          @RequestParam(required = false) BigDecimal maxAmount,
                                          @RequestParam(required = false) String sortBy,
                                          @RequestParam(required = false) String sortOrder,
                                          @RequestParam(required = false)
                                          @jakarta.validation.constraints.Size(max = 50,
                                                  message = "搜索词不能超过 50 字")
                                          String keyword) {
        IPage<Ledger> result = ledgerCoreService.page(user.userId(), page, size,
                startDate, endDate, ledgerType, subjectId, minAmount, maxAmount,
                sortBy, sortOrder, keyword);
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

    /**
     * 借贷平衡全局校验（开发自检用）。
     *
     * <p><b>金额必须转成字符串再返回</b>。
     * 这里曾经把 Mapper 查出的 {@code Map<String, BigDecimal>}
     * 原样塞进响应，于是 Jackson 把 0.00 序列化成了
     * JSON <b>数字</b>，而不是本项目全局约定的字符串。
     *
     * <p>后果很隐蔽：</p>
     * <ul>
     *   <li>浏览器 JS 里 {@code 0.00 === "0.00"} 不成立，但一般不会有人拿它做严格相等判断；</li>
     *   <li>Windows PowerShell 5.1 把 0.00 解析成 [decimal]，保留标度，
     *       打印出来仍是 "0.00"，于是本地测试一直是绿的；</li>
     *   <li>PowerShell 7 解析成 [double]，打印出来是 "0"，
     *       CI 上才暴露 —— 这个约定破口就是这样被发现的。</li>
     * </ul>
     */
    @GetMapping("/balance-check")
    public Result<Map<String, Object>> balanceCheck(@AuthenticationPrincipal CurrentUser user) {
        Map<String, java.math.BigDecimal> raw = ledgerCoreService.globalBalanceCheck();
        List<Map<String, Object>> rawDrift = ledgerCoreService.accountBalanceDrift();

        Map<String, Object> global = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, java.math.BigDecimal> e : raw.entrySet()) {
            global.put(e.getKey(), com.example.ledger.common.util.MoneyUtil.toPlainString(e.getValue()));
        }

        // 对账明细里的金额同样要转字符串，否则同一个端点上会出现两种类型
        List<Map<String, Object>> drift = new java.util.ArrayList<>(rawDrift.size());
        for (Map<String, Object> row : rawDrift) {
            Map<String, Object> converted = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                Object v = e.getValue();
                converted.put(e.getKey(), v instanceof java.math.BigDecimal bd
                        ? com.example.ledger.common.util.MoneyUtil.toPlainString(bd) : v);
            }
            drift.add(converted);
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("globalBalance", global);
        result.put("globalBalanced", raw.get("diff") == null
                || raw.get("diff").compareTo(java.math.BigDecimal.ZERO) == 0);
        result.put("accountDrift", drift);
        result.put("accountBalanced", drift.isEmpty());
        return Result.success(result);
    }
}
