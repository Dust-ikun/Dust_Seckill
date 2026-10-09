# 金融交易系统 AI 智能监控 Agent 升级改造 Spec

**文档版本：** v1.0  
**项目类型：** 既有金融交易后端系统 AI 能力升级  
**目标：** 在不破坏现有 Redis → MySQL → Outbox → MQ → Consumer 核心链路的前提下，引入可观测性与 AI Monitor Agent，实现异常发现、智能诊断、根因分析、证据聚合和处理建议。

---

## 1. 项目背景

现有系统已经具备核心业务能力：

```text
Client
  ↓
Spring Boot
  ↓
Redis
  ↓
MySQL
  ↓
Outbox
  ↓
MQ
  ↓
Consumer
  ↓
业务处理
```

系统已经解决了核心业务处理、异步解耦、幂等、重试、补偿、最终一致性等问题，但传统监控主要依赖固定阈值和人工排查：

```text
指标异常
  ↓
告警
  ↓
人工查看 Grafana
  ↓
人工查日志
  ↓
人工查数据库
  ↓
人工查 MQ
  ↓
人工判断根因
```

本次升级希望将上述流程智能化：

```text
指标/日志/业务异常
      ↓
异常事件
      ↓
AI Monitor Agent
      ↓
自主选择监控工具
      ↓
Metrics / Logs / DB / MQ / Business
      ↓
LLM 综合推理
      ↓
根因 + 证据 + 影响范围 + 修复建议
      ↓
Dashboard / 告警通知
```

本项目的核心不是简单调用 LLM，而是让 Agent 基于真实系统运行数据进行多步骤故障诊断。

---

## 2. 改造目标

### 2.1 核心目标

1. 保持现有业务系统和数据链路不变。
2. 增加统一可观测性指标采集。
3. 建立统一异常事件模型。
4. 构建 AI Monitor Agent。
5. 通过 Tool Calling 让 Agent 查询 Metrics、Logs、DB、MQ 和业务指标。
6. 输出结构化诊断结果。
7. 支持故障注入和诊断准确性验证。
8. 对 AI 能力进行安全隔离，默认只读，禁止高风险自动操作。

### 2.2 非目标

本版本暂不实现：

- Agent 直接修改生产数据库。
- Agent 自动执行高风险运维操作。
- 完整 AIOps 自动修复平台。
- 大规模多 Agent 集群。
- 复杂 RAG 知识库。

RAG 和自动修复作为后续可选扩展，不属于 MVP 核心范围。

---

## 3. 总体架构

```text
                           ┌───────────────┐
                           │    Client     │
                           └───────┬───────┘
                                   ↓
                           ┌───────────────┐
                           │ Spring Boot   │
                           │ 业务系统       │
                           └───────┬───────┘
                                   │
          ┌────────────────────────┼────────────────────────┐
          ↓                        ↓                        ↓
       Redis                    MySQL                      MQ
          │                        │                        │
          └────────────────────────┼────────────────────────┘
                                   │
                         ┌─────────┴─────────┐
                         │  Application       │
                         │  Metrics / Logs    │
                         └─────────┬─────────┘
                                   ↓
                    ┌─────────────────────────────┐
                    │ Prometheus / Grafana         │
                    │ Metrics / Dashboard / Alert  │
                    └──────────────┬──────────────┘
                                   ↓
                             Alert Event
                                   ↓
                    ┌─────────────────────────────┐
                    │     AI Monitor Agent        │
                    └──────────────┬──────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ↓                    ↓                    ↓
       Metrics Tool          Logs Tool             DB Tool
              │                    │                    │
              ↓                    ↓                    ↓
        Prometheus               Logs                 MySQL
                                   │
                        ┌──────────┴──────────┐
                        ↓                     ↓
                    MQ Tool               Business Tool
                        │                     │
                        ↓                     ↓
                       MQ                 MySQL/Redis
                                   │
                                   ↓
                             LLM Reasoning
                                   ↓
                    ┌──────────────┴──────────────┐
                    ↓                             ↓
               Diagnosis                    Suggested Action
                    ↓                             ↓
             Alert / Dashboard             Human Review
```

