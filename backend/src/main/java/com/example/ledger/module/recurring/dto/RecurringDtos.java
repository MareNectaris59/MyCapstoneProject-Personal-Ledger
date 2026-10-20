package com.example.ledger.module.recurring.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 定期记账的请求与响应体。
 *
 * <p>响应里刻意<b>不</b>返回 {@code subjectId} 这类裸 ID 给前端直接展示，
 * 而是同时给出 {@code subjectName} —— 前端不需要再查一次科目表。
 */
public final class RecurringDtos {

    private RecurringDtos() {
    }

    /**
     * 新建规则。
     *
     * <p>{@code startDate} 是首次执行日（今天或将来）。用"起始日"而不是
     * "下次执行日"来命名，是因为用户填的是"从哪天开始"这个更自然的语义。
     */
    public record Create(
            @NotBlank(message = "请输入名称")
            @Size(max = 64, message = "名称不能超过 64 字")
            String name,

            @NotBlank(message = "请选择类型")
            String ledgerType,

            @NotNull(message = "请输入金额")
            BigDecimal amount,

            @NotNull(message = "请选择科目")
            Long subjectId,

            @NotNull(message = "请选择对方账户")
            Long counterSubjectId,

            @Size(max = 255, message = "备注不能超过 255 字")
            String remark,

            @NotBlank(message = "请选择频率")
            String frequency,

            /** 月度锚定日，可选；不填则取 startDate 的日 */
            @Min(value = 1, message = "锚定日最小 1")
            @Max(value = 31, message = "锚定日最大 31")
            Integer anchorDay,

            @NotNull(message = "请选择首次执行日")
            LocalDate startDate,

            LocalDate endDate
    ) {
    }

    /**
     * 修改规则。
     *
     * <p>只开放"金额 / 备注 / 启用 / 结束日"四项可改：
     * 类型、科目、频率、锚定日改变会牵动已经生成过的账目的语义，
     * 与其让用户改一个规则去影响历史，不如停用旧规则、新建一条。
     */
    public record Update(
            BigDecimal amount,
            @Size(max = 255, message = "备注不能超过 255 字")
            String remark,
            Boolean enabled,
            LocalDate endDate
    ) {
    }

    /** 列表项 / 详情。 */
    public record View(
            Long id,
            String name,
            String ledgerType,
            String amount,
            Long subjectId,
            String subjectName,
            Long counterSubjectId,
            String counterSubjectName,
            String remark,
            String frequency,
            Integer anchorDay,
            LocalDate nextRunDate,
            LocalDate endDate,
            LocalDate lastRunDate,
            boolean enabled,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 一次执行的结果。
     *
     * @param generated 实际新生成的账目数
     * @param skipped   因幂等键已存在而跳过的条数（重复执行时全部落这里）
     * @param advanced  推进了执行日的规则数
     */
    public record RunResult(int generated, int skipped, int advanced) {
    }
}
