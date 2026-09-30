#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""知练题库存量报告（bank-stats）。

只读盘面工具：汇总 Syncthing 批次目录下全部批次的科目/分类/题型/标签/来源分布，
暴露结构性问题（偏科、标签长尾、词表外标签、程序题占比），供选题与词表治理参考。
校验类检查（Schema、应用级规则、跨批次重复）不在这里做——那是 batch-check.py 的事。

用法：
    python tools/bank-stats.py [--batches-dir <批次目录>]

退出码：0 = 正常出报告（词表外标签只在报告中标注，不改变退出码；
词表拦截是 batch-check.py 的职责）。
"""

import argparse
import collections
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vocab import FROZEN_TAGS, load_vocabulary  # noqa: E402

DEFAULT_BATCHES_DIR = r"<Syncthing 批次目录>"

TYPE_LABELS = {
    "single_choice": "单选",
    "multiple_choice": "多选",
    "true_false": "判断",
    "fill_in_blank": "填空",
}
TYPE_ORDER = list(TYPE_LABELS)


def load_batches(batches_dir):
    batches = []
    for path in sorted(glob.glob(os.path.join(batches_dir, "*.json"))):
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            print(f"!! 无法解析 {os.path.basename(path)}：{e}")
            continue
        batches.append((os.path.basename(path), data))
    return batches


def section(title):
    print()
    print(f"—— {title} " + "-" * max(0, 50 - len(title) * 2))


def main():
    ap = argparse.ArgumentParser(description="知练题库存量报告")
    ap.add_argument("--batches-dir", default=DEFAULT_BATCHES_DIR,
                    help=f"批次目录（默认 {DEFAULT_BATCHES_DIR}）")
    args = ap.parse_args()

    batches = load_batches(args.batches_dir)
    if not batches:
        print(f"批次目录无可用 JSON：{args.batches_dir}")
        sys.exit(1)

    questions_by_subject = collections.defaultdict(list)  # subject -> [(文件名, q)]
    type_counter = collections.Counter()
    tag_counter = collections.defaultdict(collections.Counter)  # subject -> tag -> 次数
    src_counter = collections.Counter()

    print(f"批次目录：{args.batches_dir}（{len(batches)} 个）")
    section("批次一览")
    for name, data in batches:
        qs = data.get("questions") or []
        subject = data.get("subject", "<缺subject>")
        code = sum(1 for q in qs if "```" in (q.get("stem") or ""))
        retired = len(data.get("retiredQuestionIds") or [])
        pct = f"{code * 100 // len(qs)}%" if qs else "-"
        print(f"  {name:<44} {subject:<10} {len(qs):>3} 题 | 程序题 {code:>2} ({pct})"
              f"{' | 停用 ' + str(retired) if retired else ''}")
        for q in qs:
            questions_by_subject[subject].append((name, q))
            type_counter[q.get("type", "<缺>")] += 1
            for t in q.get("tags") or []:
                tag_counter[subject][t] += 1
            src = (q.get("source") or {}).get("url") or ""
            host = src.split("/")[2] if src.count("/") >= 2 else "<无url/笔记来源>"
            src_counter[host] += 1

    total = sum(len(v) for v in questions_by_subject.values())
    section("总览")
    print(f"  题数 {total}；科目 " +
          " ".join(f"{s} {len(qs)}" for s, qs in sorted(questions_by_subject.items())))
    print("  题型 " + " ".join(
        f"{TYPE_LABELS.get(t, t)} {type_counter.get(t, 0)}" for t in TYPE_ORDER))

    section("分类覆盖矩阵（科目 · 分类 → 题数 [题型细分]）")
    cat_matrix = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
    for subject, qs in questions_by_subject.items():
        for _, q in qs:
            cat_matrix[subject][q.get("category", "<缺>")][q.get("type", "<缺>")] += 1
    for subject in sorted(cat_matrix):
        print(f"  [{subject}]")
        cats = cat_matrix[subject]
        for cat in sorted(cats, key=lambda c: (-sum(cats[c].values()), c)):
            detail = " ".join(f"{TYPE_LABELS.get(t, t)}{n}" for t, n in cats[cat].most_common())
            print(f"    {cat:<12} {sum(cats[cat].values()):>3} 题  [{detail}]")

    vocabulary = load_vocabulary()
    section("标签报告（按科目，使用次数排序）")
    for subject in sorted(tag_counter):
        tags = tag_counter[subject]
        legal = vocabulary.get(subject)
        once = sorted(t for t, n in tags.items() if n == 1)
        illegal = sorted(t for t in tags if legal is not None and t not in legal)
        frozen_used = sorted(t for t in tags if t in FROZEN_TAGS)
        print(f"  [{subject}] 标签 {len(tags)} 个"
              + (f"（词表 {len(legal)} 个）" if legal is not None else "（词表未建档）"))
        if illegal:
            print(f"    ! 词表外标签 {len(illegal)} 个：{'、'.join(illegal)}")
        if frozen_used:
            print(f"    ! 冻结标签仍被存量使用：{'、'.join(frozen_used)}（存量保留，新题禁用）")
        print(f"    仅用 1 次 {len(once)} 个：{'、'.join(once)}")
        top = "、".join(f"{t}{n}" for t, n in tags.most_common(10))
        print(f"    TOP10：{top}")

    section("来源域名分布")
    for host, n in src_counter.most_common():
        print(f"  {host:<28} {n}")


if __name__ == "__main__":
    main()
