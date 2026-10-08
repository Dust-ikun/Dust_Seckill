# Dust-ikun 秒杀系统（seckill）

一个按阶段演进的高并发秒杀系统，当前状态为**阶段 5-A：Redis 预扣 + 预订单状态机 + 本地消息表（Outbox）+ RocketMQ 异步落库**。

核心设计原则：**宁可少卖，绝不超卖**——所有失败路径的处置顺序、补偿与回补策略均按此裁决。

> **接手项目先读 [`docs/秒杀链路文档.md`](docs/秒杀链路文档.md)**：把「一次下单经过哪些环节、每个环节失败会怎样、
> 漏掉的东西谁负责发现」讲清楚，含失败/补偿矩阵、崩溃点清单、对账判据、运维手册与常用排查 SQL。
> 本 README 只做概览与速查，链路细节以那份文档为准。

---

## 1. 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Java | 21 | 运行时 |
| Spring Boot | 4.0.8 | Web 框架（注意：RocketMQ 官方 starter 最高适配 Boot 3.x，故使用原生 `rocketmq-client` 5.3.2 + 手写配置类管理生命周期） |
| MySQL | 8.x | 订单 / 库存 / 补偿任务 / Outbox 持久化 |
| Redis | 6+（Lettuce） | 库存预扣、一人一单去重、Lua 脚本原子扣减 |
| RocketMQ | 5.3.2（客户端） | 订单落库消息的异步投递 |
| MyBatis | 4.0.1（starter） | 数据访问层 |
| Micrometer + Prometheus | Boot 4 自带 | 指标唯一出口（`/actuator/prometheus`，管理端口 9091） |
| Lombok | — | 样板代码 |

## 2. 整体架构与请求链路

```
请求线程（RT 中无热点行写：stock 条件 UPDATE 已挪到消费线程）
 ├─ 1. Redis + Lua 原子预扣        ← 库存校验 / 扣减 / 一人一单去重，内存中一次完成
 │       └─ Redis 不可用 → 探测是否已生效，降级为同步落库（保证活动可用）
 ├─ 2. 建预订单 + 写待投递凭据      ← 同一个本地事务：orders(PENDING) + seckill_outbox
 └─ 3. 立即返回「已受理」(QUEUED + orderNo)

后台投递器（@Scheduled，独立于请求线程）
 └─ 4. 扫出 PENDING 凭据 → 批量投 MQ → 标记 SENT；失败指数退避重试，
         重试耗尽 → 同一事务里「标记 FAILED + 登记归还待办（CANCEL_ORDER）」
                  → 立刻尝试执行一次（失败由待补偿任务按退避做完）

消费线程（独立线程池）
 └─ 5. 幂等确认订单 + 条件扣库存（同一事务）：
         UPDATE orders SET status='CONFIRMED' WHERE order_no=? AND status='PENDING'
         的影响行数即扣减许可证；失败重试，重试耗尽 → 取消订单 + 归还预扣
```

关键设计点：

- **预订单状态机**：`orders.status ∈ {PENDING, CONFIRMED, CANCELLED}`。接口返回时数据库里已有一条 PENDING 预订单与一条待投递凭据（同一事务），「订单与消息同事务」字面成立；订单状态由消费线程推进。
- **Outbox 可靠投递**：消息先落库再投递，Broker 不可用不再等于用户丢单；「已受理未投出」的规模可由 `COUNT(PENDING)` 直接查询，不依赖 JVM 内存计数。
- **幂等双保险**：`orders.uk_order_no`（单号唯一，MQ 重投吸收）+ `orders.uk_user_stock`（一人一单的数据库兜底，Redis SADD 去重之外的最后防线）。
- **查单判据以订单状态优先**：CONFIRMED→SUCCESS / PENDING→QUEUED / CANCELLED→FAILED；订单未落库时才退回用 Redis 预扣标记判断（outbox 状态不能反推预扣状态，不参与判定）。
- **失败处置顺序**：放弃投递时**先写 FAILED（与归还待办同一事务）、再由待办归还 Redis**——顺序颠倒会出现「库存已还 + 订单又成立」的超卖；先落库的最坏情况只是少卖，而且进程在归还前消失也追得回来（旧写法把归还放在事务之外的内存调用里，崩在中间就永久少卖且对账也看不出来）。
- **对账兜底**：Redis 与数据库的真实状态比对，覆盖补偿逻辑照不到的三条路径（降级写库、Redis 假阴性、投递已放弃但订单仍停在 PENDING）。默认只报告不改数据；`repair=true` 时也不会在有悬空归还义务的情况下贸然校准库存。
- **在途口径**：`可信在途 = PENDING 预订单 − 已放弃但仍为 PENDING 的预订单`。后半部分是关键：它既不会被确认、也不会被取消，算进在途就会给等式两边同时减一，让一笔真正丢失的预扣读成「一致」。