---

## 4. 系统模块设计

建议 AI 升级部分采用独立模块，不侵入核心交易代码。

```text
ai-monitor/
├── monitor-core       # 领域模型、事件、任务状态
├── monitor-agent      # Agent 编排、Prompt、Tool Calling
├── monitor-tool       # Metrics/Logs/DB/MQ/Business Tools
├── monitor-analyzer   # 诊断结果解析与规则校验
├── monitor-alert      # 告警接入、通知、降噪
└── monitor-storage    # 诊断任务、Tool Trace、结果持久化
```

如果当前项目不适合拆成独立 Maven Module，也可以先按 package 组织：

```text
com.xxx.monitor
├── core
├── agent
├── tool
├── analyzer
├── alert
└── repository
```

---

## 5. 核心业务系统接入原则

### 5.1 不改变现有核心交易流程

原流程保持：

```text
Redis → MySQL → Outbox → MQ → Consumer
```

AI 监控系统通过旁路方式读取监控信息：

```text
业务系统
  ├── 正常业务链路
  │     └── Redis → MySQL → Outbox → MQ
  │
  └── 可观测性旁路
        ├── Metrics
        ├── Logs
        └── Trace
```

### 5.2 AI 系统与核心交易系统解耦

原则：

> 即使 LLM 服务不可用，也不能影响正常交易。

因此：

```text
LLM 挂掉
   ↓
交易系统正常运行
   ↓
普通 Prometheus 告警仍然有效
```

AI Agent 只能作为增强能力，而不能成为业务系统单点依赖。

---

## 6. 可观测性设计

## 6.1 Metrics

建议使用 Micrometer 暴露指标，Prometheus 负责采集。

### HTTP 指标

```text
http_request_total
http_request_error_total
http_request_duration_seconds
```

重点关注：

- QPS
- P95
- P99
- Error Rate
- Timeout Rate

### Redis 指标

```text
redis_operation_total
redis_operation_error_total
redis_latency
redis_connection_pool
```

### MySQL 指标

```text
db_query_total
db_query_latency
db_connection_pool
slow_query_count
```

### MQ 指标

```text
mq_produce_total
mq_consume_total
mq_consume_error_total
mq_consume_latency
mq_lag
```

### Outbox 指标

```text
outbox_pending_count
outbox_publish_success_total
outbox_publish_fail_total
outbox_retry_total
```

### 业务指标

```text
order_total
order_success_total
order_failure_total
order_success_rate
inventory_consistency_error
```

---

## 6.2 Logs

日志至少包含：

```text
traceId
requestId
serviceName
operation
userId / businessId（脱敏）
errorCode
exception
timestamp
```

示例：

```text
2026-10-08 20:00:01
service=order-service
traceId=xxx
operation=createOrder
errorCode=DB_TIMEOUT
message=MySQL query timeout
```

---

## 7. 异常事件模型

监控系统检测到异常后，统一转化为 `AlertEvent`。

### 7.1 AlertEvent

```java
public class AlertEvent {
    private String alertId;
    private String serviceName;
    private String metricName;
    private String alertType;
    private String severity;
    private Double currentValue;
    private Double threshold;
    private Long timestamp;
    private String description;
}
```

### 7.2 示例

```json
{
  "alertId": "ALT-20261008-001",
  "serviceName": "order-service",
  "metricName": "http_p99",
  "alertType": "LATENCY_HIGH",
  "severity": "CRITICAL",
  "currentValue": 2.1,
  "threshold": 1.0,
  "timestamp": 1791460800,
  "description": "订单创建接口 P99 超过阈值"
}
```

---

## 8. AI Monitor Agent 设计

## 8.1 Agent 职责

Agent 负责：

