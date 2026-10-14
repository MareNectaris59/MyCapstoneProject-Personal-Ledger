-- ============================================================
-- 个人复式记账与消费分析系统 —— 数据库结构初始化
--
-- 来源：docs/数据库设计.md §4 建表 DDL（已通过括号配平、主键、
--       字段注释覆盖率校验）
-- 目标库：MySQL 8.0+
--
-- 使用方式（二选一）：
--   A. 命令行：mysql -u root -p < schema.sql
--   B. Spring Boot 启动时自动执行：设置 spring.sql.init.mode=always
--
-- 说明：本脚本不含 CREATE DATABASE 与 USE 语句，因为两处执行入口
--       都已指定了目标库（JDBC URL 中的数据库名 / 命令行连接的库）。
--       建库请执行同目录下的 create_database.sql。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 用户表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `email`          VARCHAR(128)    NOT NULL                COMMENT '登录邮箱（小写）',
  `password_hash`  VARCHAR(100)    NOT NULL                COMMENT 'BCrypt 密码哈希',
  `nickname`       VARCHAR(50)     NOT NULL                COMMENT '昵称',
  `avatar_url`     VARCHAR(512)    NULL     DEFAULT NULL   COMMENT '头像地址',
  `status`         VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
  `email_verified` TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '邮箱是否已验证：0否 1是',
  `last_login_at`  DATETIME(3)     NULL     DEFAULT NULL   COMMENT '最近登录时间',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`        TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0正常 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 用户偏好设置表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user_profile`;
