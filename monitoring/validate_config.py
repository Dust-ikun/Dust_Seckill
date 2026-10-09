#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
可观测性栈的配置校验器。

【为什么需要这个脚本】
本目录下这些文件有一个共同的失败模式：**写错了不报错**。
  - prometheus.yml 缩进错一格 → 容器起不来，但只报一句 YAML 语法错误，不指行号；
  - rules/*.yml 里漏了 `expr` → Prometheus 加载成功，该规则永远停在 inactive；
  - alertmanager.yml 的路由 matcher 写错 → 告警正常产生但永远收不到通知；
  - grafana provisioning 的 uid 与看板 JSON 不一致 → 所有面板报
    "Datasource not found"，而 Grafana 不会提示「你引用的 uid 不存在」。

这些问题的共同点是：**症状出现在很远的地方**，排查成本远高于校验成本。
因此每次改完配置都应当跑一次本脚本。

用法：
    <python> monitoring/validate_config.py

退出码：0 = 全部通过；1 = 有错误（错误清单打印在 stdout）。
"""

import json
import os
import re
import sys

try:
    import yaml
except ImportError:
    print("需要 PyYAML。可用带 PyYAML 的解释器，例如：")
    print(r"  D:\Anaconda_envs\envs\agentRag\python.exe monitoring\validate_config.py")
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

errors = []
warnings = []
checks = 0


def ok(msg):
    global checks
    checks += 1
    print(f"  [OK]   {msg}")


def fail(msg):
    global checks
    checks += 1
    errors.append(msg)
    print(f"  [FAIL] {msg}")


def warn(msg):
    global checks
    checks += 1
    warnings.append(msg)
    print(f"  [WARN] {msg}")


def load_yaml(relpath):
    path = os.path.join(ROOT, relpath)
    try:
        with open(path, "r", encoding="utf-8") as f:
            return yaml.safe_load(f)
    except FileNotFoundError:
        fail(f"{relpath}: 文件不存在")
        return None
    except yaml.YAMLError as e:
        fail(f"{relpath}: YAML 语法错误 -> {e}")
        return None


def load_json(relpath):
    path = os.path.join(ROOT, relpath)
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        fail(f"{relpath}: 文件不存在")
        return None
    except json.JSONDecodeError as e:
        fail(f"{relpath}: JSON 语法错误 -> {e}")
        return None


# =============================================================================
#  1. docker-compose.yml
# =============================================================================
print("\n=== 1. docker-compose.yml ===")

compose = load_yaml("docker-compose.yml")
if compose is not None:
    ok("YAML 语法正确")

    services = compose.get("services", {})
    ok(f"服务数量 = {len(services)}: {', '.join(sorted(services))}")

    # --- 每个服务都必须是官方镜像且带明确 tag ---
    # tag 用 latest 是配置漂移的头号来源：今天能跑、明天重建就跑不起来，
    # 而中间没有任何变更记录可查。
    for name, svc in services.items():
        image = svc.get("image", "")
        if not image:
            fail(f"services.{name}: 缺少 image")
        elif image.endswith(":latest") or ":" not in image.split("/")[-1]:
            fail(f"services.{name}: 镜像未固定版本 -> {image}")
        else:
            ok(f"services.{name}: {image}")

    # --- 所有具名卷都必须被声明 ---
    declared_volumes = set((compose.get("volumes") or {}).keys())
    used_named = set()
    for name, svc in services.items():
        for vol in svc.get("volumes", []) or []:
            if isinstance(vol, str) and not vol.startswith((".", "/", "~")) and ":" in vol:
                used_named.add(vol.split(":")[0])
    missing = used_named - declared_volumes
    if missing:
        fail(f"以下卷被使用但未在顶层 volumes 声明: {sorted(missing)}")
    else:
        ok(f"具名卷声明完整（{len(declared_volumes)} 个）")

    # --- 依赖就绪条件：必须用 service_healthy 而不是默认的 service_started ---
    # 【为什么这条检查值得存在】`depends_on: [mysql]` 只保证「容器创建了」，
    # 不保证「mysqld 能接受连接」。Broker 先于 NameServer 就绪启动时会注册失败，
    # 而失败是静默的（Broker 会重试注册，但日志里不显眼）。
    #
    # 【合法的 condition 有三个，不是两个】最初这里只认 service_healthy 与
    # service_started，于是对 `service_completed_successfully` 报错 ——
    # 而它恰恰是「一次性 init 容器」唯一正确的条件（本项目的 rocketmq-init
    # 就是用它来保证卷属主已修好）。会误报的检查会被习惯性忽略，所以必须补全。
    VALID_CONDITIONS = {"service_healthy", "service_started", "service_completed_successfully"}
    for name, svc in services.items():
        dep = svc.get("depends_on")
        if isinstance(dep, dict):
            for d, cond in dep.items():
                if cond.get("condition") not in VALID_CONDITIONS:
                    fail(f"services.{name}.depends_on.{d}: condition="
                         f"{cond.get('condition')!r} 不是合法值，可选 "
                         f"{sorted(VALID_CONDITIONS)}")
            ok(f"services.{name}: depends_on 显式声明了就绪条件")
        elif isinstance(dep, list):
            warn(f"services.{name}: depends_on 是列表形式，只保证「已创建」，"
                 f"建议改为 condition: service_healthy -> {dep}")

    # --- profile 引用的服务必须存在 ---
    all_profiles = set()
    for svc in services.values():
        for p in svc.get("profiles", []) or []:
            all_profiles.add(p)
    ok(f"profiles = {sorted(all_profiles)}")

    # --- 宿主机端口不得与原生 MySQL(3306) 冲突 ---
    #
    # 【只检查「宿主机侧」端口，不检查容器侧】
    # 端口映射 `A:B` 里 A 是宿主机端口、B 是容器内端口。
    # `3307:3306` 是**正确**的写法：容器内仍是 MySQL 默认的 3306，
    # 只有宿主机侧换成了 3307 以避开原生 MySQL84。
    # 若把 B=3306 也当成冲突，这条检查会对着正确的配置报错 ——
    # 一个会误报的检查比没有检查更糟，因为它会被习惯性忽略。
    published = []
    for name, svc in services.items():
        for p in svc.get("ports", []) or []:
            if isinstance(p, str):
                published.append((name, p))
    conflict = []
    for name, p in published:
        # 形如 "127.0.0.1:9091:9091" 或 "3307:3306"
        parts = p.split(":")
        host_port = parts[-2]
        if host_port == "3306":
            conflict.append((name, p))
    if conflict:
        fail(f"以下映射占用了宿主机的 3306，会与本机原生 MySQL84 冲突: {conflict}")
    else:
        ok("宿主机侧无端口占用 3306（3307:3306 的容器侧端口不算冲突）")

    # --- healthcheck 必须有 start_period 或足够 retries ---
    #
    # 【一次性 init 容器例外】rocketmq-init 跑完 chown 就退出，它没有、
    # 也不该有 healthcheck —— 判断它是否成功的依据是退出码，
    # compose 里用 `service_completed_successfully` 表达。
    # 给它加 healthcheck 反而会制造「容器已退出所以不健康」的假告警。
    for name, svc in services.items():
        # 靠 restart policy 识别一次性任务容器（restart: "no"）
        is_one_shot = str(svc.get("restart", "")).strip('"') == "no"
        if "healthcheck" in svc:
            hc = svc["healthcheck"]
            if not hc.get("start_period"):
                warn(f"services.{name}.healthcheck: 未设 start_period，"
                     f"冷启动（首次初始化数据卷）时可能被误判为不健康")
        elif is_one_shot:
            ok(f"services.{name}: 一次性任务容器，不需要 healthcheck（用退出码判断）")
        else:
            warn(f"services.{name}: 无 healthcheck")

# =============================================================================
#  2. Prometheus 主配置
# =============================================================================
print("\n=== 2. monitoring/prometheus/prometheus.yml ===")

prom = load_yaml("monitoring/prometheus/prometheus.yml")
if prom is not None:
    ok("YAML 语法正确")

    si = prom.get("global", {}).get("scrape_interval", "15s")
    ei = prom.get("global", {}).get("evaluation_interval", "15s")
    ok(f"scrape_interval={si}, evaluation_interval={ei}")

    # 【关键一致性检查】告警规则的 `for` 必须 > 采集周期。
    # 否则「连续两次求值都成立」这个条件永远无法满足，告警不会触发。
    m = re.match(r"^(\d+)s$", str(si))
    scrape_sec = int(m.group(1)) if m else None

    # --- 采集目标 ---
    jobs = {j["job_name"]: j for j in prom.get("scrape_configs", []) or []}
    ok(f"采集 job = {sorted(jobs)}")

    if "order-service" not in jobs:
        fail("缺少 job 'order-service'（业务应用指标的唯一来源）")
    else:
        tgt = jobs["order-service"]["static_configs"][0]["targets"][0]
        if not str(tgt).startswith("host.docker.internal"):
            fail(f"order-service 目标 {tgt} 不是 host.docker.internal —— "
                 f"Prometheus 在容器内，用 localhost 会指向它自己")
        else:
            ok(f"order-service 目标 = {tgt}")

    # --- 规则文件路径 ---
    rf = prom.get("rule_files", []) or []
    if not rf:
        fail("rule_files 为空 —— 告警规则永远不会被加载")
    else:
        ok(f"rule_files = {rf}")

    # --- 告警投递 ---
    am = prom.get("alerting", {}).get("alertmanagers", []) or []
    if not am:
        fail("未配置 alertmanagers —— 告警不会离开 Prometheus")
    else:
        ok(f"alertmanagers = {am[0].get('static_configs')}")

# =============================================================================
#  3. Prometheus 规则文件
# =============================================================================
print("\n=== 3. monitoring/prometheus/rules/ ===")

RULES_DIR = os.path.join(ROOT, "monitoring", "prometheus", "rules")
alert_names = set()
record_names = set()

if not os.path.isdir(RULES_DIR):
    fail("rules 目录不存在")
else:
    rule_files = sorted(f for f in os.listdir(RULES_DIR) if f.endswith((".yml", ".yaml")))
    if not rule_files:
        fail("rules 目录下没有 YAML 文件")
    for filename in rule_files:
        rel = f"monitoring/prometheus/rules/{filename}"
        data = load_yaml(rel)
        if data is None:
            continue
        ok(f"{filename}: YAML 语法正确")

        for gi, group in enumerate(data.get("groups", []) or []):
            gname = group.get("name")
            if not gname:
                fail(f"{filename}: groups[{gi}] 缺少 name")
                continue
            rules = group.get("rules", []) or []
            if not rules:
                fail(f"{filename}: group '{gname}' 没有规则")

            for ri, rule in enumerate(rules):
                loc = f"{filename}: group '{gname}' rules[{ri}]"

                # --- 规则必须**恰好**是 alert 或 record 之一 ---
                has_alert = "alert" in rule
                has_record = "record" in rule
                if has_alert == has_record:
                    fail(f"{loc}: 必须且只能有 alert 或 record 之一")
                    continue

                if not rule.get("expr"):
                    fail(f"{loc}: 缺少 expr —— 该规则会永远停在 inactive")
                    continue

                # --- 规则名唯一性 ---
                # 重名的后果是后者覆盖前者，而 Prometheus 不报错。
                # 告警重名的危害尤其大：你以为是 A 规则在响，其实是 B。
                if has_alert:
                    name = rule["alert"]
                    if name in alert_names:
                        fail(f"{loc}: 告警名重复 -> {name}")
                    alert_names.add(name)

                    # --- alert 必须有 labels.severity ---
                    sev = (rule.get("labels") or {}).get("severity")
                    if sev not in ("CRITICAL", "HIGH", "WARNING", "INFO"):
                        fail(f"{loc}: severity 缺失或非标准值 -> {sev}")
                    # --- 必须有 summary 注释（通知内容的主体）---
                    if not (rule.get("annotations") or {}).get("summary"):
                        fail(f"{loc}: 缺少 annotations.summary")
                    # --- `for` 必须长于采集周期 ---
                    if scrape_sec:
                        fr = rule.get("for")
                        if fr:
                            mm = re.match(r"^(\d+)([smh])$", str(fr))
                            if mm:
                                val, unit = int(mm.group(1)), mm.group(2)
                                secs = val * {"s": 1, "m": 60, "h": 3600}[unit]
                                if secs < scrape_sec:
                                    fail(f"{loc}: for={fr} 短于采集周期 {si} —— "
                                         f"告警可能永远无法进入 firing")
                else:
                    name = rule["record"]
                    if name in record_names:
                        fail(f"{loc}: 记录规则名重复 -> {name}")
                    record_names.add(name)
                    # --- 记录规则命名约定：派生指标应当带冒号 ---
                    if ":" not in name:
                        warn(f"{loc}: 记录规则 '{name}' 不含冒号，"
                             f"社区约定派生指标用 `level:metric:operations` 命名")

    ok(f"告警规则 {len(alert_names)} 条 / 记录规则 {len(record_names)} 条")

# =============================================================================
#  4. 跨文件一致性：告警名引用
# =============================================================================
print("\n=== 4. 跨文件一致性 ===")

# inhibit_rules 里引用的 alertname 必须真的存在。
# 引用一个不存在的告警名，抑制规则会静默失效（source 永不匹配）。
if prom is not None:
    pass  # prometheus.yml 本身不写 inhibit_rules

am = load_yaml("monitoring/alertmanager/alertmanager.yml")
if am is not None:
    ok("alertmanager.yml: YAML 语法正确")

    if not am.get("route", {}).get("receiver"):
        fail("alertmanager.yml: route 缺少 receiver")
    else:
        ok(f"默认 receiver = {am['route']['receiver']}")

    receiver_names = {r["name"] for r in am.get("receivers", []) or []}
    ok(f"receivers = {sorted(receiver_names)}")

    # --- route 树里引用的 receiver 必须都已定义 ---
    def walk_routes(route, depth=0):
        recv = route.get("receiver")
        if recv and recv not in receiver_names:
            fail(f"alertmanager.yml: route 引用了未定义的 receiver -> {recv}")
        for sub in route.get("routes", []) or []:
            walk_routes(sub, depth + 1)

    walk_routes(am.get("route", {}))

    # --- 每个 receiver 至少要有一个可投递的出口 ---
    # 空 receiver 是合法配置，但它会让告警「产生后消失」，
    # 在排查「为什么我没收到通知」时极其费时间。
    for r in am.get("receivers", []) or []:
        outlets = [k for k in r if k != "name"]
        if not outlets:
            warn(f"alertmanager.yml: receiver '{r['name']}' 没有任何出口，"
                 f"发给它的告警会被静默丢弃")

    # --- 【关键】inhibit_rules 引用的告警名必须真实存在 ---
    for ii, rule in enumerate(am.get("inhibit_rules", []) or []):
        for side in ("source_matchers", "target_matchers"):
            for matcher in rule.get(side, []) or []:
                # matcher 形如 `alertname = "ServiceDown"` 或 `alertname =~ "A|B"`
                mm = re.match(r'^\s*alertname\s*(=~|=)\s*"(.+)"\s*$', matcher)
                if not mm:
                    continue
                op, pattern = mm.group(1), mm.group(2)
                if op == "=":
                    if pattern not in alert_names:
                        fail(f"alertmanager.yml: inhibit_rules[{ii}].{side} 引用的告警 "
                             f"'{pattern}' 不存在 —— 该抑制规则会静默失效")
                else:
                    rx = re.compile(f"^(?:{pattern})$")
                    if not any(rx.match(n) for n in alert_names):
                        fail(f"alertmanager.yml: inhibit_rules[{ii}].{side} 的模式 "
                             f"'{pattern}' 匹配不到任何已定义的告警")
    ok("抑制规则引用的告警名全部存在")

# =============================================================================
#  5. Grafana provisioning 与看板
# =============================================================================
print("\n=== 5. monitoring/grafana/ ===")

ds = load_yaml("monitoring/grafana/provisioning/datasources/prometheus.yml")
ds_uids = set()
if ds is not None:
    ok("datasources/prometheus.yml: YAML 语法正确")
    for d in ds.get("datasources", []) or []:
        if not d.get("uid"):
            fail(f"数据源 '{d.get('name')}' 缺少 uid —— 看板会报 Datasource not found")
        else:
            ds_uids.add(d["uid"])
            ok(f"数据源 {d['name']} uid={d['uid']} type={d.get('type')}")
            # url 必须用容器服务名，不能用 localhost
            url = d.get("url", "")
            if "localhost" in url or "127.0.0.1" in url:
                fail(f"数据源 {d['name']} 的 url 用了回环地址 {url} —— "
                     f"Grafana 在容器内，localhost 指向它自己")

prov = load_yaml("monitoring/grafana/provisioning/dashboards/dashboards.yml")
if prov is not None:
    ok("dashboards/dashboards.yml: YAML 语法正确")
    for p in prov.get("providers", []) or []:
        ok(f"看板 provider '{p.get('name')}' path={p.get('options', {}).get('path')}")

DASH_DIR = os.path.join(ROOT, "monitoring", "grafana", "dashboards")
if not os.path.isdir(DASH_DIR):
    fail("grafana/dashboards 目录不存在")
else:
    dash_files = sorted(f for f in os.listdir(DASH_DIR) if f.endswith(".json"))
    if not dash_files:
        fail("grafana/dashboards 下没有 JSON 看板")
    seen_uids = set()
    for filename in dash_files:
        dash = load_json(f"monitoring/grafana/dashboards/{filename}")
        if dash is None:
            continue
        uid = dash.get("uid")
        if not uid:
            fail(f"{filename}: 缺少 uid")
            continue
        if uid in seen_uids:
            fail(f"{filename}: uid '{uid}' 与其它看板重复")
        seen_uids.add(uid)

        # --- 每个 panel 都必须引用已声明的数据源 uid ---
        bad_ds = []
        panel_ids = []
        for panel in dash.get("panels", []) or []:
            panel_ids.append(panel.get("id"))
            pds = panel.get("datasource")
            if isinstance(pds, dict) and pds.get("uid") not in ds_uids:
                bad_ds.append((panel.get("title"), pds.get("uid")))
            for t in panel.get("targets", []) or []:
                tds = t.get("datasource")
                if isinstance(tds, dict) and tds.get("uid") not in ds_uids:
                    bad_ds.append((panel.get("title"), tds.get("uid")))

        if bad_ds:
            fail(f"{filename}: 引用了未声明的数据源 uid -> {set(x[1] for x in bad_ds)}")
        # --- panel id 必须唯一 ---
        if len(panel_ids) != len(set(panel_ids)):
            fail(f"{filename}: panel id 重复 -> {panel_ids}")
        ok(f"{filename}: uid={uid} panels={len(panel_ids)} 数据源引用正确")

# =============================================================================
#  6. AI Monitor 表结构
# =============================================================================
print("\n=== 6. src/main/resources/db/schema-ai-monitor.sql ===")

sql_path = os.path.join(ROOT, "src", "main", "resources", "db", "schema-ai-monitor.sql")
if not os.path.exists(sql_path):
    fail("schema-ai-monitor.sql 不存在")
else:
    with open(sql_path, "r", encoding="utf-8") as f:
        sql = f.read()

    # --- SPEC 第 14 节要求的三张表名必须齐全 ---
    for table in ("ai_diagnosis_task", "ai_diagnosis_result", "ai_tool_execution"):
        if re.search(rf"CREATE TABLE IF NOT EXISTS\s+`?{table}`?", sql, re.I):
            ok(f"表 {table} 已定义")
        else:
            fail(f"缺少 SPEC 第 14 节要求的表: {table}")

    # --- 语句结尾分号数量粗校验（防止漏写分号导致两条语句粘连）---
    stmts = [s.strip() for s in sql.split(";") if s.strip() and not s.strip().startswith("--")]
    ok(f"可执行语句 {len(stmts)} 条")

    # --- 危险语句检查：监控表结构里不该出现任何破坏性 DDL/DML ---
    for kw in ("DROP ", "TRUNCATE ", "DELETE FROM"):
        if re.search(rf"^\s*{kw}", sql, re.I | re.M):
            fail(f"schema-ai-monitor.sql 含危险语句 {kw.strip()} —— "
                 f"该文件会在容器初始化时自动执行")

# =============================================================================
#  7. application-docker.yaml 与 compose 的一致性
# =============================================================================
print("\n=== 7. application-docker.yaml 与 compose 的一致性 ===")

app = load_yaml("src/main/resources/application-docker.yaml")
app_base = load_yaml("src/main/resources/application.yaml")

if app is not None:
    ok("application-docker.yaml: YAML 语法正确")

    ds_url = app.get("spring", {}).get("datasource", {}).get("url", "")

    # -------------------------------------------------------------------------
    # 端口期望值与 .env / compose 必须三者一致。
    #
    # 【这里为什么从 3307 改成 3306】3307 是「与原生 MySQL84 并存」时期的取值。
    # 决定「本机中间件全删、只用 Docker」之后端口回到标准值，
    # 于是 application.yaml 的连接串原样可用。检查项必须跟着改，
    # 否则它会对着正确的配置报错 —— 而会误报的检查一定会被忽略。
    #
    # 【更稳的做法】直接从 .env 里读期望值，而不是在脚本里写死。
    # 这样「改 .env 之后忘了改脚本」这个新问题不会出现。
    # -------------------------------------------------------------------------
    env_expect = {}
    env_path = os.path.join(ROOT, ".env")
    if os.path.exists(env_path):
        with open(env_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env_expect[k.strip()] = v.strip()

    expected_mysql_port = env_expect.get("MYSQL_HOST_PORT", "3306")
    if expected_mysql_port not in ds_url:
        fail(f"datasource.url 未指向 {expected_mysql_port}"
             f"（.env 的 MYSQL_HOST_PORT）-> {ds_url}")
    else:
        ok(f"datasource 指向 {expected_mysql_port}（与 .env 的 MYSQL_HOST_PORT 一致）")

    # --- Redis 端口同样对照 .env（且必须可从环境变量覆盖）---
    redis_port = str(app.get("spring", {}).get("data", {}).get("redis", {}).get("port", ""))
    if "REDIS_HOST_PORT" not in redis_port:
        warn(f"spring.data.redis.port={redis_port} 未从环境变量读取 —— "
             f"改 .env 的 REDIS_HOST_PORT 时这里不会跟随"
             f"（6379 与 skychat-redis 有冲突）")
    elif env_expect.get("REDIS_HOST_PORT", "6379") not in redis_port:
        fail(f"redis.port 的默认值 {redis_port} 与 .env 的 "
             f"REDIS_HOST_PORT={env_expect.get('REDIS_HOST_PORT')} 不一致")
    else:
        ok(f"redis.port 与 .env 一致且可被环境变量覆盖: {redis_port}")

    # --- management.server.address 必须放开，否则容器内的 Prometheus 抓不到 ---
    addr = app.get("management", {}).get("server", {}).get("address")
    if addr != "0.0.0.0":
        fail(f"management.server.address={addr} —— Prometheus 在容器内，"
             f"绑回环会抓不到指标（up == 0）")
    else:
        ok("management.server.address = 0.0.0.0（容器内 Prometheus 可达）")

    # -------------------------------------------------------------------------
    # 指标口径（直方图 / SLO 桶）现在写在 application.yaml，不再随 profile 走。
    #
    # 【为什么必须检查基础配置而不是 docker profile】它原先只写在 docker profile 里，
    # 于是用默认 profile 启动时 P99 / P95 / 慢请求占比三条记录规则全部为空，
    # 三条告警静默失效（已实测确认）。指标口径与「中间件跑在哪」无关，
    # 因此它必须对**所有** profile 生效 —— 检查目标也必须跟着搬到基础配置。
    #
    # =====================================================================
    # ★ 批次 1 更正：这一段检查自己也曾用错前缀，因此漏掉了一个真实缺陷。
    #
    # 它原先读的是 spring.metrics.distribution.*，与当时 application.yaml 里的写法
    # 一致 —— 于是「检查通过」只证明了「两处写法相同」，而不是「配置生效」。
    # 实际生效的前缀由 Boot 决定，不由我们两处一致决定：
    #
    #     $ javap -v .../spring-boot-micrometer-metrics-4.0.8.jar!MetricsProperties.class
    #       #82 = Utf8   management.metrics     ← @ConfigurationProperties 的 value
    #
    # 后果是三条告警（ApiP99LatencyHigh / ApiSlowRequestRateHigh / SlowQuerySurge）
    # 从第一天起就没响过，而本校验器一路报 [OK]。
    #
    # 因此现在改成检查**正确的前缀**，并显式把错误前缀报成 FAIL ——
    # 一个把错误写法当成期望值的校验器，比没有校验器更危险：
    # 它会把「已经写好但没生效」这件事盖章确认为正确。
    #
    # 【但必须承认校验器的能力边界】它只能校验**静态配置写对了**，
    # 校不出「运行时到底有没有生效」。真正的判据在运行时：
    #     curl -s :9091/actuator/prometheus | grep -c 'le="'      # 必须 > 0
    # 这一条已写进手册 §10 的「改配置之后必须做的事」。
    # =====================================================================
    mgmt_dist = (app_base or {}).get("management", {}).get("metrics", {}) \
        .get("distribution", {})
    wrong_dist = (app_base or {}).get("spring", {}).get("metrics", {}) \
        .get("distribution", {})

    if wrong_dist:
        fail("application.yaml 把指标口径写在了 spring.metrics.distribution 下 —— "
             "Boot 4 的正确前缀是 management.metrics，该写法会被静默忽略："
             "http_server_requests_seconds 会退化成 summary（无 le= 桶），"
             "P99/P95/慢请求三条记录规则全部为空序列、三条告警永不触发")

    hist = mgmt_dist.get("percentiles-histogram", {})
    if hist.get("http.server.requests") is not True:
        fail("application.yaml 未在 management.metrics.distribution 下打开 "
             "http.server.requests 的 percentiles-histogram —— "
             "http_server_requests_seconds_bucket 不存在，P99 相关规则会静默失效")
    else:
        ok("application.yaml: http.server.requests 直方图已开启（management.metrics，对所有 profile 生效）")

    if hist.get("lettuce") is not True:
        warn("application.yaml 未打开 lettuce 直方图 —— "
             "RedisOperationSlow 告警引用的 lettuce_seconds_bucket 不存在")
    else:
        ok("application.yaml: lettuce 直方图已开启（Redis 延迟告警可用）")

    # --- 记录规则里引用的 le="1.0" 桶必须存在 ---
    slo = mgmt_dist.get("slo", {}).get("http.server.requests", "")
    if "1s" not in str(slo):
        warn(f"slo 桶边界 {slo} 中未显式包含 1s —— "
             f"记录规则 seckill:http_slow_request_rate:5m 引用 le=\"1.0\"")
    else:
        ok(f"slo 桶包含 1s（le=\"1.0\" 可用）: {slo}")

    # --- MQ name-server 必须与 compose 发布的端口一致 ---
    ns = app.get("seckill", {}).get("mq", {}).get("name-server", "")
    if "9876" not in ns:
        fail(f"seckill.mq.name-server={ns} 与 compose 的 9876 映射不一致")
    else:
        ok(f"mq.name-server = {ns}")

    # --- LLM api-key 绝不能有非空默认值 ---
    key = app.get("seckill", {}).get("monitor", {}).get("llm", {}).get("api-key", "")
    if key and "DEEPSEEK_API_KEY" not in str(key):
        fail("llm.api-key 有硬编码默认值 —— 绝不能把密钥写进被提交的文件")
    else:
        ok(f"llm.api-key 由环境变量注入（默认空值）: {key!r}")

    # --- SPEC 第 23 节的关键约束必须有落点 ---
    agent = app.get("seckill", {}).get("monitor", {}).get("agent", {})
    if agent.get("max-tool-calls", 0) <= 0:
        fail("未设置 agent.max-tool-calls —— SPEC 第 23.2 节要求限制调用次数防无限循环")
    else:
        ok(f"agent.max-tool-calls = {agent['max-tool-calls']}")
    if agent.get("min-evidence-count", 0) < 2:
        fail("agent.min-evidence-count < 2 —— SPEC 第 10 节要求根因至少两个独立证据")
    else:
        ok(f"agent.min-evidence-count = {agent['min-evidence-count']}")

# =============================================================================
#  8. 指标名静态核查 —— 抓「引用了不存在的指标」这类静默失效
# =============================================================================
#
#  【为什么必须单独做这一步】PromQL 对「序列不存在」和「值为 0」的处理完全不同：
#  写错一个指标名不会报错，只是那条规则永远停在 inactive。
#  也就是说，一个拼错的指标名会让一条告警**静默失效**，而失效的告警比没有告警更危险
#  （你以为它在守着）。
#
#  这个缺陷在本项目里**真实发生过两次**：
#    1) alertmanager.yml 的抑制规则引用了不存在的 MqSendFailureHigh / MqLagHigh；
#    2) 看板与告警引用了不存在的 lettuce_command_completion_seconds_bucket
#       （Lettuce 的实际注册名是 lettuce_seconds）。
#  两次都不是靠人眼发现的，因此这里把它变成一条自动检查。
#
#  【检查方式与它的边界】用「已知前缀白名单」判断，未知名字报 WARN 而不是 FAIL。
#  理由：白名单不可能穷尽所有指标（换一个 Boot 版本就可能多出新的），
#  报 FAIL 会让校验器在合法升级时误报 —— 而一个会误报的检查会被习惯性忽略。
#  报 WARN 让它成为「值得看一眼的清单」，这才是可持续的。
# =============================================================================
print("\n=== 8. 指标名静态核查 ===")

# 本应用自己注册的指标（来自 SeckillMetrics，已逐一核对过源码）
APP_METRICS = {
    "seckill_request_queued_total", "seckill_request_degraded_total",
    "seckill_request_sync_success_total", "seckill_rollback_all_total",
    "seckill_rollback_stock_only_total",
    "seckill_outbox_enqueued_total", "seckill_outbox_sent_total",
    "seckill_outbox_retried_total", "seckill_outbox_abandoned_total",
    "seckill_outbox_purged_total",
    "seckill_outbox_pending", "seckill_outbox_failed",
    "seckill_mq_sent_total", "seckill_mq_send_failed_total",
    "seckill_consume_confirmed_total", "seckill_consume_duplicate_total",
    "seckill_consume_order_missing_total", "seckill_consume_cancelled_total",
    "seckill_consume_failed_total", "seckill_consume_stock_restored_total",
    "seckill_compensate_enqueued_total", "seckill_compensate_recovered_total",
    "seckill_compensate_abandoned_total", "seckill_compensate_pending",
    "seckill_reconcile_findings_total",
}

# 已知的「前缀」：来源是 Spring Boot Actuator 的内置仪表与 Prometheus 自身。
# 用前缀而不是完整名，是因为这些指标带大量标签（uri / status / command / ...），
# 完整名会随标签组合爆炸，无法穷举。
KNOWN_PREFIXES = (
    # 本应用自注册的 Counter / Gauge
    "seckill_",
    # 本项目的**记录规则产出**（命名约定用冒号标记「这条序列是算出来的」）。
    # 不把它们算进来会得到一批假警告：Recording Rule 的输出被当成「不存在的指标」。
    "seckill:",
    # HTTP 服务端（Boot 内置）
    "http_server_requests_",
    # Lettuce / Spring Data Redis（Boot 内置 Observation）
    "lettuce_", "lettuce",
    # HikariCP（Boot 内置）
    "hikaricp_",
    # JDBC / 连接池
    "jdbc_", "jvm_", "system_", "process_", "logback_", "tomcat_", "executor_",
    "spring_", "application_", "cache_",
    # Prometheus / Alertmanager 自身（自监控告警要用）
    "up", "ALERTS", "prometheus_", "alertmanager_", "scrape_",
    # RocketMQ Broker 内置的 OTel Prometheus 导出器（批次 1 打通）。
    # 【它是被第 9 组补进来的】第 8 组原先没有这个前缀，因为告警规则里
    # MqConsumeLagGrowing 用的是应用侧的近似口径（seckill:mq_lag_growth_rate:5m），
    # 从未直接引用 Broker 的指标名 —— 于是「清单不全」这件事一直没暴露。
    # 批次 2 的 Metrics/MQ Tool 要读精确堆积，第 9 组的一致性检查当场指出了它。
    "rocketmq_",
    # 直方图/汇总的派生后缀出现在 token 里时会被过滤掉，这里兜一层
    "http_server_", "hikaricp_",
)

# 需要从表达式里剔除的 PromQL 关键字与函数名（否则会被当成指标名报未知）
PROMQL_NOISE = {
    "sum", "avg", "min", "max", "count", "rate", "irate", "increase", "delta",
    "histogram_quantile", "by", "without", "on", "ignoring", "group_left",
    "group_right", "offset", "bool", "and", "or", "unless", "vector", "scalar",
    "clamp_max", "clamp_min", "abs", "ceil", "floor", "round", "topk", "bottomk",
    "quantile", "stddev", "stdvar", "changes", "resets", "deriv", "predict_linear",
    "absent", "absent_over_time", "time", "timestamp", "day_of_week", "hour",
    "label_replace", "label_join", "sort", "sort_desc", "count_values", "le",
    "instance", "job", "service", "uri", "status", "severity", "alertname",
    "alertstate", "command", "db_operation", "quantile", "error", "exception",
}

# 收集所有需要核查的表达式文本
expr_sources = []   # (来源描述, 表达式)

for filename in sorted(f for f in os.listdir(RULES_DIR) if f.endswith((".yml", ".yaml"))):
    data = load_yaml(f"monitoring/prometheus/rules/{filename}")
    if not data:
        continue
    for group in data.get("groups", []) or []:
        for rule in group.get("rules", []) or []:
            name = rule.get("alert") or rule.get("record")
            if rule.get("expr"):
                expr_sources.append((f"{filename} :: {name}", rule["expr"]))

# 看板里的表达式
if os.path.isdir(DASH_DIR):
    for filename in sorted(f for f in os.listdir(DASH_DIR) if f.endswith(".json")):
        dash = load_json(f"monitoring/grafana/dashboards/{filename}")
        if not dash:
            continue
        for panel in dash.get("panels", []) or []:
            for t in panel.get("targets", []) or []:
                if t.get("expr"):
                    expr_sources.append((f"{filename} :: {panel.get('title', '?')}", t["expr"]))

# 从表达式里抽指标名。
#
# 【为什么要允许冒号】本项目的记录规则沿用社区命名约定
# `level:metric:operations`（如 seckill:outbox_pending_now）。
# 如果正则只匹配 [a-zA-Z0-9_]，冒号会被当成 token 边界，
# 抽出 `seckill` 与 `outbox_pending_now` 两个残缺名字 ——
# 前者恰好命中 seckill_ 前缀检查被放过，后者则报一堆假警告。
# 一个会误报的检查会被习惯性忽略，因此这里的字符集必须包含冒号。
IDENT_RE = re.compile(r"\b([a-zA-Z_][a-zA-Z0-9_:]*)\s*(?:\{|\[|\s|$|[),])")

unknown = {}
for source, expr in expr_sources:
    # 去掉字符串字面量，避免把 'foo' 里的词当指标名
    cleaned = re.sub(r'"[^"]*"', '""', expr)
    for m in IDENT_RE.finditer(cleaned):
        token = m.group(1)
        if token in PROMQL_NOISE or token in APP_METRICS:
            continue
        if any(token.startswith(p) for p in KNOWN_PREFIXES):
            continue
        # 纯数字标签值、单位等
        if token.isdigit():
            continue
        unknown.setdefault(token, set()).add(source)

if unknown:
    for token in sorted(unknown):
        warn(f"指标名 '{token}' 不在已知清单里，请确认它真实存在 "
             f"（否则引用它的规则会静默失效）—— 来源: {sorted(unknown[token])[0]}")
else:
    ok(f"全部表达式的指标名都能对上清单（核查了 {len(expr_sources)} 条表达式）")


# =============================================================================
#  9. Tool 层（批次 2）：配置键、跨配置不变量、指标目录与白名单的一致性
# =============================================================================
#
#  【这一组防的是什么】Tool 层有三处「写错了也没有症状」的地方，都在批次 2 里
#  真实发生过或险些发生：
#
#   1) 配置键绑到了没人写过的前缀上 → 配置被静默忽略。
#      实例：MonitorLogProperties 原本绑 seckill.monitor.logs，而配置文件里写的是
#      seckill.monitor.tool.logs。两者默认值恰好相同，因此**没有任何症状**，
#      直到有人试图改 max-samples 却发现「改了没用」。
#      -> 检查 9.2：配置文件里必须真的存在 Java 侧绑定的那些键。
#
#   2) 两个超时的大小关系写反 → 外层先于内层触发，日志里失去「是谁慢」这条线索。
#      -> 检查 9.3：tool.prometheus.timeout-ms < agent.tool-timeout-ms。
#
#   3) 指标目录里的序列名拼错 → 该口径永远返回空，而空结果会被读成「没有异常」。
#      实例：lettuce_command_completion_seconds_bucket 从来不存在（真名是 lettuce_seconds）。
#      启动时的 probeCatalog 也能抓它，但那个自检需要 Prometheus 在跑；
#      这里用静态名单再兜一层，让「没起容器也能发现拼写错误」成立。
#      -> 检查 9.4：MetricCatalog 里声明的底层序列名必须在第 8 组的已知清单里。
# =============================================================================
print("\n=== 9. Tool 层（批次 2）配置与指标目录 ===")

monitor_tool = (app or {}).get("seckill", {}).get("monitor", {}).get("tool", {})
monitor_agent = (app or {}).get("seckill", {}).get("monitor", {}).get("agent", {})

# --- 9.1 日志产出侧的键绑在 tool.logs 下（MonitorLogProperties 的前缀）---
#    这一条与它的「反向」检查合起来，正是上面第 1 类失效的判据：
#    键必须在 YAML 里存在，且必须在 Java 绑定的那个前缀下。
TOOL_LOG_KEYS = {
    "seckill.monitor.tool.logs.max-samples": monitor_tool.get("logs", {}).get("max-samples"),
    "seckill.monitor.tool.logs.max-sample-chars": monitor_tool.get("logs", {}).get("max-sample-chars"),
    "seckill.monitor.tool.logs.dedupe": monitor_tool.get("logs", {}).get("dedupe"),
}
missing_log_keys = [k for k, v in TOOL_LOG_KEYS.items() if v is None]
if missing_log_keys:
    fail(f"application-docker.yaml 缺少日志产出侧配置键 {missing_log_keys} —— "
         f"MonitorLogProperties 绑定前缀是 seckill.monitor.tool.logs，"
         f"少了它们会静默回落到代码默认值（改了配置没反应）")
else:
    ok(f"seckill.monitor.tool.logs.* 三个键齐备：{TOOL_LOG_KEYS}")

# --- 9.2 其余 Tool 层配置键 ---
REQUIRED_TOOL_KEYS = {
    "seckill.monitor.tool.max-result-chars": monitor_tool.get("max-result-chars"),
    "seckill.monitor.tool.prometheus.base-url": monitor_tool.get("prometheus", {}).get("base-url"),
    "seckill.monitor.tool.prometheus.timeout-ms": monitor_tool.get("prometheus", {}).get("timeout-ms"),
    "seckill.monitor.tool.prometheus.query-step-seconds":
        monitor_tool.get("prometheus", {}).get("query-step-seconds"),
    "seckill.monitor.tool.prometheus.max-points": monitor_tool.get("prometheus", {}).get("max-points"),
    "seckill.monitor.tool.db.max-rows": monitor_tool.get("db", {}).get("max-rows"),
    "seckill.monitor.tool.db.slow-sql-top-n": monitor_tool.get("db", {}).get("slow-sql-top-n"),
    "seckill.monitor.tool.db.slow-sql-min-seconds": monitor_tool.get("db", {}).get("slow-sql-min-seconds"),
    "seckill.monitor.tool.db.query-timeout-seconds":
        monitor_tool.get("db", {}).get("query-timeout-seconds"),
}
missing_tool_keys = [k for k, v in REQUIRED_TOOL_KEYS.items() if v is None]
if missing_tool_keys:
    warn(f"以下 Tool 层配置键缺失，将使用代码里的默认值：{missing_tool_keys}")
else:
    ok(f"Tool 层配置键齐备（{len(REQUIRED_TOOL_KEYS)} 项）")

# --- 9.3 两个超时的大小关系（跨配置不变量）---
prom_timeout = monitor_tool.get("prometheus", {}).get("timeout-ms")
tool_timeout = monitor_agent.get("tool-timeout-ms")
if isinstance(prom_timeout, int) and isinstance(tool_timeout, int):
    if prom_timeout >= tool_timeout:
        fail(f"tool.prometheus.timeout-ms={prom_timeout} 不小于 "
             f"agent.tool-timeout-ms={tool_timeout} —— 超时会由外层先触发，"
             f"日志里就失去了「是 Prometheus 慢」这条线索")
    else:
        ok(f"Prometheus 查询超时（{prom_timeout}ms）小于 Tool 超时（{tool_timeout}ms）")
else:
    warn("读不到 tool.prometheus.timeout-ms 或 agent.tool-timeout-ms，跳过大小关系检查")

# --- 9.4 指标目录声明的序列名 vs 第 8 组的已知清单 ---
#    【解析方式的边界】只在 `new Entry(...)` 的**末尾**取字符串字面量
#    （即 metricNames 参数），因为描述文字是中文、不会被这个 ASCII 标识符模式匹配到。
#    再配合「条目数 == 取到名字的条目数」这条一致性检查，
#    既能抓住拼写错误，也能抓住「新加的条目忘了写序列名」。
CATALOG_PATH = os.path.join(
    ROOT, "src", "main", "java", "com", "dustikun", "seckill",
    "monitor", "tool", "metrics", "MetricCatalog.java")
if os.path.exists(CATALOG_PATH):
    with open(CATALOG_PATH, "r", encoding="utf-8") as f:
        catalog_src = f.read()

    entry_count = len(re.findall(r"new Entry\(", catalog_src))
    # 每个 Entry 末尾的字符串字面量（一个或多个），紧跟在 `))` 之前
    trailing = re.findall(
        r'((?:"[a-zA-Z_:][a-zA-Z0-9_:]*"\s*,\s*)*"[a-zA-Z_:][a-zA-Z0-9_:]*")\s*\)\s*\)',
        catalog_src)
    declared = []
    for group in trailing:
        declared.extend(re.findall(r'"([a-zA-Z_:][a-zA-Z0-9_:]*)"', group))

    if not declared:
        warn("未能从 MetricCatalog.java 解析出任何序列名（解析规则可能已失效，请检查本检查项）")
    else:
        unknown_metrics = []
        for name in declared:
            if name in APP_METRICS:
                continue
            if any(name.startswith(p) for p in KNOWN_PREFIXES):
                continue
            unknown_metrics.append(name)
        if unknown_metrics:
            for name in sorted(set(unknown_metrics)):
                fail(f"MetricCatalog 声明的序列名 '{name}' 不在已知指标清单里 —— "
                     f"该口径会永远返回空结果（空结果会被读成「没有异常」）")
        else:
            ok(f"MetricCatalog 声明的 {len(set(declared))} 个序列名都在已知清单里")

        if len(declared) < entry_count:
            warn(f"MetricCatalog 有 {entry_count} 条目录项，但只解析到 "
                 f"{len(declared)} 个序列名 —— 有新条目忘了声明底层序列名，"
                 f"它不会参与启动自检（自检只验证声明过的名字）")
else:
    warn("找不到 MetricCatalog.java，跳过指标目录核查")


# =============================================================================
#  10. Agent 层（批次 3）：告警元数据、类型词汇一致性、以及「AI 不得自激」
# =============================================================================
#
#  【这一组防的是什么】批次 3 让告警第一次真正驱动 LLM 调用 —— 于是三类问题
#  第一次有了「花钱」或「闭环」的后果，而它们都属于「写错了不报错」：
#
#   1) 告警规则缺 value / threshold 注解（或写成 "1900ms" 这种带单位的字符串）。
#      Alertmanager 的载荷里**没有数值**，只有 labels 与 annotations，
#      因此 SPEC 第 7.1 节的 currentValue / threshold 只能由注解提供；
#      而解析器刻意不做单位换算（换算错了会得到一个「看起来合理但差三个数量级」的数）。
#      -> 检查 10.3：每条**路由到 AI** 的告警规则都必须有纯数字的 value / threshold。
#
#   2) 「告警名 -> 异常类型」的映射在两边各写了一遍（YAML 的 alert_type 标签
#      与 AlertType.java 的索引表）。分叉的后果不是报错，而是**聚合键对不上**：
#      同一个事故被拆成两个诊断任务，各付一次 LLM 费用。
#      -> 检查 10.4：两处逐条对照，并且标签值必须是枚举里的常量。
#
#   3) metric_name 写了一个 MetricCatalog 里没有的名字。
#      Agent 看到「指标：xxx」之后会去 query_metric(xxx)，而工具会以
#      REJECTED 拒绝 —— 白白浪费一轮往返与一次模型调用。
#      -> 检查 10.5：有 metric_name 的必须在目录里。
#
#   4) ★ 某条告警规则或看板的 PromQL 引用了 seckill_ai_*（AI 自身的指标）。
#      那会形成自激闭环：Agent 一跑就写指标 -> 指标触发告警 -> 告警再触发诊断。
#      它不会自己停下来，而每一圈都要付费。批次 2 已经在 reconcile 上踩过一次。
#      -> 检查 10.6：静态禁止。纪律写在注释里会被忘掉，写成检查才会被遵守。
# =============================================================================
print("\n=== 10. Agent 层（批次 3）配置与不变量 ===")

AGENT_SRC = os.path.join(ROOT, "src", "main", "java", "com", "dustikun", "seckill",
                         "monitor", "core", "AlertType.java")
ALERTS_FILE = os.path.join(RULES_DIR, "seckill-alerts.yml")

# --- 10.1 读取告警规则（含 labels 与 annotations）---
alert_meta = {}   # alertname -> {"alert_type":..., "metric_name":..., "value":..., "threshold":...}
if os.path.exists(ALERTS_FILE):
    with open(ALERTS_FILE, "r", encoding="utf-8") as f:
        alerts_doc = yaml.safe_load(f) or {}
    for group in alerts_doc.get("groups", []) or []:
        for rule in group.get("rules", []) or []:
            name = rule.get("alert")
            if not name:
                continue
            labels = rule.get("labels", {}) or {}
            ann = rule.get("annotations", {}) or {}
            alert_meta[name] = {
                "alert_type": labels.get("alert_type"),
                "metric_name": ann.get("metric_name"),
                "value": ann.get("value"),
                "threshold": ann.get("threshold"),
            }
    ok(f"seckill-alerts.yml 读取到 {len(alert_meta)} 条告警规则")
else:
    fail("找不到 monitoring/prometheus/rules/seckill-alerts.yml")

# --- 10.2 从 alertmanager.yml 推导「哪些告警会进入 AI 诊断」---
#     【为什么必须推导而不是硬编码那份名单】因为名单的来源就是 alertmanager 的路由；
#     在这里抄一遍，等于给「路由改了、校验没改」留了一个静默缺口。
AM_FILE = os.path.join(ROOT, "monitoring", "alertmanager", "alertmanager.yml")
ai_routed = []
not_ai_routed = []
if os.path.exists(AM_FILE):
    am_doc = load_yaml("monitoring/alertmanager/alertmanager.yml")
    root_route = (am_doc or {}).get("route", {}) or {}
    default_receiver = root_route.get("receiver")
    if default_receiver != "ai-monitor-webhook":
        fail(f"alertmanager 的默认 receiver 是 {default_receiver}，"
             f"而本校验按「默认接收者 = ai-monitor-webhook」推断哪些告警进入 AI 诊断。"
             f"路由改了就必须同步这里，否则第 10 组的判断全是错的")
    else:
        ok("alertmanager 默认 receiver = ai-monitor-webhook（第 10 组据此判定「进入 AI 诊断」的告警）")

    def _matcher_hits(matcher_text, alertname):
        """判定一条 Alertmanager matcher（形如 'alertname =~ "A|B"'）是否命中某个告警名。

        Prometheus 的正则是**完全匹配**（自动加 ^...$），因此这里用 fullmatch ——
        用 search 会让 TargetDownBackup 命中 TargetDown 的规则，而那种偏差
        会让一条本该送给 AI 的告警被静默排除。
        """
        m = re.match(r'^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(=~|!~|=|!=)\s*"?([^"]*)"?\s*$', matcher_text)
        if not m:
            return None
        label, op, value = m.group(1), m.group(2), m.group(3)
        if label != "alertname":
            return None
        if op == "=":
            return alertname == value
        if op == "!=":
            return alertname != value
        try:
            hit = re.fullmatch(value, alertname) is not None
        except re.error:
            return None
        return hit if op == "=~" else not hit

    for name in sorted(alert_meta):
        diverged = False
        for sub in root_route.get("routes", []) or []:
            for matcher in sub.get("matchers", []) or []:
                if _matcher_hits(str(matcher), name) is True:
                    diverged = True
                    break
            if diverged:
                break
        (not_ai_routed if diverged else ai_routed).append(name)

    if ai_routed:
        ok(f"按 alertmanager 路由推导：{len(ai_routed)} 条告警进入 AI 诊断，"
           f"{len(not_ai_routed)} 条走别的 receiver（{not_ai_routed}）")
    else:
        fail("按 alertmanager 路由推导，没有任何告警会进入 AI 诊断 —— 路由 matcher 可能写错了")
else:
    fail("找不到 monitoring/alertmanager/alertmanager.yml，无法判定哪些告警进入 AI 诊断")

# --- 10.3 进入 AI 诊断的告警必须有纯数字的 value / threshold ---
missing_numeric = []
bad_numeric = []
for name in ai_routed:
    meta = alert_meta[name]
    if meta["value"] is None or meta["threshold"] is None:
        missing_numeric.append(name)
        continue
    # threshold 必须是纯数字（写 "1s" / "99%" 都会被解析器判为读不出来）
    try:
        float(str(meta["threshold"]).strip())
    except ValueError:
        bad_numeric.append(f"{name}.threshold={meta['threshold']!r}")
    # value 是模板：要么是纯数字，要么必须带 printf 的数字动词（%.Nf / %d / %g 等）
    value_text = str(meta["value"])
    if re.fullmatch(r'\s*-?\d+(\.\d+)?\s*', value_text):
        continue
    if "printf" in value_text and re.search(r'%[-+0-9.]*[dfgeE]', value_text):
        continue
    bad_numeric.append(f"{name}.value={value_text!r}")

if missing_numeric:
    fail(f"以下告警缺少 value/threshold 注解：{missing_numeric} —— "
         f"Alertmanager 的载荷里没有数值，缺了它们 Agent 只能看到「未提供」，"
         f"而 SPEC 第 7.1 节的 AlertEvent 要求 currentValue/threshold")
elif bad_numeric:
    fail(f"以下 value/threshold 不是纯数字模板：{bad_numeric} —— "
         f"解析器不做单位换算（\"1900ms\" 会被判为读不出来），"
         f"请用 '{{{{ printf \"%.4f\" $value }}}}' 这种形态")
else:
    ok(f"进入 AI 诊断的 {len(ai_routed)} 条告警都有纯数字的 value/threshold 注解")

# --- 10.4 告警类型词汇一致性：YAML 的 alert_type 标签 vs AlertType.java ---
if os.path.exists(AGENT_SRC):
    with open(AGENT_SRC, "r", encoding="utf-8") as f:
        agent_src = f.read()

    enum_constants = set(re.findall(r'^\s{4}([A-Z][A-Z0-9_]*)\s*[,;]\s*$', agent_src, re.M))
    index_map = dict(re.findall(r'map\.put\("([^"]+)",\s*([A-Z_0-9]+)\);', agent_src))

    if not enum_constants or not index_map:
        warn("未能从 AlertType.java 解析出枚举常量或索引表（解析规则可能已失效，请检查本检查项）")
    else:
        unknown_labels = []
        mismatched = []
        for name in ai_routed:
            label = alert_meta[name]["alert_type"]
            if not label:
                unknown_labels.append(f"{name}(缺失)")
                continue
            if label not in enum_constants:
                unknown_labels.append(f"{name}={label}")
                continue
            mapped = index_map.get(name)
            if mapped != label:
                mismatched.append(f"{name}: 标签={label} 索引表={mapped}")

        if unknown_labels:
            fail(f"以下 alert_type 标签不在 AlertType 枚举里：{unknown_labels} —— "
                 f"拼错的类型名会让它落进 UNKNOWN，而 UNKNOWN 仍然会触发诊断（按设计），"
                 f"于是「类型写错了」这件事没有任何症状")
        elif mismatched:
            fail(f"alert_type 标签与 AlertType 索引表不一致：{mismatched} —— "
                 f"两边分叉的后果是聚合键对不上：同一个事故被拆成两个诊断任务，各付一次 LLM 费用")
        else:
            ok(f"{len(ai_routed)} 条告警的 alert_type 标签与 AlertType.java 索引表逐条一致"
               f"（{len(enum_constants)} 个类型常量）")

        # 反向：索引表里登记的告警名必须在 YAML 里真实存在（防止规则改名后留下死映射）
        dead = [name for name in index_map if name not in alert_meta]
        if dead:
            warn(f"AlertType 索引表里有 {len(dead)} 个告警名在 seckill-alerts.yml 中不存在：{dead} —— "
                 f"规则改名后留下的死映射不会报错，只会让 fromAlertName 回落到 UNKNOWN")
        else:
            ok("AlertType 索引表里的告警名都真实存在（没有改名后遗留的死映射）")
else:
    warn("找不到 AlertType.java，跳过告警类型词汇一致性核查")

# --- 10.5 metric_name 必须在 MetricCatalog 里（否则 Agent 会白跑一次 query_metric）---
if os.path.exists(CATALOG_PATH):
    with open(CATALOG_PATH, "r", encoding="utf-8") as f:
        catalog_for_agent = f.read()
    catalog_keys = set(re.findall(r'new Entry\("([^"]+)"', catalog_for_agent))
    if not catalog_keys:
        warn("未能从 MetricCatalog.java 解析出口径名，跳过 metric_name 核查")
    else:
        bad_metrics = [f"{name}={alert_meta[name]['metric_name']}"
                       for name in ai_routed
                       if alert_meta[name]["metric_name"]
                       and alert_meta[name]["metric_name"] not in catalog_keys]
        with_metric = sum(1 for name in ai_routed if alert_meta[name]["metric_name"])
        if bad_metrics:
            fail(f"以下 metric_name 不在 MetricCatalog 里：{bad_metrics} —— "
                 f"Agent 会照着 prompt 里的「指标」去调 query_metric，而工具会以 REJECTED 拒绝，"
                 f"白白浪费一轮 LLM 往返。可用口径共 {len(catalog_keys)} 个")
        else:
            ok(f"告警里的 metric_name 全部是 MetricCatalog 的口径名"
               f"（{with_metric}/{len(ai_routed)} 条有 metric_name，"
               f"其余刻意省略：表达式没有单一可查口径时宁可不写）")

# --- 10.6 ★ 静态禁止：告警规则与看板不得引用 AI 自身指标（防自激闭环）---
AI_METRIC_PREFIX = "seckill_ai_"
self_reference_hits = []

for filename in rule_files:
    rel = f"monitoring/prometheus/rules/{filename}"
    doc = load_yaml(rel)
    if not doc:
        continue
    for group in doc.get("groups", []) or []:
        for rule in group.get("rules", []) or []:
            blob = json.dumps(rule, ensure_ascii=False)
            if AI_METRIC_PREFIX in blob:
                self_reference_hits.append(f"{filename}:{rule.get('alert') or rule.get('record')}")

for filename in dash_files if 'dash_files' in dir() else []:
    rel = f"monitoring/grafana/dashboards/{filename}"
    with open(os.path.join(ROOT, rel), "r", encoding="utf-8") as f:
        if AI_METRIC_PREFIX in f.read():
            self_reference_hits.append(f"dashboards/{filename}")

if self_reference_hits:
    fail(f"以下位置引用了 AI 自身指标（{AI_METRIC_PREFIX}*）：{self_reference_hits} —— "
         f"那会形成自激闭环：Agent 一跑就写指标 -> 指标触发告警 -> 告警再触发诊断。"
         f"它不会自己停下来，而每一圈都要付费（批次 2 已在 reconcile 上踩过一次）。"
         f"要观察 Agent 的健康状况请查 ai_diagnosis_task / ai_tool_execution 这两张事实表")
else:
    ok(f"告警规则与看板都没有引用 AI 自身指标（{AI_METRIC_PREFIX}*）—— 没有自激闭环")

# --- 10.7 批次 3 的配置键真的存在 ---
monitor = (app or {}).get("seckill", {}).get("monitor", {})
llm = monitor.get("llm", {}) or {}
incident = monitor.get("incident", {}) or {}
REQUIRED_AGENT_KEYS = {
    "seckill.monitor.enabled": monitor.get("enabled"),
    "seckill.monitor.llm.enabled": llm.get("enabled"),
    "seckill.monitor.llm.base-url": llm.get("base-url"),
    "seckill.monitor.llm.chat-path": llm.get("chat-path"),
    "seckill.monitor.llm.model": llm.get("model"),
    "seckill.monitor.llm.api-key": llm.get("api-key"),
    "seckill.monitor.llm.timeout-ms": llm.get("timeout-ms"),
    "seckill.monitor.llm.max-retries": llm.get("max-retries"),
    "seckill.monitor.llm.max-tokens": llm.get("max-tokens"),
    "seckill.monitor.llm.temperature": llm.get("temperature"),
    "seckill.monitor.incident.aggregate-window-seconds": incident.get("aggregate-window-seconds"),
    "seckill.monitor.incident.aggregate-by": incident.get("aggregate-by"),
}
missing_agent_keys = [k for k, v in REQUIRED_AGENT_KEYS.items() if v is None]
if missing_agent_keys:
    fail(f"application-docker.yaml 缺少 Agent 层配置键 {missing_agent_keys} —— "
         f"少了它们会静默回落到代码默认值（改配置没反应，本项目已记录过三次同类问题）")
else:
    ok(f"Agent 层配置键齐备（{len(REQUIRED_AGENT_KEYS)} 项：LLM 接入 + Incident 聚合）")

# aggregate-by 只支持一个值：跨类型合并属于 Agent 的推理，不该由聚合层下结论
aggregate_by = str(incident.get("aggregate-by", "")).replace(" ", "")
if aggregate_by and aggregate_by != "service,alertType":
    fail(f"seckill.monitor.incident.aggregate-by={incident.get('aggregate-by')} 不受支持 —— "
         f"本项目只实现「同服务 + 同异常类型」（SPEC 第 16 节）。"
         f"MonitorIncidentProperties#validate 会在启动时拒绝它，这里提前报出来")


# =============================================================================
#  汇总
# =============================================================================
print("\n" + "=" * 76)
print(f"检查项 {checks} 个：错误 {len(errors)} 个，警告 {len(warnings)} 个")
print("=" * 76)

if errors:
    print("\n错误清单：")
    for i, e in enumerate(errors, 1):
        print(f"  {i}. {e}")

if warnings:
    print("\n警告清单（不阻塞，但值得处理）：")
    for i, w in enumerate(warnings, 1):
        print(f"  {i}. {w}")

sys.exit(1 if errors else 0)