1. 理解异常事件。
2. 判断需要哪些证据。
3. 自主选择 Tool。
4. 多轮调用 Tool。
5. 综合多个数据源。
6. 判断最可能根因。
7. 评估影响范围。
8. 给出处理建议。
9. 输出结构化诊断结果。

---

## 8.2 Agent 工作模式

采用 Tool Calling / ReAct 思路：

```text
Alert Event
    ↓
Agent
    ↓
分析当前异常
    ↓
选择 Tool
    ↓
Tool Result
    ↓
继续判断
    ↓
选择下一个 Tool
    ↓
Tool Result
    ↓
满足证据条件
    ↓
生成 Diagnosis
```

Agent 不强制执行固定顺序，而是允许基于当前异常动态选择工具。

---

## 9. Agent Tool 设计

MVP 实现 5 个 Tool。

## 9.1 Metrics Tool

用途：查询系统与业务监控指标。

```text
query_metric(
    metric,
    service,
    start_time,
    end_time
)
```

返回示例：

```json
{
  "metric": "http_request_duration_p99",
  "service": "order-service",
  "avg": 0.31,
  "p99": 2.10,
  "max": 3.20
}
```

---

## 9.2 Logs Tool

```text
search_logs(
    service,
    keyword,
    start_time,
    end_time,
    limit
)
```

返回：

```json
{
  "count": 321,
  "samples": [
    "MySQL query timeout",
    "connection pool exhausted"
  ]
}
```

---

## 9.3 DB Tool

默认只读。

```text
get_slow_sql()
get_db_connections()
get_transaction_status()
get_business_statistics()
```

禁止 Agent 直接执行：

```text
UPDATE
DELETE
DROP
ALTER
TRUNCATE
```

也不允许自由生成 SQL 后直接执行。

---

## 9.4 MQ Tool

```text
get_queue_lag(topic)
get_consumer_status(topic)
get_message_failure_rate(topic)
```

示例：

```json
{
  "topic": "order-event",
  "lag": 32800,
  "producerRate": 4300,
  "consumerRate": 1200,
  "failureRate": 0.13
}
```

---

## 9.5 Business Tool

该 Tool 用于体现业务层感知能力。

```text
get_order_statistics()
get_order_success_rate()
get_inventory_consistency()
get_outbox_pending_count()
```

示例：

```json
{
  "orderCount": 12000,
  "successRate": 96.7,
  "failureRate": 3.3
}
```

---

## 10. Agent Prompt 设计

建议使用固定 System Prompt + 动态 Alert Context。

### System Prompt

```text
你是金融交易系统智能运维 Agent。

你的任务：
1. 分析系统异常。
2. 获取足够证据。
3. 判断最可能的根因。
4. 判断影响范围。
5. 给出低风险处理建议。

诊断规则：
1. 不允许在没有证据的情况下直接下结论。
2. 优先查询与当前异常直接相关的指标。
3. 根因判断至少需要两个独立证据。
4. 如果证据不足，必须明确说明不确定性。
5. DB、Redis、MQ Tool 默认只读。
6. 禁止执行高风险写操作。
7. 不得伪造不存在的指标、日志或查询结果。
8. 最终输出必须包含根因、证据、影响和建议。
```

### 动态上下文

```text
当前服务：order-service
异常类型：LATENCY_HIGH
指标：http_request_duration_p99
当前值：2.1s
阈值：1.0s
开始时间：2026-10-08 20:00:00
```

---

## 11. Diagnosis 结构化输出

LLM 最终不能只返回自然语言，应要求结构化 JSON。

```json
{
  "incident_id": "INC-20261008-001",
  "severity": "HIGH",
  "service": "order-service",
  "symptom": "订单创建接口 P99 从 120ms 升高到 2.1s",
  "root_cause": "MySQL 慢 SQL",
  "confidence": 0.92,
  "impact": {
    "success_rate_change": -5.7,
    "affected_requests": 18231
  },
  "evidence": [
    "DB P99 从 20ms 上升到 1900ms",
    "慢 SQL 数量增加 8 倍",
    "Redis latency 正常",
    "MQ consumer lag 正常"
  ],
  "suggestions": [
    "检查订单查询 SQL 是否命中索引",
    "检查数据库连接池",
    "检查最近版本变更"
  ]
}
```

