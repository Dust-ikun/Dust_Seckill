# 交接文档（新会话先读这一份）

> **这份文档解决什么问题**：DSH 的新会话**不继承**上一个会话的记忆。
> 机器上的容器、镜像、数据卷和仓库里的文件都还在，但「上一轮做到哪了、
> 踩过哪些坑、下一步该干什么」只存在于对话里 —— 会话一换就丢。
> 本文件把那些信息固化下来，让新会话能用一分钟恢复上下文。
>
> **维护约定**：每完成一个批次就更新「当前进度」与「下一步」两节。
> 最后更新：2026-10-09（**批次 2 完成**：5 个只读 Tool + 白名单 + 参数校验 + 结果上限）

---

## 0. 一分钟恢复上下文

**任务**：给秒杀系统加 AI 智能监控 Agent（SPEC 见仓库根目录
`金融交易系统_AI监控Agent_升级改造_SPEC.md`）。

**已完成**：中间件 Docker 化 + 完整可观测性栈 + AI 监控表结构 + 全部配置校验
+ 批次 1（日志结构化 + Logs Tool 底座 + RocketMQ 指标 + 健康基线）
+ **批次 2（Tool 层：白名单注册表 + 5 个只读 Tool + 参数校验 + 结果上限 + 启动自检）**。
**未开始**：批次 3（手写 DeepSeek 客户端 + ReAct 循环 + API），批次 4（故障注入 + 前端）。

**恢复环境的命令（复制粘贴即可）**：

```powershell
cd D:\Java_Project\Dust-ikun_seckillv3
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker
docker compose --profile observability up -d
docker compose ps
```

**当前实际状态（写本文档时实测）**：

| 项 | 状态 |
|---|---|
| 7 个长效容器（4 核心 + 3 可观测性） | ✅ 运行中，全部 healthy |
| 第 8 个容器 `seckill-rocketmq-init` | ✅ `Exited (0)` —— 它是一次性 init（chown 三个卷属主），**正常退出就是成功**，不是故障 |
| 数据卷 | ✅ 9 个全在；`seckill` 库完好、AI 三张表在、`orders` 有历史测试数据 |
| 端口 3306 / 6379 / 9876 / 10911 / 9090 / 9093 / 3000 / 5557 | ✅ 均在用（容器） |
| 端口 10912 | ❌ **已废弃**：原先留给 JMX，实测 RocketMQ 5.x 没有 MBean（见 §3） |
| 应用 | 未运行（端口 8081 / 9091 空闲）。**跑测试前必须确保它没在跑**（见 §4 注） |
| 测试 | ✅ `mvn -o -B test` → **Tests run: 212, Failures: 0, Errors: 0**（批次 1 是 93） |
| 配置校验 | ✅ `validate_config.py` → **58 项，0 错误 0 警告**（批次 1 是 54；第 9 组专查 Tool 层） |
| **本机原生 MySQL / Redis** | ❌ **已彻底移除**（见 §2.6）。**现在唯一的中间件就是 Docker** |
| **DSH 会话的 shell** | ✅ **pwsh 7.6.6 / Core**（中文乱码已消失，见 §2.3） |
| 系统 locale | 仍是 `zh-CN` —— **这是可以的**，乱码与它无关，别为此改系统语言（见 §2.3） |

> 上面那条 `docker compose up -d ...` 会**复用现有数据卷**，
> 不会重新初始化数据库（`docker-entrypoint-initdb.d` 只在空卷时执行）。
> **做故障注入前先看** `docs/批次1_健康基线快照.md` §5：
> 本机库有历史测试数据，对账会稳定报 `MARK_MISSING`，那是数据残留不是缺陷。

---

## 1. 当前进度

### 已交付并验证

