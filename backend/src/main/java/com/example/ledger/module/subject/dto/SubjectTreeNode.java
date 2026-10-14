package com.example.ledger.module.subject.dto;

import java.util.List;

/**
 * 科目树节点。
 *
 * <p>余额只在资产类科目上有意义（费用/收入类科目不维护余额缓存，
 * 需要时由分录按期间汇总）。
 */
public record SubjectTreeNode(
        Long id,
        Long parentId,
        String code,
        String name,
        String subjectType,
        String normalSide,
        Integer level,
        boolean leaf,
        boolean budgetable,
        boolean enabled,
        String icon,
        String color,
        Integer sortOrder,
        /** 资产类账户类型：BANK / WECHAT / ALIPAY / CASH / SAVING / INVEST / OTHER */
        String accountKind,
        /** 余额（字符串，仅资产类科目非 null） */
        String balance,
        /** 是否计入净资产（仅资产类） */
        Boolean includeInNetWorth,
        List<SubjectTreeNode> children
) {
}