---

## 12. Agent 诊断流程示例

### 场景：订单 API 延迟突然升高

```text
Alert
 ↓
P99 = 2.1s
 ↓
Agent
 ↓
Metrics Tool
 ↓
发现 DB latency = 1.9s
 ↓
DB Tool
 ↓
发现慢 SQL 数量增加 8 倍
 ↓
Logs Tool
 ↓
发现大量 DB_TIMEOUT
 ↓
Business Tool
 ↓
订单成功率下降 5.7%
 ↓
Agent 综合分析
 ↓
Root Cause = MySQL 性能异常
```

注意：Agent 不应看到一项异常就立即下结论，而应继续收集证据。

---

## 13. 诊断任务状态机

```text
CREATED
   ↓
RUNNING
   ↓
COLLECTING_EVIDENCE
   ↓
ANALYZING
   ↓
COMPLETED
```

异常情况：

```text
RUNNING
   ↓
FAILED
```

或者：

```text
ANALYZING
   ↓
INSUFFICIENT_EVIDENCE
```

---

## 14. 数据库设计

新增三张表即可。

### 14.1 ai_diagnosis_task

```sql
CREATE TABLE ai_diagnosis_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    incident_id VARCHAR(64) NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    alert_type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    start_time DATETIME,
    end_time DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_incident_id (incident_id)
);
```

### 14.2 ai_diagnosis_result

```sql
CREATE TABLE ai_diagnosis_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    severity VARCHAR(32),
    root_cause TEXT,
    confidence DECIMAL(5,4),
    impact JSON,
    evidence JSON,
    suggestion JSON,
    raw_result JSON,
    created_at DATETIME NOT NULL,
    INDEX idx_task_id (task_id)
);
```

### 14.3 ai_tool_execution

```sql
CREATE TABLE ai_tool_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments JSON,
    result JSON,
    status VARCHAR(32),
    execution_time_ms BIGINT,
    created_at DATETIME NOT NULL,
    INDEX idx_task_id (task_id)
);
```

`ai_tool_execution` 是 Agent 可解释性的关键，用于完整保存 Tool Calling Trace。

---

## 15. 告警与触发机制

MVP 可以先采用：

```text
Prometheus
    ↓
Alert Rule
    ↓
Alertmanager
    ↓
AI Monitor API
    ↓
Diagnosis Task
    ↓
Agent
```

示例规则：

```text
订单接口 P99 > 1s 持续 30 秒
        ↓
触发 AI 诊断
```

业务指标：

```text
order_success_rate < 99%
        ↓
触发 AI 诊断
```

MQ：

```text
mq_lag > 10000
        ↓
触发 AI 诊断
```

---

## 16. 告警降噪

为了避免同一个事故触发大量 Agent Task，需要进行简单聚合：

```text
同一服务
+
同一异常类型
+
时间窗口内
```

合并为一次 Incident。

例如：

```text
P99 高
Error Rate 高
Timeout 高
```

可能是同一个数据库故障，不应该启动 3 个 Agent。

应当：

```text
3 Alert
  ↓
Incident Aggregator
  ↓
1 Diagnosis Task
```

---

## 17. 安全设计

### 17.1 最小权限原则

Agent Tool 全部使用只读权限。

```text
Metrics  READ
Logs     READ
DB       READ
MQ       READ
Business READ
```

### 17.2 敏感信息处理

禁止发送给 LLM：

- 用户密码
- Token
- 身份证号
- 银行卡号
- 完整账户信息
- 数据库连接信息
- Secret / API Key

日志进入 Agent 前需要脱敏。

### 17.3 Prompt Injection 防护

Tool 返回的数据视为“不可信数据”。

例如日志中出现：

```text
ignore previous instructions
```

