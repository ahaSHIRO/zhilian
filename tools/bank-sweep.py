#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全库错误模式粗筛：按「已确认的错题模式」扫一遍存量题，产出待人工复核清单。

为什么需要它（2026-10-05 本轮题库复核的教训）：只按「主题」改被点名的那几题是不够的——
同一句错误论断会在多个批次里重复出现。本轮实测：审查交接文档点名了 2 处「官方唯一点名
Mutex.withLock」类错误，按模式扫全库后实际有 **3 处**（batch-0003 一处、batch-0016 两处，
其中第三处是扫出来的）；同一次扫描还把「回官方页逐条核」的范围从 67 处收敛到 10 处。

**本工具是粗筛，输出是「待人工复核清单」，不是结论**——命中不等于错（本轮就有一处误报：
「填空按完整类名精确比对，写 concurrent.X 或中文都算错」被当成「答案表收了该写法」）。
负结果同样要带检索有效性反证，方法见 question-authoring.md §二.4 与 pitfalls 3.14。

用法：
    python tools/bank-sweep.py [--batches-dir <批次目录>] [--strict]
    # --batches-dir 缺省读环境变量 ZHILIAN_BATCHES_DIR，未设置回落当前目录
    # --strict：模式 1 / 模式 4 命中时返回 1（可接进流水线；默认只报告）