| 交付物 | 位置 | 验证方式 |
|---|---|---|
| 中间件编排（7 服务 + 1 个一次性 init） | `docker-compose.yml` | 8 个容器全部 healthy |
| RocketMQ 配置 | `docker/rocketmq/broker.conf` `namesrv.conf` | `mqadmin clusterList` 可见 `broker-a / 127.0.0.1:10911` |
| Prometheus 采集 + 12 条记录规则 + 17 条告警 | `monitoring/prometheus/**` | 4 个 target 全 `up`；规则查得到序列 |
| Alertmanager（路由 + 4 条抑制 + webhook） | `monitoring/alertmanager/alertmanager.yml` | `/healthy` = OK |
| Grafana 3 个看板 / 46 面板 + 数据源 | `monitoring/grafana/**` | 数据源 uid `seckill-prometheus` 已自动装载 |
| AI 监控三张表 | `src/main/resources/db/schema-ai-monitor.sql` | 容器初始化后 `SHOW TABLES` 可见 |
| docker profile | `src/main/resources/application-docker.yaml` | 应用端到端跑通 |
| 配置校验器 | `monitoring/validate_config.py` | **58 项 0 错误 0 警告**（9 组检查） |
| 启动脚本 | `scripts/run-app.ps1` | `-PrintOnly` 实测正确 |
| **原生中间件移除脚本** | `scripts/remove-native-middleware.ps1` | `-Yes -KeepVolumes` 实跑成功（见 §2.6） |
| 可行性评估报告 | `docs/AI监控Agent_可行性评估与准备报告.md` | 已修正 4 处错误结论（见 §3） |
| 部署与验收手册 | `docs/AI监控Agent_部署与验收手册.md` | 含 §9.4 三个故障复盘 |
| **结构化日志** | `src/main/resources/logback-spring.xml` | 一行 grep 单号即可串起请求↔消费两侧 |
| **Logs Tool 底座** | `src/main/java/com/dustikun/seckill/monitor/log/**` | 46 项单测全绿 |
| **脱敏（SPEC §17.2）** | `src/main/java/com/dustikun/seckill/monitor/core/Masker.java` | 启动自检 + 13 项单测 |
| **Broker 指标导出** | `docker/rocketmq/broker.conf` 末尾 + `prometheus.yml` | target `rocketmq-broker` = up |
| 健康基线快照 | `docs/批次1_健康基线快照.md` | 手册 §5 六项全达标 |
| **批次 2 交付说明** | **`docs/批次2_Tool层与验收.md`** | **新会话要动 Tool 层就读它** |
| **Tool 抽象 + 白名单注册表** | `monitor/tool/MonitorTool.java` `ToolRegistry.java` | 未注册名一律 REJECTED；装配期断言 ≥5 个 |
| **参数校验** | `monitor/tool/ToolArguments.java` | 越界拒绝、类型容错；14 项单测 |
| **统一出口（脱敏+收缩+包裹）** | `monitor/tool/ResultShaper.java` | 13 项单测；闭合标签不可伪造 |
| **5 个只读 Tool** | `monitor/tool/{metrics,logs,db,mq,business}/**` | 119 项新测试（含 15 项真中间件集成测试） |
| **指标目录（42 个口径白名单）** | `monitor/tool/metrics/MetricCatalog.java` | 启动自检对照 Prometheus 指标名索引 |

### 关键验证结果（这些数字是新会话最需要知道的）

```
mvn -o -B test                    → Tests run: 212, Failures: 0, Errors: 0  ✅
                                    （原 93 项 + 批次 2 新增 119 项）
monitoring/validate_config.py     → 58 项检查，0 错误 0 警告              ✅
应用端到端（容器中间件）            → 预热 936 → 下单 QUEUED → SUCCESS
                                    一行 grep 单号 → 请求侧 + 消费侧两行      ✅
ACTUATOR 直方图                    → grep -c 'le="' = 1142（必须 > 0）     ✅
Prometheus targets                 → 4/4 up（含 rocketmq-broker）          ✅
Tool 启动自检（4 行）               → 白名单 5 个 / 指标目录 42 条口径名字全对 /
                                    日志底座可用 / 结果上限 8000 字符         ✅
Prometheus 指标名索引              → 553 个名字（批次 2 前是 550，见 §3 第 6 条）✅
本机 shell                         → pwsh 7.6.6 / Core                      ✅
本机原生中间件                      → 已全部移除，只剩 Docker                ✅
```

> **环境变了要重跑一遍**：上表是变更后实测的，但新会话开工前建议至少跑一次
> `docker info --format "{{.ServerVersion}}"`（确认引擎在跑）与
> `mvn -o -B test`（确认 212 项仍全绿）。

### 未开始

`com.dustikun.seckill.monitor` 包下的批次 3 与批次 4：

