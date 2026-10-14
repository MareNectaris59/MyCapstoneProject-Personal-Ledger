package com.example.ledger.module.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 记账请求体。
 *
 * <p>金额使用 {@link BigDecimal} 接收，但对外 JSON 传输时是<b>字符串</b>
 * （{@code "30.00"}），避免 JSON 浮点精度丢失（《系统设计说明书》§6.1.3）。
 *
 * <p>{@code @Digits(integer = 16, fraction = 2)} 在这里承担 C7 约束的第一道
 * 拦截；真正的校验在 {@code MoneyUtil.validateAmount} 与
 * {@code BalanceValidator} 中再做一次（不依赖前端的信任边界）。
 *
 * <p>{@code idempotencyKey} 由 Controller 从 {@code Idempotency-Key} 请求头注入，
 * 不由客户端放在 body 里 —— 幂等键属于传输层语义。重复提交时返回首次的结果，
 * 不重复记账（BR-12、AC-06）。
 */
public final class LedgerRequests {

    private LedgerRequests() {
    }

    /** 记一笔支出：借 费用科目 / 贷 资产科目 */
    public record Expense(
            @NotNull(message = "金额不能为空")
            @DecimalMin(value = "0.01", message = "金额不能小于 0.01")
            @Digits(integer = 16, fraction = 2, message = "金额最多保留两位小数")
            BigDecimal amount,

            @NotNull(message = "请选择支出科目")
            Long expenseSubjectId,

            @NotNull(message = "请选择付款账户")
            Long assetSubjectId,

            @NotNull(message = "请选择记账日期")
            LocalDate occurredDate,

            @Size(max = 255, message = "备注不能超过 255 字")
            String remark,

            @com.fasterxml.jackson.annotation.JsonIgnore
            String idempotencyKey
    ) {
        /** 附带幂等键的副本。 */
        public Expense withIdempotencyKey(String key) {
            return new Expense(amount, expenseSubjectId, assetSubjectId, occurredDate, remark, key);
        }
    }

    /** 记一笔收入：借 资产科目 / 贷 收入科目 */
    public record Income(
            @NotNull(message = "金额不能为空")
            @DecimalMin(value = "0.01", message = "金额不能小于 0.01")
            @Digits(integer = 16, fraction = 2, message = "金额最多保留两位小数")
            BigDecimal amount,

            @NotNull(message = "请选择收款账户")
            Long assetSubjectId,

            @NotNull(message = "请选择收入科目")
            Long incomeSubjectId,

            @NotNull(message = "请选择记账日期")
            LocalDate occurredDate,

            @Size(max = 255, message = "备注不能超过 255 字")
            String remark,

            @com.fasterxml.jackson.annotation.JsonIgnore
            String idempotencyKey
    ) {
        public Income withIdempotencyKey(String key) {
            return new Income(amount, assetSubjectId, incomeSubjectId, occurredDate, remark, key);
        }
    }

    /** 记一笔转账：借 转入账户 / 贷 转出账户。不影响任何预算（BR-04） */
    public record Transfer(
            @NotNull(message = "金额不能为空")
            @DecimalMin(value = "0.01", message = "金额不能小于 0.01")
            @Digits(integer = 16, fraction = 2, message = "金额最多保留两位小数")
            BigDecimal amount,

            @NotNull(message = "请选择转出账户")
            Long fromSubjectId,

            @NotNull(message = "请选择转入账户")
            Long toSubjectId,

            @NotNull(message = "请选择记账日期")
            LocalDate occurredDate,

            @Size(max = 255, message = "备注不能超过 255 字")
            String remark,

            @com.fasterxml.jackson.annotation.JsonIgnore
            String idempotencyKey
    ) {
        public Transfer withIdempotencyKey(String key) {
            return new Transfer(amount, fromSubjectId, toSubjectId, occurredDate, remark, key);
        }
    }

    /** 冲正。{@code amount} 为 null 时表示全额冲正 */
    public record Reverse(
            @DecimalMin(value = "0.01", message = "冲正金额不能小于 0.01")
            @Digits(integer = 16, fraction = 2, message = "金额最多保留两位小数")
            BigDecimal amount,

            @Size(max = 255, message = "冲正原因不能超过 255 字")
            String reason
    ) {
    }
}