Agent 必须把它当作普通日志内容，而不能当成系统指令。

---

## 18. LLM 故障处理

LLM 不得成为核心业务单点故障。

### 超时

```text
LLM Timeout
   ↓
Retry 1~2 次
   ↓
失败
   ↓
保存原始 Alert
   ↓
普通监控继续生效
```

### LLM 不可用

仍然保留：

```text
Prometheus
Grafana
Alertmanager
```

即：

> AI 失败 ≠ 监控失败。

---

## 19. 故障注入方案

为了验证 Agent，测试环境需要主动制造故障。

### Case 1：MySQL 慢 SQL

预期：

```text
DB latency ↑
slow query ↑
API P99 ↑
```

Agent 应定位到 MySQL。

### Case 2：Redis 延迟

预期：

```text
Redis latency ↑
API latency ↑
```

Agent 应定位到 Redis。

### Case 3：MQ 消费变慢

预期：

```text
Producer Rate > Consumer Rate
MQ Lag ↑
```

Agent 应定位到 Consumer。

### Case 4：Outbox 积压

预期：

```text
outbox_pending_count ↑
```

Agent 应判断 MQ 发布链路或 Outbox Publisher 异常。

### Case 5：Redis / MySQL 不一致

测试：

```text
Redis inventory = 80
MySQL inventory = 82
```

Agent 应通过 Business Tool 发现不一致，并输出补偿建议，而不是直接修改数据。

---

## 20. Dashboard 设计

前端可以使用 Vue3 + ECharts。

### 页面 1：系统总览

```text
服务数量
当前告警
订单成功率
API P99
MQ Lag
Outbox Pending
```

### 页面 2：异常详情

```text
异常信息
 ↓
指标趋势
 ↓
Agent Tool Trace
 ↓
根因
 ↓
证据
 ↓
影响范围
 ↓
修复建议
```

### 页面 3：Agent 执行链路

```text
Alert
 ↓
Metrics Tool
 ↓
DB Tool
 ↓
Logs Tool
 ↓
Business Tool
 ↓
LLM
 ↓
Diagnosis
```

这个页面特别适合面试演示。

---

## 21. 后端 API 设计

### 创建诊断任务

```http
POST /api/ai/diagnosis
```

Request：

```json
{
  "alertId": "ALT-001",
  "service": "order-service",
  "alertType": "LATENCY_HIGH"
}
```

### 查询诊断结果

```http
GET /api/ai/diagnosis/{incidentId}
```

### 查询 Tool Trace

```http
GET /api/ai/diagnosis/{incidentId}/tools
```

### 查询历史诊断

```http
GET /api/ai/diagnosis/history
```

---

## 22. Agent Executor 核心伪代码

```java
public DiagnosisResult diagnose(AlertEvent alert) {

    AgentContext context = buildContext(alert);

    while (!context.isCompleted()) {

        AgentDecision decision = llm.decide(
                systemPrompt,
                context
        );

        if (decision.isToolCall()) {
            ToolResult result = toolExecutor.execute(
                    decision.getToolName(),
                    decision.getArguments()
            );

            saveToolTrace(context.getTaskId(), decision, result);
            context.addObservation(result);
        } else {
            return parseDiagnosis(decision.getContent());
        }
    }

    throw new DiagnosisException("Diagnosis timeout");
}
```

真实实现需要补充：

- 最大 Tool 调用次数
- Timeout
- Tool 白名单
- 参数校验
- Result 大小限制
- LLM Retry
- JSON Schema 校验
- 日志脱敏

---

## 23. 关键约束

### 23.1 Tool 白名单

Agent 只能调用注册过的 Tool：

```text
MetricsTool
LogsTool
DBTool
MQTool
BusinessTool
```

### 23.2 调用次数限制

例如一次诊断最多：

```text
10 次 Tool Call
```

防止无限循环。

### 23.3 Token 限制

日志必须：

- 限制条数
- 截断长日志
- 提取错误样本
- 去除重复日志