- **批次 3**：手写 DeepSeek 客户端 + ReAct 循环 + Diagnosis 解析 + `POST /api/ai/alerts`
  与 4 个查询 API + Tool Trace 落库 + Incident 聚合
- **批次 4**：5 个故障注入 Case + 最简前端

**批次 2 已经备好的地基（批次 3 直接用，不要重造）**：

| 批次 3 要的东西 | 批次 2 已经给的位置 |
|---|---|
| Tool 的唯一执行入口（含白名单/校验/超时/整形） | `monitor/tool/ToolRegistry#invoke(name, args)` |
| 给模型看的工具清单（OpenAI tools 结构） | `ToolRegistry#definitions()` |
| 给模型看的工具结果（已脱敏/已收缩/已包标签） | `ToolResult#llmText()` |
| 落 `ai_tool_execution.arguments` 的值 | `ToolArguments#accepted()`（校验后的实际值） |
| 落 `ai_tool_execution.status` 的映射 | `ToolStatus`（SUCCESS / FAILED / REJECTED） |
| 每 Tool 单次调用的耗时 | `ToolResult#elapsedMillis()` |
| 工具白名单与参数的唯一真相 | 每个 Tool 的 `description()` + `parametersSchema()` |


---

## 2. 本机环境的硬约束（新会话必须知道，否则会重复踩坑）

### 2.1 Docker 必须走 Clash 代理，且镜像源已被清空

```powershell
docker info --format "{{json .RegistryConfig.Mirrors}}"   # 必须是 []
```

- **三个国内镜像源已全部失效**（`docker.m.daocloud.io` 返回 DENIED、
  `docker.1ms.run` 返回 404、`jobcher` 不可达），因此 `daemon.json` 里
  `registry-mirrors` 被清空为 `[]`。
- Docker Desktop 的代理指向本机 Clash：`%APPDATA%\Docker\settings-store.json`
  里 `ProxyHTTPMode: manual` + `OverrideProxyHTTP(S): http://127.0.0.1:7897`。
- **失效镜像源比没有镜像源更糟**：Docker 会对每个 blob 先卡一遍失败的源，
  表现是「不报错、不超时、永远不完成」。判断拉取是否真在动要看
  `docker system df` 的层缓存是否增长 —— `docker pull redis` 秒完成可能只是缓存命中。
- 代理链路偶发 `EOF`，**重试即可**。

### 2.2 我所在的会话与用户的终端权限不同

- `docker` / 出网在**默认沙箱**下会被拒（`E_ACCESSDENIED` / schannel 无凭据）。
- 需要时用更大的沙箱权限；**文件策略目前是 `danger-full-access`**，
  但审批策略是 `never`，所以**不要请求提权**，直接执行。
- **需要管理员权限的事只能交给用户**：我实测过 `IsAdmin = False`，
  例如卸载 MSI 会以 **Error 1730 "You must be an Administrator"** 失败
  （而且它会先写一份 150 KB 日志，很容易被误读成「卸载成功但目录还在」）。
  这类操作写成脚本交给用户在**管理员窗口**执行，不要自己硬试。
- 原生的 MSYS2 版 Redis（`D:\Redis-8.10.1`）**不能**在本机跑
  （`CreateFileMapping Win32 error 5`），所以 Redis 必须走 Docker。
  **该目录已于 2026-10-09 删除**（见 §2.6）。

### 2.3 PowerShell 现在是 pwsh 7.6.6，且中文乱码问题已消失

- ✅ **本会话实测**：`$PSVersionTable` → **7.6.6**，`PSEdition = Core`，
  `[Console]::OutputEncoding = utf-8`，`$OutputEncoding = utf-8`。
- ✅ **乱码已修复**（这是升级 pwsh 带来的，不是改了系统语言）：
  `cmd /c "echo 中文测试"`、`cmd /c type <UTF-8 文件>` 都能正确回显中文；
  升级前这两类都会输出 `鑴辨晱...` 这种 UTF-8-被-GBK-解码的乱码。
- ⚠️ **一个容易走错方向的推论，记下来以免重犯**：
  乱码的根因是**管道用 GBK 解码 UTF-8**，是编码问题，**不是「系统语言是中文」**。
  所以**把 Windows 切成 `en-US` 修不了它** —— 英文 Windows 的 cmd 默认码页是
  437/850，同样会解错，而且中文原文会变成 `???`，比乱码更难查。
  真正修好它的是 pwsh 7 默认 UTF-8。**当前系统 locale 仍是 `zh-CN`，这是可以的。**
