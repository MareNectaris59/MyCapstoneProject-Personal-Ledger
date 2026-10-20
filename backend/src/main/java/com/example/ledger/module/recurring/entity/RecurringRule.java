package com.example.ledger.module.recurring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 定期记账规则。
 *
 * <p>与 {@code budget} 一样使用逻辑删除（{@code deleted}），因为规则是用户
 * 反复调整的配置项；物理删除会让"这条账是谁生成的"彻底断线。
 *
 * <p>注意：本表只存<b>规则</b>，真正生成的账目仍然走记账内核写进
 * {@code ledger} / {@code entry}，并且带确定性幂等键
 * {@code rec-<规则ID>-<执行日>}，因此重复执行不会重复记账。
 */
@TableName("recurring_rule")
public class RecurringRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    /** EXPENSE / INCOME / TRANSFER */
    private String ledgerType;
    private BigDecimal amount;
    /** 支出/收入科目；转账时为转出账户 */
    private Long subjectId;
    /** 付款/收款账户；转账时为转入账户 */
    private Long counterSubjectId;
    private String remark;
    /** WEEKLY / MONTHLY */
    private String frequency;
    /** 月度锚定日 1-31，仅 MONTHLY 使用 */
    private Integer anchorDay;
    private LocalDate nextRunDate;
    private LocalDate endDate;
    private LocalDate lastRunDate;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLedgerType() {
        return ledgerType;
    }

    public void setLedgerType(String ledgerType) {
        this.ledgerType = ledgerType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Long getCounterSubjectId() {
        return counterSubjectId;
    }

    public void setCounterSubjectId(Long counterSubjectId) {
        this.counterSubjectId = counterSubjectId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public Integer getAnchorDay() {
        return anchorDay;
    }

    public void setAnchorDay(Integer anchorDay) {
        this.anchorDay = anchorDay;
    }

    public LocalDate getNextRunDate() {
        return nextRunDate;
    }

    public void setNextRunDate(LocalDate nextRunDate) {
        this.nextRunDate = nextRunDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public LocalDate getLastRunDate() {
        return lastRunDate;
    }

    public void setLastRunDate(LocalDate lastRunDate) {
        this.lastRunDate = lastRunDate;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