## 3. 目录结构

```
src/main/java/com/dustikun/seckill/
├── Controller/          # SeckillController（秒杀主接口）、StockControl
├── Service/             # 核心业务
│   ├── SeckillService              # 主链路：预扣 → 预订单+Outbox → 返回
│   ├── SeckillPersistenceService   # orders(PENDING)+outbox 同事务写入
│   ├── StockCacheService           # Redis 库存缓存 / Lua 脚本调用
│   ├── OutboxService               # Outbox 登记/投递/退避/放弃/归档
│   ├── OutboxAbandonService        # 放弃投递的落库点：FAILED + 归还待办同事务
│   ├── PreDeductCompensator        # 统一的「回滚一次 Redis 预扣」动作（三态结局）
│   ├── CompensateTaskService       # 待补偿任务登记/重试/立即执行
│   └── StockReconcileService       # 库存对账与修复
├── Mq/                  # SeckillMessage / SeckillMessageProducer / SeckillOrderConsumer
├── Mapper/              # MyBatis Mapper（含 BenchStockMapper）
├── entity/              # Stock / Order / OutboxMessage / CompensateTask
├── Config/              # RocketMq / Redis / Outbox / Scheduling 配置
├── Task/                # OutboxDispatchTask（投递器+归档）、MaintenanceTask（补偿+对账）
├── Metrics/             # SeckillMetrics（唯一指标出口）+ SeckillMetricsRefresher（Gauge 缓存刷新）
├── Common/              # Result 统一响应 / 异常 / 常量 / 雪花单号 / 退避策略 / 字符串工具
└── Bench/               # 压测对照端点（cond vs opt，默认关闭）

src/main/resources/
├── lua/                 # seckill_deduct / seckill_rollback / seckill_restore_stock / seckill_sync_stock
├── db/schema.sql        # 建库建表脚本（含增量 ALTER 说明）
└── application.yaml     # 主配置（application-example.yaml 为脱敏示例）

docs/
└── 秒杀链路文档.md       # 链路 / 状态机 / 失败补偿矩阵 / 崩溃点 / 对账判据 / 运维手册（接手先读）
```

## 4. 数据库表

| 表 | 职责 |
|---|---|
| `stock` | 库存（`count` 为数据库侧剩余量，Redis 余量以 `seckill:stock:{id}` 为准） |
| `orders` | 订单。`uk_order_no` / `uk_user_stock` 两个唯一索引兼任幂等键；`idx_orders_stock_status` 支撑按活动统计在途 PENDING |
| `compensate_task` | 待补偿任务（「动作待办表」，非幂等表；`ROLLBACK_ALL` / `RESTORE_STOCK_ONLY` / `CANCEL_ORDER`；指数退避重试，耗尽标 FAILED 等人工介入。FAILED 也算「未了结的归还义务」，对账据此不自动校准库存） |
| `seckill_outbox` | 本地消息表（PENDING / SENT / FAILED；状态流转只允许 PENDING → {SENT, FAILED}，所有 UPDATE 都带 `status='PENDING'` 守卫；`uk_outbox_order_no` 保证一单一凭据；投递器按 `idx_outbox_status_next_retry` 只扫到期段） |

初始化：`mysql -uroot -p seckill < src/main/resources/db/schema.sql`（内含商品 id=1 的初始化数据，以及老库迁移用的增量 ALTER 注释）。

## 5. HTTP 接口