- `chcp` 仍显示 **936（GBK）**，但 `[Console]::OutputEncoding` 是 utf-8，
  两者不一致时以 PowerShell 的为准 —— 只要不特意用 `chcp 65001` 折腾它就没问题。

**仍然有效的两个坑**（与 5.1 时代无关，换成 pwsh 7 也照样踩）：

- **读 `.env` 必须显式指定 UTF-8**：`Get-Content` 在某些配置下仍按系统 ANSI 解码，
  读 UTF-8 中文注释会产生连锁错位，**把紧随其后的配置项一起吞进注释**
  → 表现为「变量没读到」。正确做法：
  `[System.IO.File]::ReadAllLines($path, [System.Text.UTF8Encoding]::new($false))`。
  （`scripts/run-app.ps1` 里就是这么写的，不要改成 `Get-Content`。）
- **脚本正文保持纯 ASCII**：`-File` 运行脚本时，无 BOM 的 UTF-8 中文可能导致
  语法错误，而任何工具重写文件都可能丢 BOM。因此 `scripts/run-app.ps1` 与
  `scripts/remove-native-middleware.ps1` 的正文都是**纯 ASCII**，中文说明放文档里 ——
  这是刻意的，不要「优化」回中文。
- `LocalMachine` 执行策略仍是 `RemoteSigned`，运行脚本带 `-ExecutionPolicy Bypass`：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-app.ps1
# pwsh 7 也可以：
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-app.ps1
```

### 2.4 `.env` 与 shell 环境变量是两回事

`.env` 是给 `docker compose` 读的；宿主机上的 Java 进程读 **shell 环境变量**。
`scripts/run-app.ps1` 负责把前者导出成后者。手工启动时必须自己导出，否则
会出现「容器在 6380、应用连 6379」这种能启动但连不上的状态。

### 2.5 端口约定（已回到标准值）

| 服务 | 端口 | 说明 |
|---|---|---|
| 服务 | 端口 | 说明 |
|---|---|---|
| MySQL | 3306 | ✅ **只有容器**。原生 MySQL84 已于 2026-10-09 卸载（见 §2.6） |
| Redis | 6379 | ✅ **只有容器**。原生 MSYS2 版已删除（跑不起来，见 §2.2）<br>⚠️ 另一个项目的 `skychat-redis` 也绑 6379，**不能同时启动** |
| RocketMQ | 9876 / 10911 / 10909 | 本机原生 RocketMQ 早已停止；本项目用容器 |
| Broker 指标 | 5557 | 只绑宿主机回环；Prometheus 走容器网络抓 `rocketmq-broker:5557` |
| 应用 | 8081（业务）/ 9091（运维） | 跑在宿主机，不在容器里 |
| 可观测性 | 9090 / 9093 / 3000 | |

### 2.6 原生中间件已彻底移除（2026-10-09），以后只用 Docker
**已删除**（共回收 ≈ 1.77 GB）：

| 目标 | 结果 |
|---|---|
| `MySQL84` 服务 + MSI 注册项（`MySQL Server 8.4.11`） | ✅ 已卸载（`msiexec /x {07EB6F8B-0CA6-4EE7-A669-81761C67801E}`，exit 0） |
| `C:\Program Files\MySQL`（576 MB） | ✅ 已删 |
| `D:\ProgramData\MySQL`（1160 MB，含 `Data` 与 `my.ini`） | ✅ 已删 |
| `D:\Redis-8.10.1`（37 MB，MSYS2 便携版） | ✅ 已删 |
| `C:\ProgramData\MySQL\MySQL Configurator` | ✅ 内容已清空（可能剩一个**空目录**，无害；要彻底删见下） |

**工具**：`scripts/remove-native-middleware.ps1`（**纯 ASCII**，带 dry-run）
- 不带 `-Yes` = 只报告；带 `-Yes` = 真删；`-KeepVolumes` = 保留 Docker 数据卷。
- **必须在管理员窗口跑**（`msiexec` 需要；非管理员会报 Error 1730 且写一份 150 KB 日志）。
- 用户实跑命令（已成功）：
  `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\remove-native-middleware.ps1 -Yes -KeepVolumes`

**遗留的空目录**（想清掉就在管理员窗口执行）：

```powershell
Remove-Item "C:\ProgramData\MySQL" -Recurse -Force
```

**删掉原生之后不需要改任何配置**：容器用的就是标准 3306 / 6379，
`application.yaml` 与 `.env` 都不用动 —— 这正是当初「端口回到标准值」的收益。

#### ⚠️ 数据卷的存亡规则（**不要再搞错**）

| 命令 | 对数据卷的影响 |
|---|---|
| `docker compose stop` / `down` | ✅ **不删**（卷是具名卷） |
| `docker compose down -v` | ❌ **删全部 9 个卷** |
| `docker volume rm <name>` | ❌ 删指定的那个 |

当前 9 个卷全部健在（`-KeepVolumes` 生效，已实测）：
`seckill_mysql-data` / `_mysql-slowlog` / `_redis-data` / `_rocketmq-broker-store` /
`_rocketmq-broker-logs` / `_rocketmq-namesrv-logs` / `_prometheus-data` /
`_alertmanager-data` / `_grafana-data`

**真删了会丢什么**（都**可再生**，因此不是灾难）：
64 条测试订单 + 64 条 outbox 记录、`stock` 行、Redis 全部 key、
Prometheus/Grafana 历史、RocketMQ store。

**恢复动作**：

```powershell
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker
docker compose --profile observability up -d
# 等 mysql healthy 后必须预热 —— schema.sql 只建表、不插入 stock 行
curl.exe -X POST "http://localhost:8081/seckill/preheat?stockId=1"
```

> 不预热的话每一单都会返回 `1003（活动库存未预热）` —— 这是空卷恢复时最容易漏的一步。

---

## 3. 部署中踩过的坑（都已修，但改动理由要保住）

这四条都写在代码注释里了，这里做索引 —— **重构时不要把它们「简化」掉**：

| # | 症状 | 真因 | 修复位置 |
|---|---|---|---|
| 1 | Broker 报 `NullPointerException at ScheduleMessageService.configFilePath` | 具名卷属主是 root，而镜像以 uid=3000 运行 | `docker-compose.yml` 的 `rocketmq-init`（chown 三个卷） |
| 2 | Broker 报 `Failed to bind to 0.0.0.0:10909` | 显式写了 `haListenPort`，而 RocketMQ 5.3.2 内部自己就占 10909 | `broker.conf` 里**删掉**该行 |
| 3 | Grafana 无限重启，报 `'folder' and 'folderUID' should be empty` | `folder` 与 `foldersFromFilesStructure: true` 语义互斥 | `dashboards.yml` 改为 `false` |
| 4 | `mqadmin clusterList` 崩在 `FileNotFoundException` | NameServer 日志卷同样 root 属主 | 已并入坑 1 的 init 容器 |

另有三处「配置被静默忽略」的教训 —— **这一类是本项目最大的坑源**：

- ⚠️ **【批次 1 更正，方向与原文相反】`management.metrics.*` 才是正确前缀，
  `spring.metrics.*` 才是被静默忽略的那个。** 原文写反了，而且
  `application.yaml` 里的注释把错误结论一起固化了（「前缀必须是 spring.metrics」），
  于是这段配置**从未生效过**：`/actuator/prometheus` 里
  `http_server_requests_seconds` 是 summary、一个 `le=` 桶都没有，
  P99 / P95 / 慢请求占比三条记录规则全是空序列，三条告警永远不响。
  判定依据（Boot 4.0.8 字节码）与实测命令见 `application.yaml` 的对应注释。
  **一行验证**：`curl -s :9091/actuator/prometheus | grep -c 'le="'` 必须 > 0（当前 392）。
- **`lettuce.command.completion` 匹配不到任何仪表**，实际注册名是 `lettuce`
  （标签是 `db_operation` 不是 `command`）。写错会让 `RedisOperationSlow` 告警静默失效。
- **RocketMQ 5.x 没有 JMX MBean**（4.x 才有），因此「挂 jmx_prometheus_javaagent」
  这条方案在本项目**根本不成立**。实测：`jar tf rocketmq-broker-5.3.2.jar | grep -ci mbean`
  → 0；枚举平台 MBeanServer 只有 23 个 JDK 自带 ObjectName。正确做法是 Broker
  **内置**的 `metricsExporterType = PROM`（见 `broker.conf` 末尾）。
  另注意它的绑定地址默认是 `brokerIP1`（= 127.0.0.1），**必须改成 0.0.0.0**
  否则 Prometheus 在容器里连不上，而宿主机 curl 却是通的。

### 批次 2 新增的 5 条（其中 2 条不在计划内，是「去出口上数一次」数出来的）

| # | 症状 | 真因 | 修复位置 |
|---|---|---|---|
| 5 | `package com.fasterxml.jackson.databind does not exist`（看起来像依赖漏了） | **Boot 4.0.8 用的是 Jackson 3**：`tools.jackson.core:jackson-databind:3.1.5`，`com.fasterxml.jackson.databind` 已不存在；且 `JsonNode.fields()` **已被移除**，等价方法是 `properties()` | `ResultShaper` / `HttpPrometheusQuerier` / `MonitorToolConfiguration` 的 import |
| 6 | 无（`tool.logs.dedupe: true` 改了没反应） | `MonitorLogProperties` 绑的前缀是 `seckill.monitor.logs`，而配置文件里写的一直是 `seckill.monitor.tool.logs` —— **绑到了一个没人写过的前缀上**，一直回落到代码默认值（值恰好相同，所以无感） | `MonitorLogProperties` 的 `@ConfigurationProperties`；`ToolProperties` 删掉重复的 `logs` 段；`validate_config.py` 第 9 组新增检查 |
| 7 | 无（但要靠推理才能发现） | **`reconcile(repair=false)` 不是只读**：它写 `seckill_reconcile_findings_total`，而告警 `ReconcileInconsistencyDetected` 正是 `rate(...[10m]) > 0` → Agent 一调查就让告警再响，**自激闭环**；它还会写一条 ERROR 污染 Logs Tool 的证据 | 新增 `StockReconcileService#inspect()`（判定口径相同，无指标、无 ERROR）；Business Tool 只走它 |
| 8 | 搜 `keyword=LogsTool` 得到 `count=0`，但缓冲里明明有 25 条 | `serviceName` 只在 `TraceContext.open()` 作用域内写 MDC，**启动日志/定时任务/对账都没有它**；而 Logs Tool 默认按 `order-service` 过滤 → 最需要日志的那几类问题恰好查不到 | ① `LogsTool` 不再注入默认过滤值；② `LogRingBuffer#passes` 把「serviceName 为空」视为匹配 |
| 9 | `seckill:http_error_rate*` / `seckill:pipeline_health_score` 在 Prometheus 指标名索引里**不存在** | PromQL 的 `空序列 / 非空` 结果仍是**空序列**（不是 0）→ 没有 5xx 时错误率规则不产生任何样本，而 `pipeline_health_score` 乘了一个空序列 → **Grafana 健康总分面板永久空白** | `seckill-recording-rules.yml`：加 `or (0 * 分母)`；`order_success_rate` 再加 `and (分母 > 0)` 防 `0/0 = NaN` |