CREATE TABLE `user_profile` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`               BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
  `base_currency`         VARCHAR(8)      NOT NULL DEFAULT 'CNY'  COMMENT '本位币，v1.0 固定 CNY',
  `currency_symbol`       VARCHAR(8)      NOT NULL DEFAULT '¥'    COMMENT '货币符号',
  `amount_precision`      TINYINT         NOT NULL DEFAULT 2      COMMENT '金额展示小数位',
  `week_start`            TINYINT         NOT NULL DEFAULT 1      COMMENT '周起始日：1周一 ... 7周日',
  `strict_balance_mode`   TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '严格余额模式：0否 1是',
  `budget_warn_threshold` DECIMAL(5,2)    NOT NULL DEFAULT 80.00  COMMENT '预算预警阈值（百分比）',
  `alert_email_enabled`   TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否邮件推送预警',
  `preferences`           JSON            NULL     DEFAULT NULL   COMMENT '扩展偏好配置',
  `created_at`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好设置表';

-- ------------------------------------------------------------
-- 3. 会计科目表（资金账户 + 收支分类统一存放）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `subject`;
CREATE TABLE `subject` (
  `id`           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '科目ID',
  `user_id`      BIGINT UNSIGNED  NOT NULL                COMMENT '所属用户ID',
  `parent_id`    BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '父科目ID，0表示一级科目',
  `name`         VARCHAR(64)      NOT NULL                COMMENT '科目名称',
  `subject_code` VARCHAR(32)      NOT NULL                COMMENT '科目编码，同用户内唯一',
  `subject_type` VARCHAR(20)      NOT NULL                COMMENT '科目类型：ASSET/LIABILITY/EQUITY/INCOME/EXPENSE',
  `normal_side`  VARCHAR(10)      NOT NULL                COMMENT '正常余额方向：DEBIT/CREDIT',
  `level`        TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '层级，从1开始',
  `path`         VARCHAR(255)     NOT NULL                COMMENT '物化路径，如 /1/12/125/',
  `is_leaf`      TINYINT(1)       NOT NULL DEFAULT 1      COMMENT '是否叶子科目：0否 1是（仅叶子可记账）',
  `budgetable`   TINYINT(1)       NOT NULL DEFAULT 0      COMMENT '是否可设预算：仅费用类为1',
  `icon`         VARCHAR(64)      NULL     DEFAULT NULL   COMMENT '图标名',
  `color`        VARCHAR(16)      NULL     DEFAULT NULL   COMMENT '展示颜色，如 #FF7043',
  `sort_order`   INT              NOT NULL DEFAULT 0      COMMENT '同级排序',
  `enabled`      TINYINT(1)       NOT NULL DEFAULT 1      COMMENT '是否启用：0停用 1启用',
  `remark`       VARCHAR(255)     NULL     DEFAULT NULL   COMMENT '备注',
  `created_at`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`      TINYINT(1)       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0正常 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_code`  (`user_id`, `subject_code`),
  KEY `idx_user_type`        (`user_id`, `subject_type`, `deleted`),
  KEY `idx_parent`           (`user_id`, `parent_id`),
  KEY `idx_path`             (`path`(64)),
  KEY `idx_user_leaf`        (`user_id`, `is_leaf`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会计科目表（资金账户与收支分类统一存放）';

-- ------------------------------------------------------------
-- 4. 资金账户扩展表（仅 ASSET 类型科目有对应行）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `account_meta`;
CREATE TABLE `account_meta` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `subject_id`           BIGINT UNSIGNED NOT NULL                COMMENT '关联科目ID（资产类）',
  `user_id`              BIGINT UNSIGNED NOT NULL                COMMENT '用户ID（冗余）',
  `account_kind`         VARCHAR(20)     NOT NULL DEFAULT 'OTHER' COMMENT '账户类型：BANK/WECHAT/ALIPAY/CASH/SAVING/INVEST/OTHER',
  `bank_name`            VARCHAR(64)     NULL     DEFAULT NULL   COMMENT '开户行',
  `card_tail_no`         VARCHAR(8)      NULL     DEFAULT NULL   COMMENT '卡号后四位（禁止存完整卡号）',
  `opening_balance`      DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '期初余额',
  `balance`              DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '当前余额（缓存，允许为负）',
  `include_in_net_worth` TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否计入净资产：0否 1是',
  `reconciled_at`        DATETIME(3)     NULL     DEFAULT NULL   COMMENT '最近对账通过时间',
  `created_at`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subject` (`subject_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金账户扩展表';

-- ------------------------------------------------------------
-- 5. 记账凭证表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `ledger`;
CREATE TABLE `ledger` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '凭证ID',
  `ledger_no`       VARCHAR(32)     NOT NULL                COMMENT '凭证号，如 LE20250301001',
  `user_id`         BIGINT UNSIGNED NOT NULL                COMMENT '所属用户ID',
  `ledger_type`     VARCHAR(20)     NOT NULL                COMMENT '凭证类型：EXPENSE/INCOME/TRANSFER/OPENING/REVERSAL',
  `state`           VARCHAR(24)     NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/POSTED/PARTIALLY_REVERSED/REVERSED',
  `amount`          DECIMAL(18,2)   NOT NULL                COMMENT '凭证金额（借方合计=贷方合计）',
  `reversed_amount` DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '已冲正金额（累计）',
  `reversal_of_id`  BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '冲正单指向的原凭证ID',
  `occurred_date`   DATE            NOT NULL                COMMENT '记账业务日期（统计主过滤条件）',
  `occurred_at`     DATETIME(3)     NULL     DEFAULT NULL   COMMENT '精确发生时间（可选）',
  `remark`          VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '备注',
  `reversal_reason` VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '冲正原因（仅冲正单）',
  `idempotency_key` VARCHAR(64)     NULL     DEFAULT NULL   COMMENT '幂等键（客户端UUID）',
  `request_id`      VARCHAR(64)     NULL     DEFAULT NULL   COMMENT '请求追踪ID',
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `posted_at`       DATETIME(3)     NULL     DEFAULT NULL   COMMENT '记账生效时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_no`       (`user_id`, `ledger_no`),
  UNIQUE KEY `uk_idem`          (`user_id`, `idempotency_key`),
  UNIQUE KEY `uk_reversal_once` (`reversal_of_id`, `ledger_type`),
  KEY `idx_user_date`           (`user_id`, `occurred_date`, `state`),
  KEY `idx_user_type_date`      (`user_id`, `ledger_type`, `occurred_date`),
  CONSTRAINT `ck_ledger_amount`   CHECK (`amount` > 0),
  CONSTRAINT `ck_ledger_reversed` CHECK (`reversed_amount` >= 0 AND `reversed_amount` <= `amount`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记账凭证表';

-- ------------------------------------------------------------
-- 6. 会计分录表（账务数据唯一真相，只插入不更新不删除）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `entry`;
CREATE TABLE `entry` (
  `id`            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '分录ID',
  `ledger_id`     BIGINT UNSIGNED  NOT NULL                COMMENT '所属凭证ID',
  `entry_no`      TINYINT UNSIGNED NOT NULL                COMMENT '凭证内序号，从1开始',
  `subject_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '科目ID（必须是叶子科目）',
  `direction`     VARCHAR(10)      NOT NULL                COMMENT '方向：DEBIT借/CREDIT贷',
  `amount`        DECIMAL(18,2)    NOT NULL                COMMENT '金额（恒为正数）',
  `signed_amount` DECIMAL(18,2)    NOT NULL                COMMENT '带符号金额：借为+，贷为-',
  `summary`       VARCHAR(255)     NULL     DEFAULT NULL   COMMENT '分录摘要',
  `created_at`    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ledger`         (`ledger_id`, `entry_no`),
  KEY `idx_subject`        (`subject_id`, `id`),
  KEY `idx_ledger_subject` (`ledger_id`, `subject_id`),
  CONSTRAINT `ck_entry_amount`    CHECK (`amount` > 0),
  CONSTRAINT `ck_entry_direction` CHECK (`direction` IN ('DEBIT','CREDIT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会计分录表（账务唯一真相）';

-- ------------------------------------------------------------
-- 6.1 凭证附件表（P2，结构预留，v1.0 不实现上传）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `ledger_attachment`;
CREATE TABLE `ledger_attachment` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ledger_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属凭证ID',
  `user_id`      BIGINT UNSIGNED NOT NULL                COMMENT '所属用户ID（越权校验用）',
  `file_name`    VARCHAR(255)    NOT NULL                COMMENT '原始文件名',
  `file_path`    VARCHAR(512)    NOT NULL                COMMENT '存储相对路径',
  `file_size`    INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
  `content_type` VARCHAR(64)     NULL     DEFAULT NULL   COMMENT 'MIME 类型',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_ledger` (`ledger_id`),
  KEY `idx_user`   (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭证附件表（结构预留）';

-- ------------------------------------------------------------
-- 7. 预算定义表（amount 为"每期额度"，非总预算）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `budget`;
CREATE TABLE `budget` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '预算ID',
  `user_id`         BIGINT UNSIGNED NOT NULL                COMMENT '所属用户ID',
  `name`            VARCHAR(64)     NOT NULL                COMMENT '预算名称',
  `scope_type`      VARCHAR(16)     NOT NULL                COMMENT '范围类型：TOTAL全部费用/SUBJECT指定科目',
  `subject_id`      BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '预算科目ID（仅费用类）',
  `period_type`     VARCHAR(16)     NOT NULL                COMMENT '周期类型：WEEK/MONTH/CUSTOM',
  `period_value`    INT             NULL     DEFAULT NULL   COMMENT '自定义周期天数（仅CUSTOM）',
  `start_date`      DATE            NOT NULL                COMMENT '生效起始日',
  `end_date`        DATE            NULL     DEFAULT NULL   COMMENT '结束日，NULL表示长期有效',
  `amount`          DECIMAL(18,2)   NOT NULL                COMMENT '每期额度',
  `rollover_mode`   VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT '结转模式：NONE/SURPLUS_ONLY/FULL',
  `alert_threshold` DECIMAL(5,2)    NOT NULL DEFAULT 80.00  COMMENT '预警阈值（百分比）',
  `enabled`         TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否启用',
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`         TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_user_enabled` (`user_id`, `enabled`, `deleted`),
  KEY `idx_user_subject` (`user_id`, `subject_id`),
  CONSTRAINT `ck_budget_amount` CHECK (`amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预算定义表';

-- ------------------------------------------------------------
-- 8. 预算周期实例表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `budget_period`;
CREATE TABLE `budget_period` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `budget_id`        BIGINT UNSIGNED NOT NULL                COMMENT '所属预算ID',
  `user_id`          BIGINT UNSIGNED NOT NULL                COMMENT '用户ID（冗余）',
  `period_label`     VARCHAR(16)     NOT NULL                COMMENT '周期标识，如 2025-03',
  `start_date`       DATE            NOT NULL                COMMENT '周期起始日',
  `end_date`         DATE            NOT NULL                COMMENT '周期结束日',
  `base_amount`      DECIMAL(18,2)   NOT NULL                COMMENT '本期基础额度',
  `carry_in`         DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '期初结转额（可为负）',
  `carry_floor`      DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '被下限截断的部分（用于展示累计超支）',
  `available_amount` DECIMAL(18,2)   NOT NULL                COMMENT '本期可用额 = base_amount + carry_in',
  `used_amount`      DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '本期已用额（缓存，由entry重算）',
  `execution_rate`   DECIMAL(7,2)    NULL     DEFAULT NULL   COMMENT '执行率（%），可用额为0时为NULL',
  `settle_state`     VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT '结算状态：OPEN/SETTLED',
  `settled_at`       DATETIME(3)     NULL     DEFAULT NULL   COMMENT '结算时间',
  `dirty_since`      DATE            NULL     DEFAULT NULL   COMMENT '待重算标记：最早的脏记账日期，NULL表示数据最新',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_budget_start` (`budget_id`, `start_date`),
  KEY `idx_user_date` (`user_id`, `start_date`, `end_date`),
  KEY `idx_settle`    (`settle_state`, `end_date`),
  KEY `idx_dirty`     (`dirty_since`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预算周期实例表';

-- ------------------------------------------------------------
-- 9. 预算预警记录表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `budget_alert`;
CREATE TABLE `budget_alert` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
  `budget_id`        BIGINT UNSIGNED NOT NULL                COMMENT '预算ID',
  `budget_period_id` BIGINT UNSIGNED NOT NULL                COMMENT '预算周期ID',
  `alert_level`      VARCHAR(16)     NOT NULL                COMMENT '级别：INFO/WARNING/DANGER',
  `alert_date`       DATE            NOT NULL                COMMENT '预警日期',
  `execution_rate`   DECIMAL(7,2)    NULL     DEFAULT NULL   COMMENT '触发时执行率',
  `used_amount`      DECIMAL(18,2)   NOT NULL                COMMENT '触发时已用额',
  `available_amount` DECIMAL(18,2)   NOT NULL                COMMENT '触发时可用额',
  `message`          VARCHAR(255)    NOT NULL                COMMENT '提示文案',
  `is_read`          TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否已读',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_period_level_date` (`budget_period_id`, `alert_level`, `alert_date`),
  KEY `idx_user_read` (`user_id`, `is_read`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预算预警记录表';

-- ------------------------------------------------------------
-- 10. 对账日志表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `reconcile_log`;
CREATE TABLE `reconcile_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '用户ID，NULL为全局对账',
  `check_type`   VARCHAR(32)     NOT NULL                COMMENT '校验类型：GLOBAL_BALANCE/ACCOUNT_BALANCE/BUDGET_USED',
  `ledger_count` INT             NOT NULL DEFAULT 0      COMMENT '参与校验的凭证数',
  `entry_count`  INT             NOT NULL DEFAULT 0      COMMENT '参与校验的分录数',
  `diff_count`   INT             NOT NULL DEFAULT 0      COMMENT '差异记录数（期望0）',
  `diff_amount`  DECIMAL(18,2)   NOT NULL DEFAULT 0.00   COMMENT '差异金额合计',
  `detail`       JSON            NULL     DEFAULT NULL   COMMENT '差异详情',
  `status`       VARCHAR(16)     NOT NULL                COMMENT '结果：PASSED/FAILED/FIXED',
  `duration_ms`  INT             NOT NULL DEFAULT 0      COMMENT '校验耗时(ms)',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行时间',
  PRIMARY KEY (`id`),
  KEY `idx_type_created` (`check_type`, `created_at`),
  KEY `idx_status`       (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账校验日志表';