不要直接把大量原始日志全部传给 LLM。

---

## 24. MVP 验收标准

第一版满足以下条件即可认为完成。

### 功能

- [ ] Prometheus 能采集核心系统指标。
- [ ] Grafana 能展示 API、DB、Redis、MQ 和业务指标。
- [ ] Alertmanager 能产生异常事件。
- [ ] AI Monitor API 能接收 Alert Event。
- [ ] Agent 能成功调用至少 5 个 Tool。
- [ ] Agent 能完成至少 2~3 轮 Tool Calling。
- [ ] Agent 能输出结构化 Diagnosis JSON。
- [ ] 系统保存完整 Tool Trace。
- [ ] 前端可以展示诊断结果。
- [ ] 能完成至少 5 种故障注入测试。

### 安全

- [ ] Agent 不拥有 DB 写权限。
- [ ] Tool 参数全部经过校验。
- [ ] LLM 输入进行敏感信息脱敏。
- [ ] LLM 故障不会影响交易系统。
- [ ] Agent Tool 调用次数有限制。

---

## 25. 性能与可靠性要求

目标值以本地测试环境为主，不追求生产级绝对指标。

### Agent

```text
单次诊断时间：< 10s
Tool 单次调用：< 1s
最大 Tool Call：10
```

### 监控

```text
Metrics 采集周期：15s
Alert 触发：秒级~分钟级
```

### 系统隔离

```text
Agent 异常
   ↓
不能阻塞交易请求
```

---

## 26. 开发路线

### Phase 1：可观测性接入

```text
Micrometer
   ↓
Prometheus
   ↓
Grafana
```

先确保可以看到：

```text
API P99
Error Rate
DB Latency
Redis Latency
MQ Lag
Outbox Pending
Order Success Rate
```

### Phase 2：异常事件

```text
Prometheus Alert
   ↓
Alertmanager
   ↓
AI Monitor API
```

### Phase 3：Tool

依次实现：

```text
Metrics Tool
Logs Tool
DB Tool
MQ Tool
Business Tool
```

### Phase 4：Agent

```text
LLM API
   ↓
System Prompt
   ↓
Tool Calling
   ↓
Diagnosis
```

### Phase 5：故障注入

完成：

```text
MySQL Slow SQL
Redis Timeout
MQ Consumer Slow
Outbox Backlog
Redis/MySQL Inconsistency
```

### Phase 6：Dashboard

实现：

```text
异常列表
异常详情
诊断结果
Tool Trace
指标趋势
```

---

## 27. 推荐技术栈

### 后端

```text
Java
Spring Boot
Spring MVC
MySQL
Redis
Kafka / RabbitMQ
```

### 可观测性

```text
Micrometer
Prometheus
Grafana
```

### AI

```text
LLM API
Tool Calling / Function Calling
Prompt Engineering
Agent Orchestration
```

### 前端

```text
Vue 3
ECharts
```

### 工程化

```text
Docker
Git
Linux
```

OpenTelemetry、Loki、Jaeger 等可以作为二期增强，不是 MVP 必需项。

---

## 28. 二期扩展

完成 MVP 后可以继续增加：

### 28.1 Human-in-the-Loop

```text
Agent
 ↓
生成修复方案
 ↓
人工确认
 ↓
执行低风险 Action
```

### 28.2 自动恢复

允许有限的低风险操作，例如：

```text
重新发送 Outbox
重新投递 DLQ
触发测试环境补偿
```

### 28.3 RAG

将项目文档、接口文档、故障处理手册加入知识库：

```text
故障
 ↓
Agent
 ↓
RAG 查询历史故障
 ↓
Tool 查询实时状态
 ↓
LLM 综合诊断
```

### 28.4 历史故障学习

保存历史 Incident：

```text
异常
 ↓
根因
 ↓
处理方式
 ↓
最终结果
```

以后 Agent 可以参考历史案例。

---

## 29. 最终项目数据流