> **第 9 条是被批次 2 新增的启动自检抓出来的**，而**自检本身也被修过一次**：
> 初版对每条目录项做一次即时查询，结果把 39 条全报成「无序列」——
> 因为即时查询只返回「此刻仍活跃」的序列，而应用没在跑时 `seckill_*` 当然查不到。
> 它分不清「名字写错」与「此刻没数据」，于是变成一条会被习惯性忽略的假警报。
> 现在读 Prometheus 的**指标名索引**（`/api/v1/label/__name__/values`）：一次请求、
> 与「此刻有没有数据」无关。修正后索引从 **550 → 553** 个名字，缺失只剩
> `rocketmq_send_to_dlq_messages_total`（Broker 没产生过死信 → 属文档化的正常现象）。
>
> **可推广的判据（第 7 条给的）**：把任何「给人用的诊断动作」包成 Tool 之前，先问一句
> **「它会留下什么痕迹？那些痕迹会不会反过来触发告警？」** 写指标、写表、写 ERROR 日志的
> 读取动作都有自激的可能 —— 而闭环不会自己停下来，每一圈都要付费。

> **这一类问题的通用判据**：不要靠「读配置觉得对」来判断生效与否，
> 必须去**出口上数一次**。对指标口径就是
> `grep -c 'le="' /actuator/prometheus`；对 Logback 就是看启动日志里
> 有没有 `[LogsTool] 日志底座就绪`；对 compose 就是看 target 是不是 `up`。
> `validate_config.py` 第 8 组检查（把规则与看板里的 PromQL 指标名抽出来逐个对照清单）
> 就是为抓这类问题加的 —— 但它只能校验**名字存在**，校验不了**配置有没有生效**。

