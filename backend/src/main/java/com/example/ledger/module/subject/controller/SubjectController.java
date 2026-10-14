package com.example.ledger.module.subject.controller;

import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.result.Result;
import com.example.ledger.module.subject.dto.SubjectTreeNode;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.service.SubjectService;
import com.example.ledger.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 科目与账户接口（《系统设计说明书》§6.2.3）。
 */
@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    private final SubjectService subjectService;

    public SubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    /** 科目树。可按 type 过滤（如 ASSET 取资金账户、EXPENSE 取消费分类） */
    @GetMapping("/tree")
    public Result<List<SubjectTreeNode>> tree(@AuthenticationPrincipal CurrentUser user,
                                              @RequestParam(required = false) SubjectType type) {
        return Result.success(subjectService.tree(user.userId(), type));
    }

    /** 资产科目（资金账户）及其余额 */
    @GetMapping("/accounts")
    public Result<List<SubjectTreeNode>> accounts(@AuthenticationPrincipal CurrentUser user) {
        return Result.success(subjectService.assetAccounts(user.userId()));
    }

    /**
     * 科目余额（同时返回缓存值与分录重算值，用于发现漂移）。
     *
     * <p>{@code {id:\d+}} 的数字约束是必需的：否则 {@code /{id}/balance} 这类
     * 模板会与 {@code /accounts}、{@code /tree} 等字面量路径互相争抢匹配，
     * 表现为随机的 404。
     */
    @GetMapping("/{id:\\d+}/balance")
    public Result<Map<String, Object>> balance(@AuthenticationPrincipal CurrentUser user,
                                              @PathVariable Long id) {
        return Result.success(subjectService.balance(user.userId(), id));
    }

    /** 某账户的资金变动明细 */
    @GetMapping("/{id:\\d+}/entries")
    public Result<List<Map<String, Object>>> entries(@AuthenticationPrincipal CurrentUser user,
                                                     @PathVariable Long id,
                                                     @RequestParam(defaultValue = "50") int limit) {
        return Result.success(subjectService.accountEntries(user.userId(), id, limit));
    }

    /** 新增科目 */
    @PostMapping
    public Result<Subject> create(@AuthenticationPrincipal CurrentUser user,
                                  @RequestBody CreateSubjectRequest req) {
        Subject created = subjectService.create(user.userId(), req.parentId(), req.name(),
                req.subjectType(), req.accountKind(), req.color());
        return Result.success(created);
    }

    /** 启用/停用科目 */
    @PutMapping("/{id:\\d+}/enabled")
    public Result<Void> setEnabled(@AuthenticationPrincipal CurrentUser user,
                                   @PathVariable Long id,
                                   @RequestParam boolean enabled) {
        subjectService.setEnabled(user.userId(), id, enabled);
        return Result.success();
    }

    /** 删除科目（存在子科目或已被分录引用时会被拒绝） */
    @DeleteMapping("/{id:\\d+}")
    public Result<Void> delete(@AuthenticationPrincipal CurrentUser user,
                               @PathVariable Long id) {
        subjectService.delete(user.userId(), id);
        return Result.success();
    }

    /**
     * 设置期初余额，生成一张「借 资产 / 贷 权益·期初余额」的建账凭证。
     */
    @PostMapping("/{id:\\d+}/opening-balance")
    public Result<Map<String, Object>> openingBalance(@AuthenticationPrincipal CurrentUser user,
                                                      @PathVariable Long id,
                                                      @RequestBody OpeningBalanceRequest req) {
        return Result.success(subjectService.setOpeningBalance(
                user.userId(), id, req.amount(), req.occurredDate()));
    }

    /** 新增科目请求体 */
    public record CreateSubjectRequest(
            Long parentId,
            @NotBlank(message = "科目名称不能为空") String name,
            @NotBlank(message = "科目类型不能为空") SubjectType subjectType,
            String accountKind,
            String color
    ) {
    }

    /** 期初余额请求体 */
    public record OpeningBalanceRequest(
            BigDecimal amount,
            LocalDate occurredDate
    ) {
    }
}
