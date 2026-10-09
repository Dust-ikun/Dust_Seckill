#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成 SPEC 第 20 节要求的三个 Grafana 看板（JSON）。

【为什么用脚本生成，而不是直接手写 JSON】
Grafana 看板的 JSON 有大量重复结构（每个 panel 都要 gridPos / datasource /
fieldConfig / targets），手写时有三个必然会犯的错：
  1) id 重复 —— Grafana 只在导入时报一句含糊的错误，定位要逐个面板翻；
  2) gridPos 重叠 —— 面板会互相盖住，且不报错；
  3) 漏写 datasource 的 uid —— 表现为 "Datasource not found"，
     在几十个面板里逐个重选是纯粹的浪费时间。
用生成器把这三件事变成代码里的同一个函数，改一次就全都对了。

产出：
  monitoring/grafana/dashboards/01-system-overview.json
  monitoring/grafana/dashboards/02-incident-detail.json
  monitoring/grafana/dashboards/03-agent-trace.json

重跑方式：
  python monitoring/grafana/gen_dashboards.py
（幂等：直接覆盖，不追加）
"""

import json
import os

# =============================================================================
#  常量
# =============================================================================

# 数据源 uid 必须与 provisioning/datasources/prometheus.yml 里声明的完全一致。
# 写错的表现是所有面板显示 "Datasource not found"，而 Grafana 不会提示
# 「你引用的 uid 不存在」——它只会报面板级错误。
DS_UID = "seckill-prometheus"

# Grafana 11.x 的 schema 版本。写成常量而不是散落在各处，
# 是因为它出现在每个看板的顶层，将来升级 Grafana 时只需改这一行。
SCHEMA_VERSION = 39

# 看板所在的文件夹由 provisioning/dashboards/dashboards.yml 决定，
# 这里不需要（也不应该）重复声明。

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboards")

# =============================================================================
#  面板构造助手
# =============================================================================

_panel_id = 0


def _next_id():
    global _panel_id
    _panel_id += 1
    return _panel_id


def reset_ids():
    global _panel_id
    _panel_id = 0


def _ds():
    """数据源引用。所有面板共用同一个 uid，因此收敛成一个函数。"""
    return {"type": "prometheus", "uid": DS_UID}


def timeseries(title, expr, x, y, w=12, h=8, unit="short", legend="{{uri}}",
               description="", decimals=None, min_val=None, max_val=None,
               thresholds=None):
    """时间序列面板。"""
    field = {
        "unit": unit,
        "custom": {
            "drawStyle": "line",
            "lineWidth": 2,
            "fillOpacity": 12,
            "gradientMode": "opacity",
            "showPoints": "never",
            "axisPlacement": "auto",
            "axisLabel": "",
            "spanNulls": False,
        },
    }
    if decimals is not None:
        field["decimals"] = decimals
    if min_val is not None:
        field["min"] = min_val
    if max_val is not None:
        field["max"] = max_val
    if thresholds:
        # 阈值线：把「告警规则里的数字」画在图上。
        # 【为什么必须成对出现】若图上有阈值线而告警规则用的是另一个数，
        # 看图的人会按图上那条线判断「还好」，而告警已经响了。
        # 因此这里传入的 thresholds 必须与 seckill-alerts.yml 保持一致。
        field["thresholds"] = {
            "mode": "absolute",
            "steps": thresholds,
        }
        field["custom"]["thresholdsStyle"] = {"mode": "dashed"}

    return {
        "id": _next_id(),
        "type": "timeseries",
        "title": title,
        "description": description,
        "datasource": _ds(),
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": field, "overrides": []},
        "options": {
            "legend": {
                "displayMode": "table",
                "placement": "bottom",
                "calcs": ["lastNotNull", "max"],
            },
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
        "targets": [
            {
                "refId": "A",
                "datasource": _ds(),
                "expr": expr,
                "legendFormat": legend,
                "range": True,
            }
        ],
    }


def stat(title, expr, x, y, w=4, h=5, unit="short", description="",
         thresholds=None, decimals=None, color_mode="value",
         legend="", instant=True):
    """单值面板（Stat）。

    instant=True 用即时查询：这类面板读的是 Gauge / Counter 的当前值，
    用区间查询会让它显示一段时间的聚合，语义上不对
    （「当前待投递积压」不该是 5 分钟平均值）。
    """
    field = {"unit": unit, "color": {"mode": "thresholds"}}
    if decimals is not None:
        field["decimals"] = decimals
    if thresholds is None:
        thresholds = [
            {"color": "green", "value": None},
            {"color": "red", "value": 80},
        ]
    field["thresholds"] = {"mode": "absolute", "steps": thresholds}

    return {
        "id": _next_id(),
        "type": "stat",
        "title": title,
        "description": description,
        "datasource": _ds(),
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": field, "overrides": []},
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": color_mode,
            "graphMode": "area",
            "justifyMode": "auto",
            "orientation": "auto",
            "textMode": "auto",
        },
        "targets": [
            {
                "refId": "A",
                "datasource": _ds(),
                "expr": expr,
                "legendFormat": legend,
                "instant": instant,
                "range": not instant,
            }
        ],
    }


def table(title, expr, x, y, w=24, h=10, description="", unit="short",
          transformations=None, overrides=None):
    """表格面板。用于「当前正在触发的告警」这类需要看标签的场景。"""
    return {
        "id": _next_id(),
        "type": "table",
        "title": title,
        "description": description,
        "datasource": _ds(),
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "custom": {"align": "auto", "cellOptions": {"type": "auto"}},
            },
            "overrides": overrides or [],
        },
        "options": {
            "showHeader": True,
            "cellHeight": "sm",
            "footer": {"show": False, "reducer": ["sum"], "countRows": False},
        },
        "transformations": transformations or [],
        "targets": [
            {
                "refId": "A",
                "datasource": _ds(),
                "expr": expr,
                "format": "table",
                "instant": True,
                "range": False,
            }
        ],
    }


def text_panel(title, content, x, y, w=24, h=6):
    """文本面板。用于在看板上直接写清「这块面板该怎么读」。"""
    return {
        "id": _next_id(),
        "type": "text",
        "title": title,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "options": {"mode": "markdown", "content": content},
        "transparent": False,
    }


def row(title, y, collapsed=False):
    """折叠行。把看板按「症状 / 归因 / 业务」分段，避免一屏塞满 30 个面板。"""
    return {
        "id": _next_id(),
        "type": "row",
        "title": title,
        "collapsed": collapsed,
        "gridPos": {"x": 0, "y": y, "w": 24, "h": 1},
        "panels": [],
    }


def alert_annotations():
    """告警注释层：把 ALERTS 序列标在每张图上。

    【为什么每张图都要有它】诊断时最常见的问题是「曲线变化与告警触发
    对不上时间」。把 ALERTS 画成注释之后，「P99 是什么时候开始涨的」
    与「告警是什么时候响的」在同一条时间轴上，归因会快得多。
    """
    return {
        "datasource": _ds(),
        "enable": True,
        "hide": False,
        "iconColor": "rgba(255, 96, 96, 1)",
        "name": "告警触发",
        "target": {
            "expr": "ALERTS{alertstate=\"firing\"}",
            "refId": "Anno",
        },
    }


def base_dashboard(title, uid, tags, description, panels, time_from="now-30m",
                   refresh="10s"):
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": tags,
        "timezone": "browser",
        "schemaVersion": SCHEMA_VERSION,
        "version": 1,
        "editable": False,
        "graphTooltip": 1,
        "refresh": refresh,
        "time": {"from": time_from, "to": "now"},
        "annotations": {"list": [alert_annotations()]},
        "templating": {"list": []},
        "panels": panels,
    }


# =============================================================================
#  看板 1：系统总览（SPEC 第 20 节「页面 1」）
# =============================================================================
#
#  这一页回答的问题是：「现在系统整体是什么状态，有没有事情需要我看一眼」。
#  因此它只放**总量与比率**，不放任何需要下钻才能看懂的东西 ——
#  下钻是页面 2 的职责。
# =============================================================================

def build_overview():
    reset_ids()
    p = []

    # ---- 顶部单值卡片：一屏之内回答「现在好不好」 ----
    p.append(stat(
        "服务状态", 'max(up{job="order-service"})',
        x=0, y=0, w=4, h=5, unit="short",
        description="1=Prometheus 能抓到应用指标；0=抓不到（见 ServiceDown 告警）",
        thresholds=[
            {"color": "red", "value": None},
            {"color": "green", "value": 1},
        ],
        legend="up",
    ))
    p.append(stat(
        "当前告警数",
        'count(ALERTS{alertstate="firing", severity=~"CRITICAL|HIGH"}) or vector(0)',
        x=4, y=0, w=4, h=5,
        description="处于 firing 状态的 CRITICAL/HIGH 告警数量",
        thresholds=[
            {"color": "green", "value": None},
            {"color": "yellow", "value": 1},
            {"color": "red", "value": 3},
        ],
    ))
    p.append(stat(
        "订单成功率(5m)",
        'seckill:order_success_rate:5m',
        x=8, y=0, w=4, h=5, unit="percentunit", decimals=4,
        description=(
            "分母是「已走到终态的消费侧消息数」= 确认 + 取消 + 重试耗尽。"
            "刻意不用受理数做分母：受理与确认之间隔着投递与消费两级异步，"
            "用受理数会让活动刚开始的几十秒必然显示低成功率。"
        ),
        thresholds=[
            {"color": "red", "value": None},
            {"color": "yellow", "value": 0.99},
            {"color": "green", "value": 0.995},
        ],
    ))
    p.append(stat(
        "API P99",
        'max(seckill:http_p99_latency:5m)',
        x=12, y=0, w=4, h=5, unit="s", decimals=3,
        description="所有接口中最大的 P99（看板下方有按 uri 的明细）",
        thresholds=[
            {"color": "green", "value": None},
            {"color": "yellow", "value": 0.5},
            {"color": "red", "value": 1},
        ],
    ))
    p.append(stat(
        "Outbox 待投递",
        'max(seckill:outbox_pending_now) or vector(0)',
        x=16, y=0, w=4, h=5, unit="short",
        description=(
            "status=PENDING 的 outbox 条数，**来自数据库 COUNT**，"
            "因此是真实欠账而非估算，多实例部署下依然准确。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "yellow", "value": 100},
            {"color": "red", "value": 1000},
        ],
    ))
    p.append(stat(
        "需人工介入",
        'max(seckill:manual_intervention_backlog) or vector(0)',
        x=20, y=0, w=4, h=5, unit="short",
        description=(
            "outbox FAILED + compensate PENDING。**这个数不会自愈**，"
            "大于 0 就意味着已经有库存账没平，必须有人动手。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 1},
        ],
    ))

    # ---- 链路速率：判断「卡在哪一段」 ----
    p.append(row("一、链路吞吐（判断卡在哪一段）", y=5))

    p.append(timeseries(
        "各阶段速率",
        'sum(rate(seckill_request_queued_total[1m]))',
        x=0, y=6, w=12, h=8, unit="reqps", legend="受理 (queued)",
        description=(
            "受理 = Redis 预扣成功并已写入预订单与待投递凭据。"
            "它与下面两条曲线的差值直接指出瓶颈在哪一级。"
        ),
    ))
    p.append(timeseries(
        "投递 / 确认速率对照",
        'sum(rate(seckill_mq_sent_total[1m]))',
        x=12, y=6, w=12, h=8, unit="reqps", legend="已投出 (mq.sent)",
        description=(
            "对照阅读方式：受理 ≈ 投出 > 确认 → 瓶颈在消费；"
            "受理 > 投出 → 瓶颈在投递器或 Broker；"
            "三者都低 → 瓶颈在入口（或没有流量）。"
        ),
    ))

    p.append(timeseries(
        "MQ 在途净增长速率",
        'sum(rate(seckill_mq_sent_total[5m])) - sum(rate(seckill_consume_confirmed_total[5m])) '
        '- sum(rate(seckill_consume_duplicate_total[5m])) - sum(rate(seckill_consume_order_missing_total[5m])) '
        '- sum(rate(seckill_consume_failed_total[5m]))',
        x=0, y=14, w=12, h=8, unit="short", legend="在途增长",
        description=(
            "四类消费结局（确认/重复/订单缺失/失败）全部计入在途，"
            "因此这个差值在系统空闲时会回到 0，**不会随运行时间单调漂移**。"
            "持续为正 = 队列在堆积。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 5},
        ],
    ))
    p.append(timeseries(
        "Outbox 欠账存量",
        'max(seckill_outbox_pending) by (service)',
        x=12, y=14, w=12, h=8, unit="short", legend="待投递 ({{service}})",
        description=(
            "存量类指标，用绝对值判断比用速率更合适："
            "「稳定积压但不增长」同样是需要处置的状态。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 1000},
        ],
    ))

    # ---- 业务指标 ----
    p.append(row("二、业务指标（用户真实感受）", y=22))

    p.append(timeseries(
        "订单确认成功率",
        'seckill:order_success_rate:5m',
        x=0, y=23, w=12, h=8, unit="percentunit", legend="成功率",
        description="低于 99% 触发 OrderSuccessRateLow（CRITICAL）",
        thresholds=[
            {"color": "red", "value": None},
            {"color": "green", "value": 0.99},
        ],
    ))
    p.append(timeseries(
        "下单受理 QPS",
        'seckill:order_accept_rate:1m',
        x=12, y=23, w=12, h=8, unit="reqps", legend="受理 QPS",
        description="Redis 预热后才有值；未预热时下单会直接返回 1003。",
    ))

    # ---- 依赖健康度 ----
    p.append(row("三、依赖健康度（MySQL / Redis）", y=31))

    p.append(timeseries(
        "数据库连接池",
        'hikaricp_connections_active{service="order-service"}',
        x=0, y=32, w=8, h=7, unit="short", legend="活跃连接",
        description=(
            "与下面「池上限」对照看。本项目已实测过：消费线程数超过池上限时，"
            "线程会互相等连接而非等锁，表现为连接池打满。"
        ),
    ))
    p.append(timeseries(
        "数据库连接池上限",
        'hikaricp_connections_max{service="order-service"}',
        x=8, y=32, w=8, h=7, unit="short", legend="池上限",
        description="来自 application.yaml 的 hikari.maximum-pool-size（默认 50）",
    ))
    p.append(timeseries(
        "Redis 命令 P99（客户端侧）",
        'histogram_quantile(0.99, sum by (le) (rate(lettuce_seconds_bucket[5m])))',
        x=16, y=32, w=8, h=7, unit="s", legend="Redis P99",
        description=(
            "Lettuce 客户端侧耗时，含网络往返 —— 比 Redis 自身的 SLOWLOG "
            "更接近应用的真实感受。同机容器正常应在 1ms 以内。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 0.05},
        ],
    ))

    # ---- 当前告警明细 ----
    p.append(row("四、当前告警明细", y=39))

    p.append(table(
        "正在触发的告警",
        'ALERTS{alertstate="firing"}',
        x=0, y=40, w=24, h=10,
        description=(
            "直接把 Prometheus 的 ALERTS 序列列成表格。"
            "它比 Alertmanager UI 更早看到告警（Alertmanager 还有 group_wait 与去重），"
            "因此在故障注入实验中用来确认「规则到底有没有触发」更可靠。"
        ),
    ))

    p.append(text_panel(
        "怎么用这一页",
        "**这一页只回答「现在好不好」，不回答「为什么」**。看到异常后应当：\n\n"
        "1. 记下异常开始的时间点（图上告警注释的位置）；\n"
        "2. 从「各阶段速率」判断卡在哪一级（受理 / 投递 / 消费）；\n"
        "3. 带着这个时间点与判断，进入 **页面 2：异常详情**，或直接看 AI Monitor 的诊断结果。\n\n"
        "**一个反直觉的读法**：`需人工介入 > 0` 比 `API P99 高` 更严重。\n"
        "前者是「已经有东西永久坏了」，后者是「现在慢」。后者流量退去可能自愈，前者不会。",
        x=0, y=50, w=24, h=7,
    ))

    return base_dashboard(
        title="1. 系统总览",
        uid="seckill-overview",
        tags=["seckill", "overview"],
        description=(
            "SPEC 第 20 节页面 1。回答「现在系统整体是什么状态」。"
            "所有阈值线均与 monitoring/prometheus/rules/seckill-alerts.yml 保持一致。"
        ),
        panels=p,
    )


# =============================================================================
#  看板 2：异常详情（SPEC 第 20 节「页面 2」）
# =============================================================================
#
#  这一页回答的问题是：「这次异常具体是什么，谁引起的，影响多大」。
#  它按 SPEC 给出的下钻顺序组织：异常 → 指标趋势 → 根因 → 证据 → 影响 → 建议。
#  其中「根因 / 证据 / 影响 / 建议」四块由 AI Monitor 产出，
#  因此这里用文本面板占位并写清数据来源 —— 而不是画一张永远不会更新的空图。
# =============================================================================

def build_incident_detail():
    reset_ids()
    p = []

    p.append(text_panel(
        "这一页要回答什么",
        "**异常详情页**：从「系统在哪儿慢」下钻到「是谁引起的、影响多大」。\n\n"
        "下钻顺序（与 SPEC 第 20 节一致）：\n"
        "`异常信息 → 指标趋势 → 根因 → 证据 → 影响范围 → 修复建议`\n\n"
        "**前两块（异常信息、指标趋势）由本页的 Prometheus 面板直接给出；**\n"
        "**后四块由 AI Monitor Agent 产出**，本页底部给出了它们的查询入口。\n"
        "刻意不在这里伪造静态文本 —— 诊断结论必须来自 Agent 对真实数据的推理。",
        x=0, y=0, w=24, h=7,
    ))

    p.append(row("一、异常信息（发生了什么）", y=7))

    p.append(table(
        "当前 firing 的告警",
        'ALERTS{alertstate="firing"}',
        x=0, y=8, w=12, h=9,
        description=(
            "把 status/db/redis/mq/outbox/business 六个维度分别看清楚。"
            "**关键判据**：同一个时间窗内是否有多个维度同时异常 ——"
            "那通常意味着一个共享依赖（最常见是 MySQL）而不是多个独立故障。"
        ),
    ))
    p.append(timeseries(
        "P99 延迟（按接口）",
        'seckill:http_p99_latency:5m',
        x=12, y=8, w=12, h=9, unit="s", legend="{{uri}} / {{status}}",
        description=(
            "若只有某一个 uri 慢 → 大概率是该接口的查询/SQL 问题；"
            "若所有 uri 一起慢 → 大概率是共享资源（DB 连接池、Redis、线程池）。"
            "这个区分决定了 Agent 下一步该调哪个 Tool。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 1},
        ],
    ))

    p.append(row("二、指标趋势（横向对比各个嫌疑人）", y=17))

    p.append(timeseries(
        "① MySQL：慢请求（>1s）速率",
        'sum(rate(http_server_requests_seconds_count{uri="/seckill"}[5m])) '
        '- sum(rate(http_server_requests_seconds_bucket{uri="/seckill",le="1.0"}[5m]))',
        x=0, y=18, w=8, h=8, unit="short", legend="慢请求/秒",
        description=(
            "Case 1（MySQL 慢 SQL）的特征信号。"
            "注意它是**近似**指标 —— 精确的慢 SQL 明细来自 DB Tool 的 get_slow_sql()，"
            "后者读 MySQL 的 performance_schema。指标用于发现，工具用于取证。"
        ),
    ))
    p.append(timeseries(
        "② Redis：命令 P99",
        'histogram_quantile(0.99, sum by (le) (rate(lettuce_seconds_bucket[5m])))',
        x=8, y=18, w=8, h=8, unit="s", legend="Redis P99",
        description=(
            "Case 2（Redis 延迟）的特征信号。"
            "若 Redis P99 与 API P99 同步抬升、而 MySQL 侧无异常 → 根因在 Redis。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 0.05},
        ],
    ))
    p.append(timeseries(
        "③ MQ：在途净增长速率",
        'sum(rate(seckill_mq_sent_total[5m])) - sum(rate(seckill_consume_confirmed_total[5m])) '
        '- sum(rate(seckill_consume_duplicate_total[5m])) - sum(rate(seckill_consume_order_missing_total[5m])) '
        '- sum(rate(seckill_consume_failed_total[5m]))',
        x=16, y=18, w=8, h=8, unit="short", legend="在途增长/秒",
        description="Case 3（消费变慢）的特征信号。持续为正 = 堆积在扩大。",
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 5},
        ],
    ))

    p.append(timeseries(
        "④ Outbox：待投递存量",
        'max(seckill_outbox_pending) by (service)',
        x=0, y=26, w=8, h=8, unit="short", legend="待投递",
        description=(
            "Case 4（Outbox 积压）的特征信号。"
            "**关键判据**：它涨的同时 `seckill_mq_send_failed_total` 涨不涨？"
            "涨 → Broker 可达性问题；不涨 → 投递器本身（调度阻塞 / 批量发送整体超时）。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 1000},
        ],
    ))
    p.append(timeseries(
        "⑤ 投递失败速率（与④对照读）",
        'sum(rate(seckill_mq_send_failed_total[5m]))',
        x=8, y=26, w=8, h=8, unit="short", legend="投递失败/秒",
        description="与 Outbox 存量对照，区分「Broker 不可达」与「投递器自身卡住」。",
    ))
    p.append(timeseries(
        "⑥ 业务：订单成功率",
        'seckill:order_success_rate:5m',
        x=16, y=26, w=8, h=8, unit="percentunit", legend="成功率",
        description=(
            "**影响范围**的核心证据。技术指标异常但成功率未降 → 影响可控；"
            "成功率同步下降 → 已经影响用户，严重度上升。"
        ),
        thresholds=[
            {"color": "red", "value": None},
            {"color": "green", "value": 0.99},
        ],
    ))

    p.append(row("三、根因与证据（由 AI Monitor Agent 产出）", y=34))

    p.append(table(
        "数据一致性：对账结论",
        'sum by (status) (rate(seckill_reconcile_findings_total[10m])) > 0',
        x=0, y=35, w=12, h=9,
        description=(
            "Case 5（Redis / MySQL 不一致）的特征信号。"
            "status 标签本身就是重要证据：REDIS_AHEAD 说明有扣减绕过了 Redis（降级写库），"
            "STALE_PENDING / ABANDONED_PENDING 说明有预订单不会自己走向终态。"
        ),
    ))
    p.append(table(
        "需人工介入的欠账明细",
        'seckill_outbox_failed or seckill_compensate_pending',
        x=12, y=35, w=12, h=9,
        description=(
            "终态失败的存量。它们**不会自愈**，且直接对应「少卖」这个业务损失。"
            "Agent 给出的建议应当是「调用 /seckill/reconcile 查看对账结论」，"
            "而**不是**直接修改数据（SPEC 第 19 节 Case 5 的明确要求）。"
        ),
    ))

    p.append(text_panel(
        "Agent 诊断结果的查询入口",
        "根因、证据、影响范围、修复建议这四块**不在这里硬编码**，"
        "它们由 AI Monitor Agent 基于本次异常的实际数据推理产出。获取方式：\n\n"
        "| 用途 | 接口 |\n|---|---|\n"
        "| 触发一次诊断 | `POST /api/ai/diagnosis` |\n"
        "| 查诊断结果（根因/证据/影响/建议） | `GET /api/ai/diagnosis/{incidentId}` |\n"
        "| 查 Tool Calling 完整轨迹 | `GET /api/ai/diagnosis/{incidentId}/tools` |\n"
        "| 查历史诊断 | `GET /api/ai/diagnosis/history` |\n\n"
        "**为什么把它们放在接口而不是看板上**：诊断结论是「一次推理的产物」，"
        "它带时间戳、带证据引用、带置信度，是一份**记录**而不是一条**时间序列**。"
        "硬塞进 Grafana 只能做成静态文本，反而会让人误以为它是实时结论。\n\n"
        "完整链路（含 Agent 的每一步 Tool 调用）见 **页面 3：Agent 执行链路**。",
        x=0, y=44, w=24, h=12,
    ))

    return base_dashboard(
        title="2. 异常详情",
        uid="seckill-incident-detail",
        tags=["seckill", "incident"],
        description=(
            "SPEC 第 20 节页面 2。从「系统在哪儿慢」下钻到「是谁引起的、影响多大」。"
        ),
        panels=p,
        time_from="now-1h",
    )


# =============================================================================
#  看板 3：Agent 执行链路（SPEC 第 20 节「页面 3」）
# =============================================================================
#
#  【这一页的性质与其他两页不同】它展示的不是 Prometheus 指标，
#  而是 AI Monitor 的**诊断任务与 Tool 调用轨迹**（存在 MySQL 的
#  ai_diagnosis_task / ai_diagnosis_result / ai_tool_execution 三张表里）。
#
#  因此这里有一个真实的技术选择：Grafana 直连 MySQL 还是走后端 API？
#  本看板选择**走后端 API**，理由有两条：
#    1) 直连 MySQL 需要给 Grafana 配数据库账号，而这个账号会是只读的 ——
#       等于把一个「能看全部业务表」的凭据交给了一个展示层；
#    2) 诊断结果里的 evidence / impact / suggestion 是 JSON 列，
#       Grafana 的 MySQL 数据源对 JSON 的展开能力很弱，最终仍要做成文本。
#  代价是这些面板依赖后端在线 —— 这是可接受的：后端不在线时，
#  这一页本来也没有任何东西可展示。
#
#  因此本看板的主体是**文本 + 链接**的结构，Prometheus 面板只用于标注
#  「Agent 的推理发生在故障曲线的哪一段」。
# =============================================================================

def build_agent_trace():
    reset_ids()
    p = []

    p.append(text_panel(
        "这一页要回答什么",
        "**Agent 执行链路页**：证明诊断不是「问了一次大模型」，"
        "而是**基于真实系统数据、多轮取证、可追溯**的过程。\n\n"
        "SPEC 第 20 节把这一页标为「特别适合面试演示」，原因是它同时展示三件事：\n"
        "1. Agent **自主选择**了哪些 Tool（而不是固定顺序跑一遍）；\n"
        "2. 每一步 Tool 调用的**真实入参与真实返回**（存在 `ai_tool_execution` 表）；\n"
        "3. 结论是**怎么从证据推出来的**（evidence 与 root_cause 的对应关系）。",
        x=0, y=0, w=24, h=8,
    ))

    p.append(row("一、诊断发生的时刻（把推理标在故障曲线上）", y=8))

    p.append(timeseries(
        "API P99 与告警触发点",
        'seckill:http_p99_latency:5m',
        x=0, y=9, w=12, h=8, unit="s", legend="{{uri}}",
        description=(
            "图上的红色注释就是 ALERTS 的触发时刻。"
            "Agent 的 Tool 调用时间窗应当紧跟在这条注释之后 ——"
            "若两者相隔很远，说明诊断任务被排在了队列里（Incident 聚合或线程池饱和），"
            "这本身就是一个值得记录的性能问题。"
        ),
        thresholds=[
            {"color": "green", "value": None},
            {"color": "red", "value": 1},
        ],
    ))
    p.append(timeseries(
        "证据来源的四个维度（同时抬高 = 同一根因）",
        'sum(rate(seckill_request_degraded_total[5m]))',
        x=12, y=9, w=12, h=8, unit="short", legend="Redis 降级次数/秒",
        description=(
            "把「可能的证据来源」画在同一条时间轴上，是判断 Agent 结论是否可信的"
            "最直接手段：**若 Agent 说是 MySQL 的问题，但 MySQL 侧曲线全程平坦、"
            "而 Redis 降级在同步抬升，那么它的结论就是错的**。"
            "这一页的价值有一半在于让结论可被证伪。"
        ),
    ))

    p.append(row("二、Agent 工作流（SPEC 第 8.2 节）", y=17))

    p.append(text_panel(
        "Tool Calling / ReAct 流程",
        "```text\n"
        "Alert Event\n"
        "    ↓\n"
        "Agent（读取 Alert Context + System Prompt）\n"
        "    ↓\n"
        "分析当前异常 → 选择 Tool ──────────┐\n"
        "    ↑                              │\n"
        "    │                        Tool 执行（只读）\n"
        "    │                              │\n"
        "    └──── Tool Result ◀────────────┘\n"
        "         （写 ai_tool_execution）\n"
        "    ↓\n"
        "满足证据条件（≥2 个独立证据）\n"
        "    ↓\n"
        "生成结构化 Diagnosis（JSON Schema 校验）\n"
        "```\n\n"
        "**关键设计**：Agent **不强制执行固定顺序**，而是基于当前异常动态选择工具。\n"
        "SPEC 第 12 节给了一个反例：拿到「P99 高」就立刻下结论是错的 ——"
        "它必须继续查 DB、查日志、查 MQ，直到有至少两个**互相独立**的证据。",
        x=0, y=18, w=12, h=13,
    ))

    p.append(text_panel(
        "五个 Tool 与它们各自回答的问题",
        "| Tool | 回答的问题 | 数据来源 |\n"
        "|---|---|---|\n"
        "| **Metrics** | 现在哪一项指标偏离了基线？ | Prometheus HTTP API |\n"
        "| **Logs** | 应用自己说了什么错误？ | 应用日志（已脱敏） |\n"
        "| **DB** | 数据库侧的实情是什么？ | MySQL performance_schema / 慢查询日志 |\n"
        "| **MQ** | 消息是不是堆住了？ | Broker 消费位点 + 应用侧计数 |\n"
        "| **Business** | 用户实际受到了什么影响？ | orders / stock / outbox / Redis |\n\n"
        "**为什么必须分成五个而不是合成一个「查一下」**：\n"
        "每个 Tool 对应一个**可以独立证伪**的假设。合成一个之后，"
        "Agent 的推理过程就变成了一次不可审计的黑盒调用，"
        "而 SPEC 第 9 节要求「根因判断至少需要两个独立证据」——"
        "「独立」这件事只能在 Tool 边界上被验证。",
        x=12, y=18, w=12, h=13,
    ))

    p.append(row("三、Tool Trace 与诊断结果（来自 ai_tool_execution / ai_diagnosis_result）", y=31))

    p.append(text_panel(
        "为什么 Tool Trace 是可解释性的关键",
        "SPEC 第 14.3 节的原话：`ai_tool_execution` 是 Agent 可解释性的关键，"
        "用于**完整保存 Tool Calling Trace**。\n\n"
        "它保存的不只是「调用了哪个工具」，而是四件事：\n"
        "1. **arguments（JSON）**：Agent 当时带着什么参数去查 —— 能看出它的假设是什么；\n"
        "2. **result（JSON）**：它实际看到了什么 —— 能验证证据确实存在，而不是编的；\n"
        "3. **execution_time_ms**：每次取证花了多久 —— 对应 SPEC 第 25 节「Tool 单次调用 < 1s」；\n"
        "4. **status**：成功还是失败 —— **失败的 Tool 调用同样要留痕**。\n\n"
        "第 4 点最容易被忽略：若 Agent 因为某个 Tool 报错而转向了另一条推理路径，"
        "只记录成功的调用会让整条轨迹看起来「证据不足却下了结论」。\n"
        "把失败也记下来，轨迹才真正可复现。",
        x=0, y=32, w=24, h=12,
    ))

    p.append(text_panel(
        "接口清单（这一页的数据从这里来）",
        "| 目的 | 请求 | 返回 |\n"
        "|---|---|---|\n"
        "| 创建诊断任务 | `POST /api/ai/diagnosis` | incidentId + 任务状态 |\n"
        "| 查诊断结果 | `GET /api/ai/diagnosis/{incidentId}` | root_cause / confidence / evidence / impact / suggestions |\n"
        "| 查 Tool Trace | `GET /api/ai/diagnosis/{incidentId}/tools` | 按时间排序的完整 Tool 调用序列 |\n"
        "| 查历史 | `GET /api/ai/diagnosis/history` | 历史 Incident 列表 |\n\n"
        "**演示建议**：先把本页与页面 2 并排打开，然后依次触发 SPEC 第 19 节的五个"
        "故障注入 Case，观察 Agent 在不同 Case 下**选择 Tool 的顺序不同**。"
        "这一点比任何架构图都更能说明「它不是固定流程的脚本」。",
        x=0, y=44, w=24, h=11,
    ))

    return base_dashboard(
        title="3. Agent 执行链路",
        uid="seckill-agent-trace",
        tags=["seckill", "ai-agent"],
        description=(
            "SPEC 第 20 节页面 3。展示诊断任务与 Tool Calling 轨迹，"
            "用于证明诊断基于真实数据、多轮取证、可追溯。"
        ),
        panels=p,
        time_from="now-1h",
        refresh="30s",
    )


# =============================================================================
#  主流程
# =============================================================================

def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    dashboards = [
        ("01-system-overview.json", build_overview()),
        ("02-incident-detail.json", build_incident_detail()),
        ("03-agent-trace.json", build_agent_trace()),
    ]

    for filename, dash in dashboards:
        path = os.path.join(OUT_DIR, filename)
        with open(path, "w", encoding="utf-8") as f:
            # ensure_ascii=False：看板里有大量中文标题，转义成 \uXXXX 之后
            # 在 Git diff 里完全不可读 —— 而「看板变更可 review」正是
            # 选择文件式 provisioning 的理由之一。
            json.dump(dash, f, ensure_ascii=False, indent=2)
            f.write("\n")

        # 自校验：重新读回来确认 JSON 合法、uid 与 id 没有重复。
        # 【为什么要在生成器里做这件事】Grafana 对 id 重复与 JSON 语法错误
        # 都只在导入时给一句含糊的错误。在源头校验的代价接近零。
        with open(path, "r", encoding="utf-8") as f:
            check = json.load(f)
        ids = [panel["id"] for panel in check["panels"]]
        assert len(ids) == len(set(ids)), f"{filename}: panel id 重复 -> {ids}"
        assert check["uid"], f"{filename}: 缺少 uid"
        print(f"  OK  {filename}  panels={len(ids)}  uid={check['uid']}")

    print(f"\n看板已生成到：{OUT_DIR}")


if __name__ == "__main__":
    main()