---

## 4. 验证清单（改完任何东西都跑这几条）

```powershell
# 1) 配置静态校验（58 项，改配置后必跑）
D:\Anaconda_envs\envs\agentRag\python.exe monitoring\validate_config.py

# 2) compose 合法性（不需要守护进程）
docker compose --profile observability config --quiet

# 3) 容器健康（期望 7 个长效容器全 healthy；第 8 个 init 容器是 Exited (0)，正常）
docker compose --profile observability ps        # 加 -a 才能看到那个已退出的 init 容器

# 4) 全量测试（212 项，需要 MySQL + Redis + RocketMQ 都在）
#    ⚠️ 跑之前确认应用**没有**在运行：每个测试上下文都会启动一个 RocketMQ 消费者，
#       与正在跑的应用同属一个消费组时，Broker 会把队列对半分，导致依赖消费进度的断言假失败。
mvn -o -B test

# 5) 应用端到端
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-app.ps1
#    启动日志里必有这四行「出口核对」（批次 2 的自检）：
#      [ToolRegistry] 工具白名单就绪：5 个，单次调用超时=3000ms
#      [MetricsTool] 指标目录自检通过：42 条口径引用的 42 个序列名在 Prometheus 中都存在
#      [LogsTool]    日志底座可用：容量 10000 条，启动完成时已缓存 N 条
#      [ToolRegistry] 结果整形器就绪：单次结果上限=8000 字符（含 <untrusted_data> 包裹）
curl.exe -X POST "http://localhost:8081/seckill/preheat?stockId=1"
curl.exe -X POST "http://localhost:8081/seckill?userId=1001&stockId=1"
curl.exe "http://localhost:8081/seckill/reconcile?stockId=1"   # 本机有历史数据 → MARK_MISSING（见 §5.1）

# 6) 指标口径真的生效了吗（这一步抓的是「配置被静默忽略」）
curl.exe -s "http://localhost:9091/actuator/prometheus" | Select-String 'le="' | Measure-Object   # 必须 > 0（当前 1142）
#    以及 Prometheus 的 4 个 target 全 up：
#    http://localhost:9090/api/v1/targets
#    【批次 2 新增】记录规则的「空序列」问题也可以在这里一眼看出来：
#    http://localhost:9090/api/v1/label/__name__/values   → 索引里应有 553 个名字，
#    其中必须包含 seckill:http_error_rate:5m / _all:5m / pipeline_health_score

# 7) 日志真的带上了 traceId 吗
#    下一次单，用返回体里的 orderNo grep 应用日志，应当拿到「请求侧 + 消费侧」两行

# 8) 【环境】本机已无原生中间件；开工前确认 Docker 引擎在跑
docker info --format "server={{.ServerVersion}}"        # 期望 29.8.1；空则 Docker Desktop 没起
#    若引擎没起，Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe" 后等约 45s
```

