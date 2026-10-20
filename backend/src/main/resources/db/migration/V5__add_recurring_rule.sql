-- =============================================================================
--  V5：定期记账规则表
--
--  为什么需要"锚定日"（anchor_day）：
--    月付的房租常定在 31 号。若直接用 LocalDate.plusMonths(1) 逐月推进，
--    1 月 31 日会变成 2 月 28 日，之后就一直停在 28 日 —— 越走越偏。
--    正确做法是记住最初约定的日期（anchor_day = 31），
--    每次推进时再按当月实际天数夹取：31 -> 28 -> 31 -> 30 ...
--    这一点单独存一列，否则信息在第一次夹取后就丢了。
--
--  为什么没有外键：
--    与 V1 基线一致，本项目不在数据库层建外键（依赖应用层校验）。
--    记账内核的写入路径已经保证了科目属于当前用户、且为末级科目。
-- =============================================================================

CREATE TABLE `recurring_rule` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID',
  `name` varchar(64) NOT NULL COMMENT '规则名称，用户可读（如「房租」）',
  `ledger_type` varchar(16) NOT NULL COMMENT '生成的凭证类型：EXPENSE/INCOME/TRANSFER',
  `amount` decimal(18,2) NOT NULL COMMENT '每期金额',
  `subject_id` bigint unsigned NOT NULL COMMENT '科目：支出/收入科目；转账时为转出账户',
  `counter_subject_id` bigint unsigned NOT NULL COMMENT '对方科目：付款/收款账户；转账时为转入账户',
  `remark` varchar(255) DEFAULT NULL COMMENT '生成账目时写入的备注',
  `frequency` varchar(16) NOT NULL COMMENT '频率：WEEKLY/MONTHLY',
  `anchor_day` int DEFAULT NULL COMMENT '月度锚定日（1-31），仅 MONTHLY 使用，见文件头说明',
  `next_run_date` date NOT NULL COMMENT '下次执行日；到点即生成账目并向后推进',
  `end_date` date DEFAULT NULL COMMENT '结束日，NULL 表示长期有效',
  `last_run_date` date DEFAULT NULL COMMENT '最近一次成功执行的日期',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  -- 定时任务按「启用 + 到期」扫描，这个索引是它的唯一入口
  KEY `idx_due` (`enabled`, `deleted`, `next_run_date`),
  KEY `idx_user` (`user_id`, `deleted`),
  CONSTRAINT `ck_recurring_amount` CHECK ((`amount` > 0)),
  CONSTRAINT `ck_recurring_anchor` CHECK (((`anchor_day` is null) or (`anchor_day` between 1 and 31)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定期记账规则表';