```text
                 ┌──────────────────┐
                 │    业务请求       │
                 └────────┬─────────┘
                          ↓
                 ┌──────────────────┐
                 │   金融交易系统    │
                 └────────┬─────────┘
                          ↓
             Redis → MySQL → Outbox → MQ
                                      ↓
                                  Consumer

====================================================

             同时产生可观测性数据

        ┌──────────┬──────────┬──────────┐
        ↓          ↓          ↓          ↓
     Metrics     Logs       DB Stats    MQ Stats
        └──────────┬──────────┴──────────┘
                   ↓
               Prometheus
                   ↓
              Alertmanager
                   ↓
               Alert Event
                   ↓
            AI Monitor Agent
                   ↓
        ┌──────────┼───────────┐
        ↓          ↓           ↓
     Metrics     Logs        DB/MQ/Business
       Tool       Tool            Tool
        └──────────┼───────────────┘
                   ↓
                  LLM
                   ↓
       ┌───────────┼────────────┐
       ↓           ↓            ↓
    Root Cause   Evidence    Suggestion
       └───────────┼────────────┘
                   ↓
             Diagnosis Result
                   ↓
         Dashboard / Notification
```

---

## 30. 简历项目描述建议

完成 MVP 后，建议简历写成：

> **金融交易系统与智能监控 Agent**  
> 面向高并发金融交易场景，在已有 Redis + MySQL + Outbox + MQ 最终一致性交易系统上增加可观测性与 AI 智能运维能力。基于 Micrometer + Prometheus + Grafana 建立 API P99、错误率、数据库延迟、MQ Lag、Outbox 积压及订单成功率等监控体系；设计 AI Monitor Agent，通过 Tool Calling 动态调用 Metrics、Logs、DB、MQ、Business 等工具，结合多源运行数据完成异常根因分析、影响范围评估及修复建议生成，并通过 Tool Trace 与结构化 Diagnosis 提升诊断过程可解释性；针对 MySQL 慢查询、Redis 延迟、MQ 消费堆积、Outbox 积压、Redis/MySQL 数据不一致等场景完成故障注入与验证。

---

## 31. 项目完成后的面试主线

面试时建议围绕以下链路讲解：

```text
为什么需要 AI 监控？
        ↓
传统监控解决了什么？
        ↓
为什么传统告警还需要 Agent？
        ↓
Agent 如何获取真实系统信息？
        ↓
为什么需要 Tool Calling？
        ↓
Agent 如何避免胡乱下结论？
        ↓
为什么需要多个独立证据？
        ↓
如何处理 LLM 不稳定？
        ↓
如何保证 Agent 不影响交易系统？
        ↓
如何保障金融场景数据安全？
        ↓
发生 MySQL / Redis / MQ 故障时 Agent 如何定位？
```

这条面试主线能够把项目中的 **后端、分布式、可观测性、AI Agent、稳定性和安全** 串成一个完整故事。

---

# 32. MVP 最终交付物

项目最终建议形成以下目录：

```text
project/
├── backend/                  # 原有业务系统
├── ai-monitor/               # AI 监控 Agent
│   ├── core/
│   ├── agent/
│   ├── tool/
│   ├── analyzer/
│   ├── alert/
│   └── storage/
│
├── monitoring/
│   ├── prometheus/
│   ├── grafana/
│   └── alertmanager/
│
├── frontend/
│
├── fault-injection/
│
├── docker-compose.yml
└── README.md
```

README 至少包含：

1. 系统架构图。
2. 核心数据流。
3. Agent 工作流程。
4. Tool 设计。
5. 故障注入方法。
6. 运行截图。
7. 诊断案例。
8. 性能测试结果。
9. 安全设计。
10. 本项目遇到的问题与解决方案。

---

# 33. 一句话定义

> **本项目是在已有金融交易后端系统之上，通过可观测性 + Tool Calling + LLM Agent 构建智能故障诊断闭环，使系统从“发现异常并告警”升级到“发现异常、主动取证、分析根因并给出处理建议”。**
