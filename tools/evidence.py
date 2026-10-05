#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""证据检索三合一：题库原文定位 / 关键词计数 / 落盘页面上下文检索。

服务两条已被写进规范的纪律：
  1. question-authoring.md §二.4「负结果（证否型）的取证纪律」——报「官方没写」必须附
     **对照词命中数**（`count --control`）与检索范围；对照词也 0 命中 ⇒ 是抓取失败；
  2. pitfalls 3.14——跨行短语要先折叠空白，锚点/章节号检索要先用已知存在项自证。

三个子命令：
  dump   按 questionId 前缀打印整题 JSON（复核某题时看原文；未命中会显式报「负结果」）
  count  对文件批量统计多个正则的命中数 + 首行样本，可带对照词
  grep   去标签后带上下文检索（可选 --control 一并给对照词命中数）

用法：
    python tools/evidence.py dump 0d48de79 46abd2f9 [--batches-dir <目录>]
    python tools/evidence.py count <文件> 'weakly reachable' --control 'clazz'
    python tools/evidence.py grep  <文件> 'spurious wakeup' --window 400 --control 'Condition'

说明：`count`/`grep` 对 HTML 自动去 script/style/标签并解实体；`dump` 需批次目录
（缺省读环境变量 ZHILIAN_BATCHES_DIR）。

退出码：0 = 正常；1 = dump 有前缀未命中（很可能就是「查错前缀」这类假阴性）。
"""

import argparse
import glob
import html
import json
import os
import re
import sys


def strip_html(text: str) -> str:
    text = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = html.unescape(text)
    return re.sub(r"[ \t\x0b\f\r]+", " ", text)


def read_text(path: str) -> str:
    raw = open(path, encoding="utf-8", errors="replace").read()
    head = raw[:4000].lower()
    if "<html" in head or "<body" in head or "<!doctype" in raw[:200].lower():
        return strip_html(raw)
    return raw


def cmd_dump(a) -> int:
    bdir = a.batches_dir
    if not os.path.isdir(bdir):
        print(f"批次目录不存在：{bdir}")
        return 1
    index = []
    for f in sorted(glob.glob(os.path.join(bdir, "batch-*.json"))):
        d = json.load(open(f, encoding="utf-8"))
        for q in d.get("questions", []):
            index.append((f, d, q))
    print(f"扫描 {len({p for p, _, _ in index})} 个批次文件，共 {len(index)} 题\n")

    hit, missed = 0, []
    for pre in [p.lower() for p in a.prefixes]:
        ms = [(f, d, q) for f, d, q in index if q["questionId"].lower().startswith(pre)]
        if not ms:
            missed.append(pre)
            print(f"### 前缀 {pre} —— 未命中任何题！（这本身是待解释的负结果：先确认前缀抄对了没有，"
                  f"再考虑它是否已被替换/删除）\n")
            continue
        for f, d, q in ms:
            hit += 1
            print("=" * 100)
            print(f"批次文件: {os.path.basename(f)}   batchOrder={d.get('batchOrder')}  subject={d.get('subject')}")
            print("-" * 100)
            print(json.dumps(q, ensure_ascii=False, indent=2))
            print()
    print(f"命中 {hit} 题 / 请求前缀 {len(a.prefixes)} 个")
    return 1 if missed else 0


def cmd_count(a) -> int:
    text = read_text(a.file)
    lines = text.split("\n")
    flags = 0 if a.case_sensitive else re.I
    print(f"文件: {a.file}")
    print(f"总字符 {len(text)}   总行 {len(lines)}   大小写："
          f"{'敏感' if a.case_sensitive else '不敏感（加 -c 改用敏感；全大写标识符如 SIGNAL 别用默认值）'}")
    print("=" * 88)
    for p in a.patterns:
        hits = [(i, ln.strip()) for i, ln in enumerate(lines, 1) if re.search(p, ln, flags)]
        print(f"{p!r:<46} 命中 {len(hits)}")
        for i, ln in hits[: a.samples]:
            print(f"    L{i}: {ln[:150]}")
        print("-" * 88)
    if a.control:
        n = len(re.findall(a.control, text, flags))
        print(f"对照词 {a.control!r} 命中 {n}   （>0 才算本次检索协议有效）")
        if n == 0:
            print("  !!! 对照词也 0 命中 ⇒ 是抓取/检索失败，不是「官方没写」——结论作废")
            return 1
    return 0


def cmd_grep(a) -> int:
    text = read_text(a.file)
    flags = 0 if a.case_sensitive else re.I
    print(f"文件: {a.file}  去标签后字符数: {len(text)}   大小写："
          f"{'敏感' if a.case_sensitive else '不敏感（加 -c 改用敏感）'}")
    print(f"主正则: {a.pattern}")
    ms = list(re.finditer(a.pattern, text, flags))
    print(f"主正则命中数: {len(ms)}")
    for k, m in enumerate(ms, 1):
        s, e = max(0, m.start() - a.window), min(len(text), m.end() + a.window)
        print("-" * 90)
        print(f"[命中 {k}] ...{text[s:e]}...")
    if a.control:
        am = list(re.finditer(a.control, text, flags))
        print("=" * 90)
        print(f"对照词 {a.control!r} 命中 {len(am)}   （>0 才算本次检索协议有效）")
        for k, m in enumerate(am[:5], 1):
            s, e = max(0, m.start() - 120), min(len(text), m.end() + 120)
            print(f"  [对照 {k}] ...{text[s:e]}...")
        if not am:
            print("  !!! 对照词也 0 命中 ⇒ 结论作废")
            return 1
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="证据检索三合一（题库原文 / 计数 / 上下文）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("dump", help="按 questionId 前缀打印整题 JSON")
    d.add_argument("prefixes", nargs="+")
    d.add_argument("--batches-dir", default=os.environ.get("ZHILIAN_BATCHES_DIR", "."))
    d.set_defaults(func=cmd_dump)

    c = sub.add_parser("count", help="批量统计正则命中数 + 首行样本")
    c.add_argument("file")
    c.add_argument("patterns", nargs="+")
    c.add_argument("--samples", type=int, default=3)
    c.add_argument("-c", "--case-sensitive", action="store_true",
                   help="区分大小写（查 SIGNAL 这类全大写标识符时必须加）")
    c.add_argument("--control", default=None, help="对照词（必然命中）；0 命中则判本次检索无效")
    c.set_defaults(func=cmd_count)

    g = sub.add_parser("grep", help="去标签后带上下文检索")
    g.add_argument("file")
    g.add_argument("pattern")
    g.add_argument("--window", type=int, default=300)
    g.add_argument("-c", "--case-sensitive", action="store_true", help="区分大小写")
    g.add_argument("--control", default=None, help="对照词（必然命中）；0 命中则判本次检索无效")
    g.set_defaults(func=cmd_grep)

    a = ap.parse_args()
    if not os.path.exists(getattr(a, "file", "")) and a.cmd in ("count", "grep"):
        print(f"文件不存在：{a.file}")
        return 1
    return a.func(a)


if __name__ == "__main__":
    sys.exit(main())