退出码：0 = 报告完成；1 = --strict 下模式 1/4 有命中。
"""

import argparse
import collections
import glob
import json
import os
import re
import sys

# 模式 1：解析自称「某写法会被判错」，而该写法其实在 acceptableAnswers 里 → 左右互搏
JUDGE_WORDS = re.compile(r"判错|判成错|判为错|算错|记为错|会被判")
# 模式 2：全称／边界断言；STRONG 为强断言词，「来源归属类」= 强断言词 AND 归属词
STRONG = ["唯一", "从不", "永远", "必然", "绝不会", "绝不可能", "无一例外", "只有"]
ATTRIB = re.compile(r"官方|手册|原句|明写|明确|javadoc|类页|规范|JLS|JVMS|KDoc")
# 模式 4：选择题解析自称「选 X / 答案是 X」
CLAIM = re.compile(r"(?:选|答案是|正解是|正确项是)\s*([A-E](?:\s*[、,和]\s*[A-E])*)")
# 模式 2b：含「实测／检索／命中数」的自证性断言（可机器复跑，但每处要一条命令）
SELFPROOF = re.compile(r"实测|检索|命中\s*\d|全词命中|0 命中|均 0")


def sentences(t):
    return [s.strip() for s in re.split(r"[。；\n]+", t or "") if s.strip()]


def main() -> int:
    ap = argparse.ArgumentParser(description="按已确认的错题模式全库粗筛")
    ap.add_argument("--batches-dir", default=os.environ.get("ZHILIAN_BATCHES_DIR", "."))
    ap.add_argument("--strict", action="store_true", help="模式 1/4 有命中时返回 1")
    ap.add_argument("--top", type=int, default=3, help="每个关键词最多打印几条强断言（默认 3，0=全打）")
    a = ap.parse_args()

    qs = []
    for f in sorted(glob.glob(os.path.join(a.batches_dir, "batch-*.json"))):
        d = json.load(open(f, encoding="utf-8"))
        for q in d.get("questions", []):
            q["_file"] = os.path.basename(f).replace("batch-", "").replace(".json", "")
            qs.append(q)
    print(f"批次目录：{a.batches_dir}  扫描题目数：{len(qs)}")

    hard = 0

    print("\n" + "=" * 96)
    print("【模式 1】解析自称「会被判错」而答案表里却收了该写法（左右互搏）")
    print("=" * 96)
    n1 = 0
    for q in qs:
        answers = q.get("acceptableAnswers") or []
        if not answers:
            continue
        for s in sentences(q.get("explanation")):
            if not JUDGE_WORDS.search(s):
                continue
            for x in answers:
                if len(x) >= 2 and x in s:
                    n1 += 1
                    print(f"  [{q['_file']} {q['questionId'][:8]}] 答案表含 {x!r}，但解析说：{s[:110]}")
    print(f"  命中 {n1} 处（需人工判读：可能是「完整类名 vs 裸类名」这类不矛盾的表述）")
    hard += n1

    print("\n" + "=" * 96)
    print("【模式 2】全称／边界断言分布")
    print("=" * 96)
    cnt = collections.Counter()
    detail = collections.defaultdict(list)
    for q in qs:
        for s in sentences(q.get("explanation")):
            for k in STRONG:
                if k in s:
                    cnt[k] += 1
                    detail[k].append((q["_file"], q["questionId"][:8], s))
    for k in STRONG:
        print(f"  {k:<8} 命中 {cnt[k]}")
    attrib = [(f, i, s) for k in STRONG for f, i, s in detail[k] if ATTRIB.search(s)]
    print(f"\n  —— 高风险子集：同时含「来源归属词」（官方/明写/类页/javadoc…）：{len(attrib)} 处 ——")
    seen = set()
    for f, i, s in attrib:
        if (f, i, s) in seen:
            continue
        seen.add((f, i, s))
        print(f"  [{f} {i}] {s[:120]}")
    print("\n  —— 强断言逐条（待人工复核；不必然是错，但必须能逐条回官方页核实）——")
    for k in STRONG:
        show = detail[k] if a.top == 0 else detail[k][:a.top]
        for f, i, s in show:
            print(f"  [{k}] {f} {i}: {s[:110]}")
        if a.top and len(detail[k]) > a.top:
            print(f"  [{k}] … 其余 {len(detail[k]) - a.top} 条略（--top 0 全打）")

    print("\n" + "=" * 96)
    print("【模式 3】规范章节号引用（编号是否真实存在请交给 tools/check-citations.py）")
    print("=" * 96)
    bych = collections.defaultdict(set)
    for q in qs:
        blob = (q.get("explanation") or "") + " " + json.dumps(q.get("source") or {}, ensure_ascii=False)
        for m in re.finditer(r"§+\s*(\d+(?:\.\d+)*)", blob):
            bych[m.group(1).split(".")[0]].add(m.group(1))
    print(f"  共 {sum(len(v) for v in bych.values())} 个不同章节号，涉及 {len(bych)} 章：")
    for ch, nums in sorted(bych.items(), key=lambda x: int(x[0])):
        print(f"    第 {ch:>3} 章：{len(nums)} 个 -> {sorted(nums, key=lambda s: [int(x) for x in s.split('.')])}")

    print("\n" + "=" * 96)
    print("【模式 4】选择题解析自称「选 X / 答案是 X」与 answer 字段不一致")
    print("=" * 96)
    n4 = 0
    for q in qs:
        if q.get("type") not in ("single_choice", "multiple_choice"):
            continue
        ans = q.get("answer")
        ans_set = set(ans if isinstance(ans, list) else [ans])
        flat = set()
        for c in CLAIM.findall(q.get("explanation") or ""):
            flat |= set(re.findall(r"[A-E]", c))
        if flat and flat != ans_set:
            n4 += 1
            print(f"  [{q['_file']} {q['questionId'][:8]}] answer={sorted(ans_set)} 解析自称={sorted(flat)}")
    print(f"  命中 {n4} 处（含「A 错」这类否定语境，需人工判读）")
    hard += n4

    print("\n" + "=" * 96)
    print("【模式 5】来源缺 url（无法回官方页核实）")
    print("=" * 96)
    nourl = [q for q in qs if not (q.get("source") or {}).get("url")]
    for q in nourl:
        print(f"  [{q['_file']} {q['questionId'][:8]}] {q.get('type')} 标题={(q.get('source') or {}).get('title', '')[:52]}")
    print(f"  共 {len(nourl)} 题（JDK 源码型来源本机可复查，但建议在 source 里写明「版本 + src.zip 内路径」）")

    print("\n" + "=" * 96)
    print("【模式 2b】含「实测／检索／命中数」的自证性断言（可机器复跑）")
    print("=" * 96)
    sp = [(q["_file"], q["questionId"][:8], s) for q in qs for s in sentences(q.get("explanation")) if SELFPROOF.search(s)]
    print(f"  共 {len(sp)} 处；只在「结论一旦不成立答案就错」时值得逐条复跑")
    for f, i, s in sp[: a.top if a.top else len(sp)]:
        print(f"  [{f} {i}] {s[:110]}")

    print(f"\n汇总：模式 1 命中 {n1}、模式 4 命中 {n4}（这两类是硬缺陷候选，需人工判读）")
    if a.strict and hard:
        print("--strict：存在命中，返回 1")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
