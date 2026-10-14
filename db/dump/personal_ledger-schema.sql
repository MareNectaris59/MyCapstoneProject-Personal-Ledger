
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `personal_ledger` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `personal_ledger`;
DROP TABLE IF EXISTS `account_meta`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `account_meta` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `subject_id` bigint unsigned NOT NULL COMMENT '关联科目ID（资产类）',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID（冗余）',
  `account_kind` varchar(20) NOT NULL DEFAULT 'OTHER' COMMENT '账户类型：BANK/WECHAT/ALIPAY/CASH/SAVING/INVEST/OTHER',
  `bank_name` varchar(64) DEFAULT NULL COMMENT '开户行',
  `card_tail_no` varchar(8) DEFAULT NULL COMMENT '卡号后四位（禁止存完整卡号）',
  `opening_balance` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '期初余额',
  `balance` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '当前余额（缓存，允许为负）',
  `include_in_net_worth` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否计入净资产：0否 1是',
  `reconciled_at` datetime(3) DEFAULT NULL COMMENT '最近对账通过时间',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subject` (`subject_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=166 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资金账户扩展表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `budget`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `budget` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '预算ID',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID',
  `name` varchar(64) NOT NULL COMMENT '预算名称',
  `scope_type` varchar(16) NOT NULL COMMENT '范围类型：TOTAL全部费用/SUBJECT指定科目',
  `subject_id` bigint unsigned DEFAULT NULL COMMENT '预算科目ID（仅费用类）',
  `period_type` varchar(16) NOT NULL COMMENT '周期类型：WEEK/MONTH/CUSTOM',
  `period_value` int DEFAULT NULL COMMENT '自定义周期天数（仅CUSTOM）',
  `start_date` date NOT NULL COMMENT '生效起始日',
  `end_date` date DEFAULT NULL COMMENT '结束日，NULL表示长期有效',
  `amount` decimal(18,2) NOT NULL COMMENT '每期额度',
  `rollover_mode` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT '结转模式：NONE/SURPLUS_ONLY/FULL',
  `alert_threshold` decimal(5,2) NOT NULL DEFAULT '80.00' COMMENT '预警阈值（百分比）',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_user_enabled` (`user_id`,`enabled`,`deleted`),
  KEY `idx_user_subject` (`user_id`,`subject_id`),
  CONSTRAINT `ck_budget_amount` CHECK ((`amount` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预算定义表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `budget_alert`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `budget_alert` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `budget_id` bigint unsigned NOT NULL COMMENT '预算ID',
  `budget_period_id` bigint unsigned NOT NULL COMMENT '预算周期ID',
  `alert_level` varchar(16) NOT NULL COMMENT '级别：INFO/WARNING/DANGER',
  `alert_date` date NOT NULL COMMENT '预警日期',
  `execution_rate` decimal(7,2) DEFAULT NULL COMMENT '触发时执行率',
  `used_amount` decimal(18,2) NOT NULL COMMENT '触发时已用额',
  `available_amount` decimal(18,2) NOT NULL COMMENT '触发时可用额',
  `message` varchar(255) NOT NULL COMMENT '提示文案',
  `is_read` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已读',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_period_level_date` (`budget_period_id`,`alert_level`,`alert_date`),
  KEY `idx_user_read` (`user_id`,`is_read`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预算预警记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `budget_period`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `budget_period` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `budget_id` bigint unsigned NOT NULL COMMENT '所属预算ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID（冗余）',
  `period_label` varchar(16) NOT NULL COMMENT '周期标识，如 2025-03',
  `start_date` date NOT NULL COMMENT '周期起始日',
  `end_date` date NOT NULL COMMENT '周期结束日',
  `base_amount` decimal(18,2) NOT NULL COMMENT '本期基础额度',
  `carry_in` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '期初结转额（可为负）',
  `carry_floor` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '被下限截断的部分（用于展示累计超支）',
  `available_amount` decimal(18,2) NOT NULL COMMENT '本期可用额 = base_amount + carry_in',
  `used_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '本期已用额（缓存，由entry重算）',
  `execution_rate` decimal(7,2) DEFAULT NULL COMMENT '执行率（%），可用额为0时为NULL',
  `settle_state` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT '结算状态：OPEN/SETTLED',
  `settled_at` datetime(3) DEFAULT NULL COMMENT '结算时间',
  `dirty_since` date DEFAULT NULL COMMENT '待重算标记：最早的脏记账日期，NULL表示数据最新',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_budget_start` (`budget_id`,`start_date`),
  KEY `idx_user_date` (`user_id`,`start_date`,`end_date`),
  KEY `idx_settle` (`settle_state`,`end_date`),
  KEY `idx_dirty` (`dirty_since`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预算周期实例表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `entry`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `entry` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '分录ID',
  `ledger_id` bigint unsigned NOT NULL COMMENT '所属凭证ID',
  `entry_no` tinyint unsigned NOT NULL COMMENT '凭证内序号，从1开始',
  `subject_id` bigint unsigned NOT NULL COMMENT '科目ID（必须是叶子科目）',
  `direction` varchar(10) NOT NULL COMMENT '方向：DEBIT借/CREDIT贷',
  `amount` decimal(18,2) NOT NULL COMMENT '金额（恒为正数）',
  `signed_amount` decimal(18,2) NOT NULL COMMENT '带符号金额：借为+，贷为-',
  `summary` varchar(255) DEFAULT NULL COMMENT '分录摘要',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ledger` (`ledger_id`,`entry_no`),
  KEY `idx_subject` (`subject_id`,`id`),
  KEY `idx_ledger_subject` (`ledger_id`,`subject_id`),
  CONSTRAINT `ck_entry_amount` CHECK ((`amount` > 0)),
  CONSTRAINT `ck_entry_direction` CHECK ((`direction` in (_utf8mb4'DEBIT',_utf8mb4'CREDIT')))
) ENGINE=InnoDB AUTO_INCREMENT=77 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会计分录表（账务唯一真相）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ledger`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ledger` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '凭证ID',
  `ledger_no` varchar(32) NOT NULL COMMENT '凭证号，如 LE20250301001',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID',
  `ledger_type` varchar(20) NOT NULL COMMENT '凭证类型：EXPENSE/INCOME/TRANSFER/OPENING/REVERSAL',
  `state` varchar(24) NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/POSTED/PARTIALLY_REVERSED/REVERSED',
  `amount` decimal(18,2) NOT NULL COMMENT '凭证金额（借方合计=贷方合计）',
  `reversed_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '已冲正金额（累计）',
  `reversal_of_id` bigint unsigned DEFAULT NULL COMMENT '冲正单指向的原凭证ID',
  `occurred_date` date NOT NULL COMMENT '记账业务日期（统计主过滤条件）',
  `occurred_at` datetime(3) DEFAULT NULL COMMENT '精确发生时间（可选）',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `reversal_reason` varchar(255) DEFAULT NULL COMMENT '冲正原因（仅冲正单）',
  `idempotency_key` varchar(64) DEFAULT NULL COMMENT '幂等键（客户端UUID）',
  `request_id` varchar(64) DEFAULT NULL COMMENT '请求追踪ID',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `posted_at` datetime(3) DEFAULT NULL COMMENT '记账生效时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_no` (`user_id`,`ledger_no`),
  UNIQUE KEY `uk_idem` (`user_id`,`idempotency_key`),
  UNIQUE KEY `uk_reversal_once` (`reversal_of_id`,`ledger_type`),
  KEY `idx_user_date` (`user_id`,`occurred_date`,`state`),
  KEY `idx_user_type_date` (`user_id`,`ledger_type`,`occurred_date`),
  CONSTRAINT `ck_ledger_amount` CHECK ((`amount` > 0)),
  CONSTRAINT `ck_ledger_reversed` CHECK (((`reversed_amount` >= 0) and (`reversed_amount` <= `amount`)))
) ENGINE=InnoDB AUTO_INCREMENT=47 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='记账凭证表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ledger_attachment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ledger_attachment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ledger_id` bigint unsigned NOT NULL COMMENT '所属凭证ID',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID（越权校验用）',
  `file_name` varchar(255) NOT NULL COMMENT '原始文件名',
  `file_path` varchar(512) NOT NULL COMMENT '存储相对路径',
  `file_size` int unsigned NOT NULL DEFAULT '0' COMMENT '文件大小（字节）',
  `content_type` varchar(64) DEFAULT NULL COMMENT 'MIME 类型',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_ledger` (`ledger_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭证附件表（结构预留）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `reconcile_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reconcile_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned DEFAULT NULL COMMENT '用户ID，NULL为全局对账',
  `check_type` varchar(32) NOT NULL COMMENT '校验类型：GLOBAL_BALANCE/ACCOUNT_BALANCE/BUDGET_USED',
  `ledger_count` int NOT NULL DEFAULT '0' COMMENT '参与校验的凭证数',
  `entry_count` int NOT NULL DEFAULT '0' COMMENT '参与校验的分录数',
  `diff_count` int NOT NULL DEFAULT '0' COMMENT '差异记录数（期望0）',
  `diff_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '差异金额合计',
  `detail` json DEFAULT NULL COMMENT '差异详情',
  `status` varchar(16) NOT NULL COMMENT '结果：PASSED/FAILED/FIXED',
  `duration_ms` int NOT NULL DEFAULT '0' COMMENT '校验耗时(ms)',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行时间',
  PRIMARY KEY (`id`),
  KEY `idx_type_created` (`check_type`,`created_at`),
  KEY `idx_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账校验日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `subject`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `subject` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '科目ID',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID',
  `parent_id` bigint unsigned NOT NULL DEFAULT '0' COMMENT '父科目ID，0表示一级科目',
  `name` varchar(64) NOT NULL COMMENT '科目名称',
  `subject_code` varchar(32) NOT NULL COMMENT '科目编码，同用户内唯一',
  `subject_type` varchar(20) NOT NULL COMMENT '科目类型：ASSET/LIABILITY/EQUITY/INCOME/EXPENSE',
  `normal_side` varchar(10) NOT NULL COMMENT '正常余额方向：DEBIT/CREDIT',
  `level` tinyint unsigned NOT NULL DEFAULT '1' COMMENT '层级，从1开始',
  `path` varchar(255) NOT NULL COMMENT '物化路径，如 /1/12/125/',
  `is_leaf` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否叶子科目：0否 1是（仅叶子可记账）',
  `budgetable` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否可设预算：仅费用类为1',
  `icon` varchar(64) DEFAULT NULL COMMENT '图标名',
  `color` varchar(16) DEFAULT NULL COMMENT '展示颜色，如 #FF7043',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '同级排序',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用：0停用 1启用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_code` (`user_id`,`subject_code`),
  KEY `idx_user_type` (`user_id`,`subject_type`,`deleted`),
  KEY `idx_parent` (`user_id`,`parent_id`),
  KEY `idx_path` (`path`(64)),
  KEY `idx_user_leaf` (`user_id`,`is_leaf`,`enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=1254 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会计科目表（资金账户与收支分类统一存放）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `email` varchar(128) NOT NULL COMMENT '登录邮箱（小写）',
  `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt 密码哈希',
  `nickname` varchar(50) NOT NULL COMMENT '昵称',
  `avatar_url` varchar(512) DEFAULT NULL COMMENT '头像地址',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
  `email_verified` tinyint(1) NOT NULL DEFAULT '0' COMMENT '邮箱是否已验证：0否 1是',
  `last_login_at` datetime(3) DEFAULT NULL COMMENT '最近登录时间',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 1已删除',
  `deleted_at` datetime(3) DEFAULT NULL COMMENT '注销时间；NULL 表示未注销',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_profile`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_profile` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `base_currency` varchar(8) NOT NULL DEFAULT 'CNY' COMMENT '本位币，v1.0 固定 CNY',
  `currency_symbol` varchar(8) NOT NULL DEFAULT '¥' COMMENT '货币符号',
  `amount_precision` tinyint NOT NULL DEFAULT '2' COMMENT '金额展示小数位',
  `week_start` tinyint NOT NULL DEFAULT '1' COMMENT '周起始日：1周一 ... 7周日',
  `strict_balance_mode` tinyint(1) NOT NULL DEFAULT '0' COMMENT '严格余额模式：0否 1是',
  `budget_warn_threshold` decimal(5,2) NOT NULL DEFAULT '80.00' COMMENT '预算预警阈值（百分比）',
  `alert_email_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否邮件推送预警',
  `preferences` json DEFAULT NULL COMMENT '扩展偏好配置',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户偏好设置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

