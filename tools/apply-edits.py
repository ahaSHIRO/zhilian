#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批次内容刷新落笔器（fail-closed）。

用途：把「改哪几题的哪一句」写成机器可读清单，然后一条命令完成
备份 -> 改 -> 读回复验 -> 逐文件 batch-check，任何一步不满足预期就整体中止/回滚。

为什么要有它（而不是手工改 JSON 或让模型直接改文件）：
  - 落笔属不可逆的投放类动作（ADR-0017「内容刷新」；文件在 Syncthing 目录里，
    改错会同步到手机），手工改 13 个文件 26 处极易转录错且无从复验；
  - 「原文不命中」必须**中止**而不是「改了个差不多的地方」；
  - 身份键（questionId / batchId / batchOrder / retiredQuestionIds）一旦被动，
    语义就从「内容刷新」变成「投放新题」——本工具机械拒绝这类编辑。

清单格式（JSON 数组）：
    [
      {"questionId": "<uuid>", "kind": "field", "field": "stem|explanation|opt:A", "old": "...", "new": "..."},
      {"questionId": "<uuid>", "kind": "array_remove", "value": "<要删掉的可接受答案>"},
      {"questionId": "<uuid>", "kind": "array_append", "value": "<要新增的可接受答案>"}
    ]

用法：
    python tools/apply-edits.py --spec <清单.json> [--batches-dir <批次目录>]
    python tools/apply-edits.py --spec <清单.json> --measure      # 顺带打印改动前后篇幅（硬线见 §三）
    python tools/apply-edits.py --spec <清单.json> --apply        # 真正落笔（默认 dry-run）
    # --batches-dir 缺省读环境变量 ZHILIAN_BATCHES_DIR，未设置回落当前目录
    # 备份目录缺省 app/build/backup-<时间戳>/（已 gitignore）

