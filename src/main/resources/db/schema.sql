-- ============================================================================
--  秒杀系统数据库结构
--  设计说明：
--  「异步化」并不天然意味着要加表，关键是幂等键是否已经存在——
--        这里直接把业务上本来就有的两个唯一索引当作幂等键使用。
--  compensate_task（待补偿任务表）解决的是另一个问题——
--        「补偿失败只打日志」没有状态，库存丢了无从追溯、无法自动重试。
--        注意它不是幂等表，而是「动作待办表」：幂等靠唯一索引，任务表负责把没做完的动作做完。
--  seckill_outbox（本地消息表）治的是「Redis 预扣之后、消息投出之前」这段没有持久化
--        中间态的窗口。唯一索引那类幂等键解决不了它——它需要的是一份「待办凭据」。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `seckill`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `seckill`;

-- ---------------------------------------------------------------------------
-- 库存表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock`
(
    `id`    BIGINT      NOT NULL,
    `name`  VARCHAR(64) NOT NULL,
    `count` INT         NOT NULL COMMENT '剩余库存',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 订单表
--   uk_order_no    : 单号唯一，由 Snowflake 生成（时间有序，减少索引页分裂）。
--                    同时充当「消费幂等键」：MQ 重复投递时靠它识别重复消息。
--   uk_user_stock  : 「一人一单」的数据库兜底约束。
--                    Redis 侧 SADD 去重是第一道防线，唯一索引是最后一道防线：
--                    即使 Redis 数据丢失 / 主从切换丢写，也不可能出现同一用户重复下单。
--                    它还兼任第二个幂等键，覆盖「同一用户不同单号」的极端重复场景。
--
--   【订单状态机】status：
--     PENDING    —— 已受理、待后台确认（由请求线程在「订单+outbox 同事务」里写入）
--     CONFIRMED  —— 已确认（同步降级路径落库即写它）
--     CANCELLED  —— 已取消（「DB 库存不足」的罕见路径，处置为补库存、留标记）
--   默认值取 CONFIRMED 而非 PENDING，有两个原因：
--     1) 任何尚未接入状态机的写入路径会 fail-safe 到「落库即成功」的旧行为，
--        而不是制造永远停在 PENDING、并阻塞「排空判定」的游离去；
--     2) 存量数据回填场景下，存量订单在语义上都是「已成立」，选 CONFIRMED 一步到位。
--   idx_orders_stock_status 支撑 countPendingByStockId（对账的「在途预扣数」就来自它）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `orders`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `order_no`    VARCHAR(64) NOT NULL COMMENT '业务单号',
    `user_id`     BIGINT      NOT NULL,
    `stock_id`    BIGINT      NOT NULL,
    `status`      VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED' COMMENT 'PENDING / CONFIRMED / CANCELLED',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_user_stock` (`user_id`, `stock_id`),
    KEY `idx_orders_stock_status` (`stock_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 待补偿任务表
--   解决的问题：「回补失败」不能只打一行 ERROR 日志。日志不是状态——
--   没人盯日志的时候库存就永久消失（少卖），事后也查不出丢了哪几笔。
--   落成一张表之后它才有了状态：可被定时任务反复重试、可查询、可人工处理。
--
--   type = ROLLBACK_ALL       : 库存 +1 且摘掉用户已购标记（这次下单根本不该成立）
--   type = RESTORE_STOCK_ONLY : 库存 +1、保留标记（用户其实已经买过，这次预扣多余）
--
--   order_no 对 RESTORE_STOCK_ONLY 是**必须**的：该类型的幂等靠「活动 + 单号」构造的
--   一次性去重键，重试时若换了标识就会绕过幂等保护，把库存补两次（→ 超卖）。
--
--   idx_status_next_retry 让定时任务只扫描「到期待处理」的一段，不会随表增长退化成全表扫描。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `compensate_task`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `stock_id`        BIGINT      NOT NULL,
    `user_id`         BIGINT      NOT NULL,
    `order_no`        VARCHAR(64)          DEFAULT NULL COMMENT '业务单号，RESTORE_STOCK_ONLY 必经',
    `num`             INT         NOT NULL COMMENT '需要归还的数量',
    `type`            VARCHAR(32) NOT NULL COMMENT 'ROLLBACK_ALL / RESTORE_STOCK_ONLY',
    `reason`          VARCHAR(255)         DEFAULT NULL COMMENT '登记原因',
    `status`          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE / FAILED',
    `retry_count`     INT         NOT NULL DEFAULT 0,
    `next_retry_time` DATETIME    NOT NULL COMMENT '下次重试时间（指数退避）',
    `last_error`      VARCHAR(500)         DEFAULT NULL,
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_status_next_retry` (`status`, `next_retry_time`),
    KEY `idx_stock_user` (`stock_id`, `user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 待投递消息表（本地消息表 / Outbox）
--   解决的问题：没有本表时，请求链路是「Redis 预扣 → 同步投递 MQ」，若进程在投递成功前消失，
--   系统里没有任何地方记录过这笔预扣存在过 —— Redis 少一件，Broker 里没有消息，
--   补偿逻辑也没有异常可捕获，库存就永久消失了。
--   把「这笔预扣需要投递一条消息」在返回用户之前写进本表，它就有了独立于请求线程的凭据。
--
--   status = PENDING : 待投递（后台投递器会扫这一批）
--   status = SENT    : 已拿到 Broker 确认，等消费端落库
--   status = FAILED  : 重试次数用尽仍投不出去，Redis 预扣已回补，需人工介入
--
--   uk_outbox_order_no 保证同一单号只有一条待投递记录：
--   投递器没有被设计成「只投一次」，它可能因重复轮询而重投同一条记录，
--   但绝不能因为重复登记而产生两条记录 —— 那会让同一笔下单被消费两次。
--
--   idx_outbox_status_next_retry 让投递器只扫描「待投递且已到期」的一段，
--   不会随表增长退化成全表扫描。
--
--   注意：Outbox 表会持续增长（这是它相对「同步投递」额外付出的运维成本），
--   因此必须配套归档清理，见 OutboxDispatchTask#purge。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `seckill_outbox`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `order_no`        VARCHAR(64) NOT NULL COMMENT '业务单号，请求线程生成',
    `user_id`         BIGINT      NOT NULL,
    `stock_id`        BIGINT      NOT NULL,
    `num`             INT         NOT NULL COMMENT '下单件数',
    `status`          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED',
    `retry_count`     INT         NOT NULL DEFAULT 0,
    `next_retry_time` DATETIME    NOT NULL COMMENT '下次投递时间（指数退避）',
    `last_error`      VARCHAR(500)         DEFAULT NULL,
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_order_no` (`order_no`),
    KEY `idx_outbox_status_next_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 初始化商品（已存在则只更新名称，不覆盖库存）
-- ---------------------------------------------------------------------------
INSERT INTO `stock` (`id`, `name`, `count`)
VALUES (1, '秒杀商品', 1000) ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- ============================================================================
--  增量脚本：建表时没带 uk_user_stock 索引的老库需要手工执行下面这一句，
--  因为 CREATE TABLE IF NOT EXISTS 不会给已存在的表补索引。
-- ============================================================================
-- ALTER TABLE `orders` ADD UNIQUE KEY `uk_user_stock` (`user_id`, `stock_id`);

-- ============================================================================
--  增量脚本：给已存在的 orders 表补订单状态机。
--  默认值 CONFIRMED 会把全部存量行回填为「已成立」——这正是它们的事实语义，
--  因此无需再补 UPDATE。列和索引要分开两条 ALTER（MySQL 8.0 支持 comma 语法，
--  但分开写便于按需执行、失败时定位）。
-- ============================================================================
-- ALTER TABLE `orders`
--     ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED'
--     COMMENT 'PENDING / CONFIRMED / CANCELLED' AFTER `stock_id`;
-- ALTER TABLE `orders`
--     ADD KEY `idx_orders_stock_status` (`stock_id`, `status`);
