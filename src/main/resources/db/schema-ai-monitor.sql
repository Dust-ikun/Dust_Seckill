-- ============================================================================
--  AI 监控 Agent —— 表结构（SPEC 第 14 节）
--
--  【为什么单独一个文件，而不是追加进 schema.sql】
--  两张表的生命周期与业务表完全不同：
--    - stock / orders / compensate_task / seckill_outbox 是**交易事实**，
--      一旦丢失就是资金与库存对不上，必须随业务版本一起演进、一起备份；
--    - 下面这三张是**诊断记录**，丢一轮最多损失一次排障历史，不影响正确性。
--  混在一个文件里，会让「只想加监控能力、不想碰业务表结构」这件事变成
--  一次有风险的 schema.sql 改动。分开之后，监控能力可以单独启用与回滚。
--
--  【命名与 SPEC 的一致性】三张表名、列名、类型均严格按 SPEC 第 14 节实现，
--  只在两处做了显式补强（都在下面用 ★ 标出），原因随行注释。
-- ============================================================================

USE `seckill`;

-- ---------------------------------------------------------------------------
--  14.1 ai_diagnosis_task —— 诊断任务（一次 Incident 对应一行）
--
--  它承担的核心职责是**去重**：同一次事故可能触发多条 Alert
--  （P99 高 + 错误率高 + 超时高），SPEC 第 16 节要求把它们聚合为一次诊断，
--  而不是启动三个 Agent。聚合的落点就是这里的 incident_id 唯一键。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_diagnosis_task`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `incident_id`  VARCHAR(64)  NOT NULL COMMENT '事故编号，聚合键；一次事故只有一个',
    `service_name` VARCHAR(128) NOT NULL COMMENT '服务名，来自 Alertmanager 的 service 标签',
    `alert_type`   VARCHAR(64)  NOT NULL COMMENT '异常类型：LATENCY_HIGH / ERROR_RATE_HIGH / ...',
    `severity`     VARCHAR(32)  NOT NULL COMMENT 'CRITICAL / HIGH / WARNING',
    `status`       VARCHAR(32)  NOT NULL COMMENT 'CREATED/RUNNING/COLLECTING_EVIDENCE/ANALYZING/COMPLETED/FAILED/INSUFFICIENT_EVIDENCE',
    `start_time`   DATETIME              DEFAULT NULL COMMENT '异常开始时间（来自 Alert 的 startsAt）',
    `end_time`     DATETIME              DEFAULT NULL COMMENT '异常结束时间（Alertmanager 的 resolved 通知）',

    -- ★ 补强一：alert_id 与 alert_count
    --
    -- SPEC 的建表语句里没有这两列，但 SPEC 第 7 节明确要求 AlertEvent 带
    -- alertId，第 16 节又要求「3 Alert → 1 Diagnosis Task」。这两条要求
    -- 放在一起就产生了一个必须回答的问题：**被合并掉的那两条 Alert 去哪了？**
    -- 若只留一个 incident_id，事后就无法回答「这次诊断是由哪几条告警触发的」——
    -- 而「为什么 Agent 会认为这是同一个事故」恰恰是排障时第一个要问的问题
    -- （聚合窗口设错会让不相关的事故被合并，Agent 于是查错方向）。
    -- 因此：alert_id 记首条触发的告警，alert_count 记累计合并了几条。
    `alert_id`     VARCHAR(64)           DEFAULT NULL COMMENT '首条触发本任务的 AlertEvent.alertId',
    `alert_count`  INT          NOT NULL DEFAULT 1 COMMENT '被聚合进本任务的告警条数',

    `created_at`   DATETIME     NOT NULL,
    `updated_at`   DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_incident_id` (`incident_id`),
    -- 支撑 /api/ai/diagnosis/history 的翻页查询（按时间倒序）
    KEY `idx_task_created_at` (`created_at`),
    -- 支撑「同一服务 + 同一异常类型 + 时间窗口」的聚合查询（SPEC 第 16 节）
    KEY `idx_task_aggregate` (`service_name`, `alert_type`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
--  14.2 ai_diagnosis_result —— 结构化诊断结果（SPEC 第 11 节的 JSON）
--
--  与 task 是 1 : N 的关系，而不是 1 : 1 —— 这是刻意的：
--  同一次事故在「证据不足」之后可能被重新诊断（例如 30 分钟后补齐了数据），
--  两次结论都应当保留。若做成 1:1 并覆盖，就会丢掉「Agent 第一次为什么
--  没判断出来」这条对调 prompt 最有价值的信息。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_diagnosis_result`
(
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`     BIGINT        NOT NULL COMMENT 'ai_diagnosis_task.id',
    `severity`    VARCHAR(32)            DEFAULT NULL,
    `root_cause`  TEXT                   DEFAULT NULL COMMENT '根因（自然语言，SPEC 第 11 节的 root_cause）',

    -- ★ 补强二：confidence 用 DECIMAL(5,4)，范围为 0.0000 ~ 9.9999
    --
    -- SPEC 原文就是 DECIMAL(5,4)，这里保持不动，但必须记下它的边界：
    -- 它表达不了 1.0 以上的值，而 LLM 完全可能返回 1.0 或 0.95 之外的数
    -- （例如 0.923 到 95 这类把百分数当小数的输出）。
    -- 写入时的正确做法是在应用侧就把 confidence 规整到 [0,1]，
    -- 而不是依赖列类型兜底 —— MySQL 在非严格模式下会把 95 截成 9.9999，
    -- 那是一个**看起来合理但完全错误**的值，比报错危险得多。
    -- （MySQL 8 默认启用严格模式，会直接报错而不是截断，因此这里的风险
    --   主要是「从别处导入数据」或显式关了严格模式的场景。）
    `confidence`  DECIMAL(5, 4)          DEFAULT NULL COMMENT '置信度，必须落在 0~1',

    `impact`      JSON                   DEFAULT NULL COMMENT '影响范围：{success_rate_change, affected_requests}',
    `evidence`    JSON                   DEFAULT NULL COMMENT '证据数组，至少两条独立证据',
    `suggestion`  JSON                   DEFAULT NULL COMMENT '处理建议数组（低风险、只读建议）',
    `raw_result`  JSON                   DEFAULT NULL COMMENT 'LLM 原始返回，用于复盘 Schema 校验失败的原因',
    `created_at`  DATETIME      NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
--  14.3 ai_tool_execution —— Tool Calling 轨迹
--
--  SPEC 第 14.3 节的原话：这张表「是 Agent 可解释性的关键，用于完整保存
--  Tool Calling Trace」。这里的三个字段各自对应一种「不可解释」的失败模式：
--    - arguments：看不到入参 → 无法判断 Agent 当时的假设是什么；
--    - result   ：看不到返回 → 无法验证结论有没有证据支撑（即是否在编）；
--    - status   ：只记成功 → 因工具报错而转向的推理路径会消失在轨迹里。
--  因此这三列都不是"顺手记一下"，缺任何一列都会让轨迹失去可复现性。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_tool_execution`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`           BIGINT       NOT NULL COMMENT 'ai_diagnosis_task.id',
    `tool_name`         VARCHAR(128) NOT NULL COMMENT 'query_metric / search_logs / get_slow_sql / get_queue_lag / ...',
    `arguments`         JSON                  DEFAULT NULL COMMENT 'Agent 传入的参数（经校验后的实际值，不是原始串）',
    `result`            JSON                  DEFAULT NULL COMMENT '工具返回（已截断到大小上限）',
    `status`            VARCHAR(32)           DEFAULT NULL COMMENT 'SUCCESS / FAILED / REJECTED（白名单或参数校验拦截）',
    `execution_time_ms` BIGINT                DEFAULT NULL COMMENT '单次调用耗时，对应 SPEC 第 25 节「Tool 单次调用 < 1s」',

    -- ★ 补强三：sequence_no 与 error_message
    --
    -- sequence_no：轨迹的**顺序**是推理链的一部分，而 created_at 的 DATETIME(0)
    -- 精度只有秒 —— 一次诊断里 Agent 完全可能在同一秒内连续调用两个工具。
    -- 只按时间排序会得到不确定的顺序，「Agent 先查了 DB 还是先查了日志」
    -- 这个关键问题就答不上来。
    --
    -- error_message：status=FAILED 时，失败原因必须留下。
    -- 「工具失败了」与「工具失败了因为超时」在排查时是完全不同的两件事：
    -- 前者可能是参数错，后者是 Prometheus 太慢。
    `sequence_no`       INT          NOT NULL DEFAULT 0 COMMENT '同一 task 内的调用序号，从 1 开始',
    `error_message`     VARCHAR(1000)         DEFAULT NULL COMMENT 'status != SUCCESS 时的原因',

    `created_at`        DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    -- 按「任务 + 序号」查轨迹，正是 GET /api/ai/diagnosis/{incidentId}/tools 的查询形态
    KEY `idx_tool_task_seq` (`task_id`, `sequence_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================================
--  增量脚本：老库（已存在业务表但没有这三张表）直接执行本文件即可，
--  全部使用 CREATE TABLE IF NOT EXISTS，重复执行安全。
--
--  验证：
--    docker compose exec mysql mysql -uroot -p -Dseckill -e "SHOW TABLES LIKE 'ai_%';"
--  应当看到三行：ai_diagnosis_result / ai_diagnosis_task / ai_tool_execution
-- ============================================================================