> **只跑批次 2 那几个测试**（不起容器也很快，除最后一条）：
>
> ```powershell
> mvn -o -B test -Dtest=ToolRegistryTest,ToolArgumentsTest,ResultShaperTest,LogsToolTest,MetricsToolTest,MqToolTest,DbToolStructuralReadOnlyTest,MetricCatalogTest,TimeParsingTest
> mvn -o -B test -Dtest=MonitorToolIntegrationTest      # 需要中间件 + Prometheus
> ```

> `validate_config.py` 需要 PyYAML。本机可用的解释器：
> `D:\Anaconda_envs\envs\agentRag\python.exe`（或 `vocotype-cli`）。
> 注意它不是 `python`/`py`（那两个没有 PyYAML）。

> **脚本放在 `scripts/` 下的注意事项**：两个 `.ps1` 的正文都是**纯 ASCII**（见 §2.3），
> 新增脚本请沿用这个约定，中文说明写进 `docs/`。
>
> **需要管理员权限的事不要自己试**：我实测 `IsAdmin = False`，
> `msiexec` 会以 Error 1730 失败并留下 150 KB 日志容易误读。
> 这类操作（卸载程序、改 HKLM、删 `C:\ProgramData` 下的目录）写成脚本交给用户在管理员窗口执行。