### 业务接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/seckill?userId=&stockId=` | 下单。返回「已受理」+ `orderNo`，需轮询查最终结果 |
| GET | `/seckill/order/status?orderNo=&userId=&stockId=` | 查询最终结果（SUCCESS / QUEUED / FAILED） |
| POST | `/seckill/preheat?stockId=` | 活动前把数据库库存前置到 Redis（必须，否则下单返回 1003）；也是降级后重新对齐两边的手段 |
| GET | `/seckill/remain?stockId=` | 查询 Redis 侧真实可售余量 |
| DELETE | `/seckill/cache?stockId=` | 活动结束后清理 Redis 活动缓存 |
| GET | `/stock/{id}` | 查询**数据库侧**库存（账本，非可售余量）。与 `/seckill/remain` 的区别：后者才是当下真实可售余量，两者在活动期间不相等是正常的中间状态 |

### 运维接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/seckill/metrics` | 运行态指标：请求侧 / 待投递侧（outbox\_*）/ MQ 投递侧 / 消费侧 / 补偿侧计数 |
| GET | `/seckill/reconcile?stockId=&expectedInFlight=&repair=` | 库存对账。`expectedInFlight` 默认 `-1` = 自动模式：在途数按「数据库库存 − 可信在途」从库中直接算出（可信在途 = PENDING 预订单 − 已放弃未了结 − 陈旧未了结），无需人工判断；传 `>=0` 可显式覆盖。默认只报告；`repair=true` 会（a）为「已放弃 / 陈旧未了结」的预订单补登记归还待办、（b）在没有未了结归还义务且写入前值比对通过时，把 Redis 校准为「数据库 − 可信在途」、（c）补齐缺失的已购标记 |
| GET | `/seckill/count` | 已受理下单数 |

### 压测对照端点（默认关闭）

`BenchController`（`/bench/reset`、`/bench/deduct`、`/bench/state`）仅在 `seckill.bench.enabled=true` 时装配，用于同一行热点库存下「条件 UPDATE vs 乐观锁重试」两种并发模型的对照压测，不参与业务链路。

## 6. 关键配置（`application.yaml` → `seckill.*`）

| 配置块 | 关键项 | 说明 |
|---|---|---|
| `mq` | `enabled` / `name-server` / `topic` / `tag` / `send-timeout-ms` / `max-reconsume-times` / `consume-thread-min/max` | 置 `false` 时不创建任何 MQ 客户端，链路退化为「Redis 预扣 + 同步落库」，便于本机无 Broker 时调试。消费线程数不要超过 Hikari `maximum-pool-size` |
| `outbox` | `enabled`（默认 true）/ `dispatch-interval-ms=1000` / `batch-size=500` / `max-retry=15` / `first-retry-delay-seconds=2` / `purge-interval-ms=600000` / `retention-hours=24` / `purge-batch-size=1000` | 置 `false` 回到同步投递行为。投递为「先批量、失败退回逐条」；重试指数退避、封顶 5 分钟；归档只清超期 SENT，按批删除 |
| `maintenance` | `enabled` / `compensate.*` / `reconcile.*`（含 `stale-pending-minutes=90`） | 待补偿任务轮询与库存对账两个定时任务。对账默认 `auto-repair=false`（只检测告警 + 指标计数）；`stale-pending-minutes` 是「陈旧未了结」的判定阈值，同时决定排空门控的封顶时长，必须大于最坏链路时延（默认配置下投递退避 ≈19 分钟） |
| `worker-id` | `0` | Snowflake 机器位（0~1023），多实例部署必须各不相同，否则单号重复 |

注意：`seckill.maintenance.enabled=false` 不会连带关掉投递器——`@EnableScheduling` 无条件开启，各任务用自己的 `@ConditionalOnProperty` 独立注册。

## 7. 快速启动

### 前置组件

| 组件 | 端口 |
|---|---|
| MySQL 8.x | 3306 |
| Redis | 6379 |
| RocketMQ NameServer | 9876 |
| RocketMQ Broker | 10911 / 10909 |

### 步骤

