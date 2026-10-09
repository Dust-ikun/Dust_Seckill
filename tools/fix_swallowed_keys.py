#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
修复 `key = value` 被吞进中文注释行的问题。

【问题现象】
生成文件时，字符串里的 `\n` 被当成真实换行写入了文件，于是原本想写成
「一行注释 + 单独一行配置项」的内容变成了：

    # ...说明文字...brokerIP1 = 127.0.0.1

后果是**这个配置项从未生效**，而文件看起来是"有这一行"的。

【为什么这个缺陷特别危险】
以 docker/rocketmq/broker.conf 为例，被吞掉的包括：
    brokerIP1 / listenPort / haListenPort / maxMessageSize /
    diskMaxUsedSpaceRatio / deleteWhen / maxReconsumeTimes ...
其中 brokerIP1 失效会让 Broker 把**容器内网地址**注册到 NameServer，
表现为「NameServer 一切正常、Broker 日志显示注册成功、应用一投消息就超时」。
排查时几乎不会有人先去怀疑「配置文件里那一行其实是注释」。

【修复策略】
只做加法，不做删除或改写：
  1. 若被吞的键**已存在**于文件其它位置 → 不动（避免制造重复键 / 覆盖既有值）；
  2. 否则在**该注释段落结束处**追加一行 `key = value`，注释文字原样保留。
这样最坏情况是「什么都没做」，绝不会把配置改坏。

用法：
    <python> tools/fix_swallowed_keys.py --check     # 只报告
    <python> tools/fix_swallowed_keys.py --apply     # 执行修复
"""

import argparse
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 需要检查的文件（按语法分两类，正则略有不同）
CONF_FILES = [
    "docker/rocketmq/broker.conf",
    "docker/rocketmq/namesrv.conf",
]
ENV_FILES = [
    ".env",
    ".env.example",
]

# 注释行里被吞掉的 `key = value`：
#   - key：字母开头的标识符，允许点号（如 spring.datasource.url）
#   - 分隔：= 或 :=，两侧允许空格
#   - value：到行尾，或遇到另一个 `# ` 起点为止
SWALLOWED_CONF = re.compile(
    r'(?<=[^\s#])\s*([A-Za-z_][A-Za-z0-9_.]*)\s*=\s*([^#\n]+?)\s*$'
)
SWALLOWED_ENV = re.compile(
    r'(?<=[^\s#])\s*([A-Z][A-Z0-9_]{2,})\s*=\s*([^#\n]*?)\s*$'
)


def parse_keys(text, pattern):
    """返回文件中**独立成行**的所有 key（即真正会生效的那些）。"""
    keys = set()
    for line in text.split("\n"):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        m = re.match(r'^\s*([A-Za-z_][A-Za-z0-9_.]*)\s*=', s)
        if m:
            keys.add(m.group(1))
    return keys


def repair(path, pattern, apply_fix):
    full = os.path.join(REPO, path)
    if not os.path.exists(full):
        print(f"  [跳过] {path} 不存在")
        return 0

    with open(full, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")

    existing = parse_keys("\n".join(lines), pattern)
    findings = []   # (行号, 键, 值)

    for i, line in enumerate(lines):
        s = line.strip()
        if not s.startswith("#"):
            continue
        m = pattern.search(line)
        if not m:
            continue
        key, value = m.group(1), m.group(2).strip()
        if not value:
            continue
        if key in existing:
            print(f"  [已有] L{i+1} 键 '{key}' 在文件中已独立存在，跳过")
            continue
        findings.append((i, key, value))

    if not findings:
        print(f"  [干净] {path} 没有被吞掉的键")
        return 0

    print(f"  [发现] {path} 有 {len(findings)} 个键被吞进注释：")
    for i, key, value in findings:
        print(f"         L{i+1}: {key} = {value}")
        print(f"               ← 原文: {lines[i].strip()[:78]}...")

    if not apply_fix:
        return len(findings)

    # 从后往前插入，避免行号偏移
    for i, key, value in sorted(findings, key=lambda x: -x[0]):
        # 找到该注释段落的最后一行（连续的注释/空行之后就是段落结束）
        j = i
        while j + 1 < len(lines) and (lines[j + 1].strip().startswith("#")
                                      or lines[j + 1].strip() == ""):
            # 但不要跨过下一个独立配置项
            if lines[j + 1].strip() != "" and not lines[j + 1].strip().startswith("#"):
                break
            j += 1
        insert_at = j + 1
        lines.insert(insert_at, f"{key} = {value}")

    with open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))

    print(f"  [已修] 追加了 {len(findings)} 行独立配置")
    return len(findings)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="执行修复（默认只报告）")
    args = ap.parse_args()

    mode = "APPLY" if args.apply else "CHECK"
    print(f"=== 修复被吞进注释的配置项 [{mode}] ===\n")

    total = 0
    for path in CONF_FILES:
        print(f"[conf] {path}")
        total += repair(path, SWALLOWED_CONF, args.apply)
        print()
    for path in ENV_FILES:
        print(f"[env ] {path}")
        total += repair(path, SWALLOWED_ENV, args.apply)
        print()

    print("=" * 74)
    if args.apply:
        print(f"共修复 {total} 处。请重新运行校验器确认。")
    else:
        print(f"共发现 {total} 处需要修复。加 --apply 执行。")
    print("=" * 74)
    return 1 if total and not args.apply else 0


if __name__ == "__main__":
    sys.exit(main())