---

## 5. 阅读顺序（新会话按这个顺序读，不要通读全仓库）

1. 本文件（当前状态 + 环境约束，**§3 末尾的「批次 2 新增的 5 条」是本次新增的**）
2. **`docs/批次2_Tool层与验收.md`**（**要动 Tool 层就读这一份**：5 个工具的契约、
   「结构性只读」落在哪一行、5 个缺陷的完整复盘、给批次 3 的 6 条交接要点）
3. `docs/批次1_健康基线快照.md`（故障注入前的基线 + 两个「静默失效」的完整复盘）
4. `docs/AI监控Agent_可行性评估与准备报告.md` §3（SPEC 与现有工程的 6 处冲突及裁定）
   + §6（批次 1/2 的完成情况与判据）
5. `docs/AI监控Agent_部署与验收手册.md` §0 / §9（部署与排错）
6. `金融交易系统_AI监控Agent_升级改造_SPEC.md`（需求原文，1521 行，按需查阅）
7. `docs/秒杀链路文档.md`（**改动业务链路前必读**：失败补偿矩阵、崩溃点、对账判据）

**改业务链路的红线**：`README.md` §9、`docs/秒杀链路文档.md` 与源码注释里
记录了若干「顺序不可颠倒」的约束（如「先让订单不成立，再归还库存」）。
AI 监控是**旁路**，不得改动 `Redis → MySQL → Outbox → MQ → Consumer` 主链路。

**批次 1 对主链路的改动清单（共 4 处，都是加日志上下文，不动业务语义）**：

| 文件 | 改动 | 为什么不算侵入 |
|---|---|---|
| `RequestTraceFilter`（新增） | 每个 HTTP 请求建 traceId 并写 MDC | 只读请求参数，不改任何响应 |
| `SeckillOrderConsumer` | 消费前开 tracing 作用域；把 `confirm` 拆进 `doConsume` | 消费逻辑一行未改 |
| `SeckillService.seckill()` | 生成单号后写入 MDC 的 `orderNo` | 两行 `MDC.put`，无返回值变化 |
| `OutboxDispatchTask` / `MaintenanceTask` | 每轮定时任务开 tracing 作用域 | 只包了一层 try-with-resources |

**批次 2 对既有代码的改动清单（共 4 处，同样不动业务语义）**：

| 文件 | 改动 | 为什么不算侵入 |
|---|---|---|
| `StockReconcileService` | 抽出私有 `doReconcile(..., recordFindings)`，`reconcile()` 行为**逐字不变**；新增只读入口 `inspect()` | 唯一的行为差异只出现在新入口上（不记指标、不写 ERROR）。`reconcile` 的调用点（Controller / MaintenanceTask）一行未改 |
| `LogRingBuffer#passes` | serviceName 为空的记录不再被服务过滤排除 | 只是「少排除几条」，匹配条件更宽；对已有的过滤语义是纯放松（批量 1 的 19 项单测全绿） |
| `MonitorLogProperties` | 绑定前缀 `seckill.monitor.logs` → `seckill.monitor.tool.logs` | 修的是「绑到没人写过的前缀上」；配置文件里写的就是后者，改完默认值不变 |
| `seckill-recording-rules.yml` | 两条错误率加 `or (0 * 分母)`；成功率加 `and (分母 > 0)`；健康总分加兜底 | 只把「空序列 / NaN」变成「确知为 0 / 明确不存在」，告警阈值与判定口径未变 |

> **`seckill-recording-rules.yml` 那三条的收益是可验证的**：修正前 Prometheus 的指标名索引里
> 没有 `seckill:http_error_rate:5m` / `_all:5m` / `pipeline_health_score`（它们从未被写入过），
> 修正后索引从 550 个名字变成 553 个。核对方式见 §4 第 6 条。

> 之所以要在 `SeckillService` 里写 `orderNo` 进 MDC：MDC 是 ThreadLocal，
> **不会**从请求线程传播到消费线程，而 `orderNo` 是两侧唯一的公共字段。
> 没有它就只能靠时间戳猜，而「猜」正是这套日志要消灭的东西。