退出码：0 = 成功（dry-run 视为成功）；1 = 预检失败 / 复验失败 / batch-check 失败（已回滚）。
"""

import argparse
import copy
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(TOOLS_DIR)
sys.path.insert(0, TOOLS_DIR)
from brevity import question_metrics, over_limit  # noqa: E402

# 身份键：动它们等于「投放新题」而非「内容刷新」，机械拒绝
FORBIDDEN = {"questionId", "batchId", "batchOrder", "retiredQuestionIds"}


def esc(s: str) -> str:
    """把字符串转成它在 JSON 文件里的转义形式（去掉外层引号）。"""
    return json.dumps(s, ensure_ascii=False)[1:-1]


def field_value(q: dict, field: str) -> str:
    if field.startswith("opt:"):
        oid = field.split(":")[1]
        for o in q.get("options") or []:
            if o["optionId"] == oid:
                return o["text"]
        raise KeyError(f"{q['questionId'][:8]} 无选项 {oid}")
    return q.get(field) or ""


def set_field(q: dict, field: str, v: str) -> None:
    if field.startswith("opt:"):
        oid = field.split(":")[1]
        for o in q["options"]:
            if o["optionId"] == oid:
                o["text"] = v
                return
        raise KeyError(field)
    q[field] = v


def array_span(raw: str, qid: str, key: str):
    """定位某题里 "<key>": [ ... ] 的原文区间，返回 (开括号下标, 闭括号下标)。"""
    qi = raw.find(qid)
    if qi < 0:
        raise ValueError(f"原文里找不到 {qid}")
    ki = raw.find(f'"{key}"', qi)
    if ki < 0:
        raise ValueError(f"{qid[:8]} 里找不到字段 {key}")
    lb = raw.find("[", ki)
    i, depth = lb, 0
    while i < len(raw):
        if raw[i] == "[":
            depth += 1
        elif raw[i] == "]":
            depth -= 1
            if depth == 0:
                return lb, i
        i += 1
    raise ValueError("数组括号不闭合")


def main() -> int:
    ap = argparse.ArgumentParser(description="批次内容刷新落笔器（fail-closed）")
    ap.add_argument("--spec", required=True, help="编辑清单 JSON")
    ap.add_argument("--batches-dir", default=os.environ.get("ZHILIAN_BATCHES_DIR", "."),
                    help="批次目录，缺省读 ZHILIAN_BATCHES_DIR")
    ap.add_argument("--backup-dir", default=None, help="备份目录，缺省 app/build/backup-<时间戳>/")
    ap.add_argument("--measure", action="store_true", help="打印改动前后篇幅指标")
    ap.add_argument("--apply", action="store_true", help="真正写盘（默认只做预检）")
    a = ap.parse_args()

    if not os.path.isdir(a.batches_dir):
        print(f"批次目录不存在：{a.batches_dir}")
        return 1

    spec = json.load(open(a.spec, encoding="utf-8"))
    files = []
    for f in sorted(glob.glob(os.path.join(a.batches_dir, "batch-*.json"))):
        with open(f, "r", encoding="utf-8", newline="") as fh:
            files.append((f, fh.read()))
    raw_by_path = dict(files)
    doc_before = {p: json.loads(r) for p, r in files}

    print(f"批次目录：{a.batches_dir}（{len(files)} 个文件）")
    print(f"编辑清单：{a.spec}（{len(spec)} 条）  模式：{'APPLY' if a.apply else 'DRY-RUN'}")
    print("\n=== 预检（不写盘） ===")

    plans = {}                             # path -> [(qid, 描述, old_token, new_token)]
    field_merges = {}                      # (path, qid, field) -> 原始整值：同一字段的多次编辑合并成一次替换
    expected = copy.deepcopy(doc_before)   # 程序化构造的预期文档，用于落笔后全等比对

    def locate(qid):
        hits = []
        for p, _r in files:
            for d in [doc_before[p]]:
                if any(q["questionId"] == qid for q in d.get("questions", [])):
                    hits.append((p, raw_by_path[p], d))
        return hits

    for e in spec:
        qid, kind = e["questionId"], e["kind"]
        if e.get("field") in FORBIDDEN:
            print(f"拒绝：清单试图改动身份键 {e['field']}")
            return 1
        hits = locate(qid)
        if len(hits) != 1:
            print(f"预检失败：{qid} 命中 {len(hits)} 个文件（应为 1）")
            return 1
        path, raw, doc = hits[0]
        q = next(q for q in doc["questions"] if q["questionId"] == qid)
        q_exp = next(q for q in expected[path]["questions"] if q["questionId"] == qid)

        if kind == "field":
            field = e["field"]
            # 用「预期文档的当前值」而非原文：这样同一字段的多处编辑能顺序累积
            cur = field_value(q_exp, field)
            n = cur.count(e["old"])
            if n != 1:
                print(f"预检失败：{qid[:8]} 字段 {field} 内 old 命中 {n} 次（应为 1）")
                return 1
            set_field(q_exp, field, cur.replace(e["old"], e["new"], 1))
            otok = json.dumps(field_value(q, field), ensure_ascii=False)   # 原值：落盘时用它定位
            if raw.count(otok) != 1:
                print(f"预检失败：{qid[:8]} 字段 {field} 的整值在文件里命中 {raw.count(otok)} 次（应为 1）")
                return 1
            field_merges[(path, qid, field)] = otok

        elif kind in ("array_remove", "array_append"):
            lb, rb = array_span(raw, qid, "acceptableAnswers")
            inner = raw[lb + 1:rb]
            v = esc(e["value"])
            if kind == "array_remove":
                if inner.count(f'"{v}"') != 1:
                    print(f"预检失败：{qid[:8]} 数组里 {e['value']!r} 命中不为 1")
                    return 1
                q_exp["acceptableAnswers"] = [x for x in q_exp["acceptableAnswers"] if x != e["value"]]
                plans.setdefault(path, []).append((qid, f"删除答案 {e['value']!r}", None, None))
            else:
                if f'"{v}"' in inner:
                    print(f"预检失败：{qid[:8]} 数组已含 {e['value']!r}")
                    return 1
                elems = re.findall(r'"[^"]*"', inner)
                if not elems:
                    print(f"预检失败：{qid[:8]} 数组为空，无插入位置")
                    return 1
                indent = re.search(r'[ \t]*(?=")', inner[inner.rfind(elems[-1]):])
                ind = indent.group(0) if indent else "    "
                q_exp["acceptableAnswers"] = q_exp["acceptableAnswers"] + [e["value"]]
                plans.setdefault(path, []).append((qid, f"新增答案 {e['value']!r}", ("__append__", ind), v))
        else:
            print(f"未知 kind：{kind}")
            return 1
        print(f"  OK  {qid[:8]}  {kind:<13} {os.path.basename(path)}")

    # 把同一字段的多处编辑合并成「原值 -> 最终值」一次替换
    for (path, qid, field), otok in field_merges.items():
        final_val = field_value(next(q for q in expected[path]["questions"] if q["questionId"] == qid), field)
        plans.setdefault(path, []).append((qid, f"{field} 替换", otok, json.dumps(final_val, ensure_ascii=False)))

    print(f"\n预检通过：{len(spec)} 条编辑，涉及 {len(plans)} 个文件")
    for p in sorted(plans):
        print(f"  {os.path.basename(p)}: {len(plans[p])} 条")

    if a.measure:
        print("\n=== 改动前后篇幅（口径 tools/brevity.py；硬线见 question-authoring.md §三） ===")
        print("上限：题干文字 100 / 题干总长 180 / 解析 400 / 单选项 60")
        bad = 0
        for p in sorted(plans):
            for qid, desc, otok, ntok in plans[p]:
                b = next(q for q in doc_before[p]["questions"] if q["questionId"] == qid)
                g = next(q for q in expected[p]["questions"] if q["questionId"] == qid)
                m1, m2 = question_metrics(b), question_metrics(g)
                over = over_limit(m2)
                bad += 1 if over else 0
                print(f"  {qid[:8]} 题干 {m1['stem_text']}->{m2['stem_text']} | 总长 {m1['stem_total']}->{m2['stem_total']}"
                      f" | 解析 {m1['explanation']}->{m2['explanation']} | 最长选项 {m1['max_option']}->{m2['max_option']}"
                      f" | {'达标' if not over else '超标:' + ','.join(over)}")
        print(f"  超标条目：{bad}")
        if bad:
            print("  存在超标 -> 中止（提示：改完不许撑破篇幅硬线）")
            return 1

    if not a.apply:
        print("\nDRY-RUN 结束：未写盘。加 --apply 才会真正落笔。")
        return 0

    bdir = a.backup_dir or os.path.join(REPO_ROOT, "app", "build", f"backup-{time.strftime('%Y%m%d-%H%M%S')}")
    os.makedirs(bdir, exist_ok=True)
    for p in plans:
        shutil.copy2(p, os.path.join(bdir, os.path.basename(p)))
    print(f"\n=== 已备份 {len(plans)} 个文件 -> {bdir} ===")

    written = []
    try:
        for path in sorted(plans):
            new_raw = raw_by_path[path]
            for qid, desc, otok, ntok in plans[path]:
                if otok is None:            # array_remove
                    rm = [e for e in spec if e["questionId"] == qid and e["kind"] == "array_remove"][0]
                    v = esc(rm["value"])
                    lb, rb = array_span(new_raw, qid, "acceptableAnswers")
                    seg = re.sub(r',?\s*"' + re.escape(v) + r'"', '', new_raw[lb + 1:rb], count=1)
                    new_raw = new_raw[:lb + 1] + seg + new_raw[rb:]
                elif isinstance(otok, tuple):   # array_append：一律追加到末尾，保证与预期顺序一致
                    _, indent = otok
                    lb, rb = array_span(new_raw, qid, "acceptableAnswers")
                    inner = new_raw[lb + 1:rb]
                    m = re.search(r'(\s*)$', inner)
                    trail, body = m.group(1), inner[:m.start()]
                    if not body.strip():
                        raise RuntimeError("数组为空，无插入位置")
                    new_raw = new_raw[:lb + 1] + body + ",\n" + indent + f'"{ntok}"' + trail + new_raw[rb:]
                else:                           # field：整值替换
                    if new_raw.count(otok) != 1:
                        raise RuntimeError(f"写盘前复检失败：{qid[:8]} 目标字段值命中不为 1")
                    new_raw = new_raw.replace(otok, ntok, 1)

            got = json.loads(new_raw)
            if got != expected[path]:
                raise RuntimeError(f"复验失败：{os.path.basename(path)} 解析结果与预期文档不全等")

            for kb, ka in zip(doc_before[path].get("questions", []), got.get("questions", [])):
                for k in FORBIDDEN:
                    if kb.get(k) != ka.get(k):
                        raise RuntimeError(f"身份键 {k} 被改动")
            if doc_before[path].get("batchId") != got.get("batchId"):
                raise RuntimeError("batchId 被改动")

            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8", newline="") as fh:
                fh.write(new_raw)
            os.replace(tmp, path)
            written.append(path)
            print(f"  已写 {os.path.basename(path)}（{len(plans[path])} 条）")

            r = subprocess.run([sys.executable, os.path.join(TOOLS_DIR, "batch-check.py"), path,
                                "--batches-dir", a.batches_dir],
                               capture_output=True, text=True, encoding="utf-8")
            print(f"    batch-check {'通过' if r.returncode == 0 else '失败'}（exit={r.returncode}）")
            if r.returncode != 0:
                raise RuntimeError(((r.stdout or "") + (r.stderr or ""))[-700:])
    except Exception as ex:
        print(f"\n!!! 失败，回滚：{ex}")
        for p in written:
            shutil.copy2(os.path.join(bdir, os.path.basename(p)), p)
            print(f"  已回滚 {os.path.basename(p)}")
        return 1

    print(f"\n全部完成：{len(spec)} 条编辑写入 {len(written)} 个文件，备份在 {bdir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