```powershell
# 1. 建库建表
mysql -uroot -p < src/main/resources/db/schema.sql

# 2. 修改 src/main/resources/application.yaml 中的数据库密码等连接信息

# 3. 构建（Maven，JDK 21）
mvn package -DskipTests

# 4. 启动（需已启动 MySQL / Redis / RocketMQ）
java -jar target/seckill-0.0.1-SNAPSHOT.jar

# 5. 预热并下单
curl -X POST "http://localhost:8081/seckill/preheat?stockId=1"
curl -X POST "http://localhost:8081/seckill?userId=1001&stockId=1"
# 拿到 orderNo 后轮询
curl "http://localhost:8081/seckill/order/status?orderNo=<orderNo>&userId=1001&stockId=1"
```

应用端口默认 `8081`。常用降级开关：`--seckill.mq.enabled=false`（无 Broker 调试）、`--seckill.outbox.enabled=false`（回到同步投递对比）。

## 8. 测试

```powershell
mvn test
```

测试覆盖（36 项）：

- `SeckillConcurrencyTest` — 并发抢购下的防超卖与一人一单
- `SeckillOutboxTest` — Outbox 登记 / 退避重试 / 耗尽放弃（FAILED 与归还待办同事务、待办插不进则两者都不成立）/ MQ 未启用时不动记录 / 归档只清 SENT
- `SeckillCompensationTest` — 补偿路径与查单终态
- `SeckillReviewFixTest` — 评审修复项回归（含「投递已放弃但仍为 PENDING」必须被对账报出来且不许对账自己改库存）
- `SeckillEntityMappingTest` — snake_case 列映射回归锁（防 `map-underscore-to-camel-case` 配置失效导致静默 null）
- `SeckillOrderStatusTest` — 订单状态机流转

## 9. 已知取舍与后续方向

如实记录的固有取舍（均有对账兜底）：

1. **INSERT 之前仍有窗口**：进程在「Redis 已扣、预订单未写」之间消失，该预扣无持久化记录，且对账也报不出来（崩溃前后它读到的输入完全一致）。窗口已缩至一次本地事务提交，彻底消除需预扣本身可恢复，属另一层取舍。
2. **对账结果不落库**：发现不一致会写 ERROR 日志 + 按结论分标签的计数器（`seckill_reconcile_findings_total`），但没有历史报告表，回溯要靠日志系统。
3. **消费完成不回写 outbox**：「已投出未消费」只能靠单实例进程内计数判断
   （`MaintenanceTask#pipelineDrained` 用 `mqSentCount() - resolvedCount()` 估算）；
   该门控已被「陈旧阈值」封顶，因此卡死的链路不会被它永久挡住。
4. **指标的两个残余局限**（已迁 Micrometer，这两条是迁移后剩下的）：
   - Gauge（`outbox.pending` / `outbox.failed` / `compensate.pending`）走本地缓存 + 30s 定时刷新，
     最多滞后一个刷新周期——对「欠账规模」这类慢变量够用，但不适合做秒级告警；
   - `/seckill/metrics` 里的 `inFlightEstimate` 仍是**单实例**视角的估算（`mqSent - resolved`）。
     对账不受影响：它用的可信在途是数据库事实（`PENDING` 减去「已放弃」「陈旧未了结」两类）。
5. **放弃投递后的归还依赖待补偿任务**：`FAILED` 与归还待办同事务落库，正常链路上不会漏；
   但待办自身重试耗尽会标成 `FAILED`，那部分需要人工介入（对账会把它算作「未了结的归还义务」，
   因此不会贸然校准库存，但也不会替人做决定）。
6. **多实例重复投递**：不做抢占（SKIP LOCKED / claim），重复投递由消费端 `uk_order_no` 幂等吸收，换来零并发控制复杂度；如需消除只需替换 `OutboxMessageMapper.selectPending` 一处。
7. **陈旧判定本质是时间推断**：阈值再保守也可能误判（例如大促期间消费积压超过阈值），因此它默认只告警；
   要自动补登记归还待办必须显式打开 `auto-repair`。

后续方向：对账结果落库 + 告警、活动起止时间自动化、消费完成回写 outbox（全链路台账 +
让 `pipelineDrained` 跨实例准确）、评估 Redis Stream 版激进方案。
