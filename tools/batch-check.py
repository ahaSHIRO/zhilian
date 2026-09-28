#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""知练批次文件预校验。

出题后、放入 Syncthing 批次目录前，在电脑端先自查一遍，避免"同步到手机才发现
整批被拒"从而走一整套回炉流程。App 对 Schema 不合格的批次是整批拒绝且只回报
前 5 条错误。

校验分两层：
  1. JSON Schema（复用 App 打包的同一份权威 Schema：docs/schema/batch-v1.schema.json）
  2. 应用级规则（Schema 表达不了、由 App 逐题执行的 8 项，见 batch-spec-v1.md）

用法：
    python tools/batch-check.py <批次文件路径> [--batches-dir <已导入批次目录>]

退出码：0 = 全部通过（可能有警告）；1 = 有错误。
"""

import argparse
import glob
import json
import os
import sys
import unicodedata

try:
    import jsonschema
except ImportError:
    print("缺少 jsonschema，请先安装：pip install jsonschema")
    sys.exit(1)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_PATH = os.path.join(REPO_ROOT, "docs", "schema", "batch-v1.schema.json")
DEFAULT_BATCHES_DIR = r"<Syncthing 批次目录>"

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def normalize_identity(s):
    """身份归一化：NFC + trim（与 App 的 normalizeIdentity 一致）"""
    return unicodedata.normalize("NFC", s.strip()) if isinstance(s, str) else s


def validate_schema(batch, schema):
    validator = jsonschema.Draft202012Validator(schema)
    problems = sorted(validator.iter_errors(batch), key=lambda e: list(e.absolute_path))
    for p in problems:
        loc = "/" + "/".join(str(x) for x in p.absolute_path) if p.absolute_path else "(根)"
        err(f"[Schema] {loc}: {p.message}")
    return len(problems) == 0


def validate_app_level(batch):
    """应用级校验（batch-spec-v1.md 的清单 1/2/3/4/5）"""
    questions = batch.get("questions") or []

    # 1. formatVersion 必须为 1（Schema 已用 const 卡，这里再明确提示）
    if batch.get("formatVersion") != 1:
        err(f"[应用级] formatVersion 必须为 1，实际 {batch.get('formatVersion')!r}")

    # 2. 批次内 questionId 互不重复
    ids = [q.get("questionId") for q in questions]
    dup_ids = {i for i in ids if ids.count(i) > 1}
    if dup_ids:
        err(f"[应用级] 批次内 questionId 重复：{sorted(dup_ids)}")

    # 3. retiredQuestionIds 不得包含本批次新题
    retired = batch.get("retiredQuestionIds") or []
    overlap = set(retired) & set(ids)
    if overlap:
        err(f"[应用级] retiredQuestionIds 包含本批次新题：{sorted(overlap)}")

    # 逐题
    for idx, q in enumerate(questions):
        qid = q.get("questionId", f"#{idx}")
        qtype = q.get("type")
        options = q.get("options") or []
        option_ids = [o.get("optionId") for o in options]

        # 4. options 内 optionId 互不重复
        dup_opts = {o for o in option_ids if option_ids.count(o) > 1}
        if dup_opts:
            err(f"[应用级] 题 {qid} 选项标识重复：{sorted(dup_opts)}")

        # 5. answer 引用的 optionId 必须存在于 options
        if qtype in ("single_choice", "multiple_choice"):
            if not options:
                err(f"[应用级] 题 {qid} 是选择题但缺少 options")
            answer = q.get("answer")
            referenced = []
            if isinstance(answer, str):
                referenced = [answer]
            elif isinstance(answer, list):
                referenced = [a for a in answer if isinstance(a, str)]
            elif answer is not None:
                err(f"[应用级] 题 {qid} 的 answer 类型与 {qtype} 不符：{type(answer).__name__}")
            for r in referenced:
                if r not in option_ids:
                    err(f"[应用级] 题 {qid} 的 answer 引用了不存在的选项 {r!r}（现有 {option_ids}）")
            if qtype == "multiple_choice" and isinstance(answer, list) and len(answer) < 2:
                err(f"[应用级] 题 {qid} 是多选但 answer 少于 2 项")

        if qtype == "fill_in_blank":
            acc = q.get("acceptableAnswers")
            if not acc:
                err(f"[应用级] 题 {qid} 是填空但缺少 acceptableAnswers")
            else:
                for a in acc:
                    if a != normalize_identity(a):
                        warn(f"[应用级] 题 {qid} 的可接受答案 {a!r} 首尾有空白或非 NFC，"
                             f"而用户答案会被 trim+NFC —— 可能永远匹配不上（建议写成 {normalize_identity(a)!r}）")

        if qtype == "true_false":
            if options:
                warn(f"[应用级] 题 {qid} 是判断题，App 固定渲染正确/错误两选项，"
                     f"批次不该给 options（当前给了 {len(options)} 个）")

        if qtype in ("single_choice", "multiple_choice", "true_false") and q.get("acceptableAnswers"):
            warn(f"[应用级] 题 {qid} 是 {qtype} 却带了 acceptableAnswers（仅填空用）")
        if qtype == "fill_in_blank" and q.get("options"):
            warn(f"[应用级] 题 {qid} 是填空却带了 options")

        # 6. 分类名 NFC+trim 后非空
        cat = q.get("category")
        if not normalize_identity(cat or ""):
            err(f"[应用级] 题 {qid} 的分类名为空")


def load_existing_batches(batches_dir, exclude_path):
    """读取批次目录里已有的批次，用于核对 batchOrder 与疑似重复"""
    existing = []
    for path in glob.glob(os.path.join(batches_dir, "*.json")):
        if os.path.abspath(path) == os.path.abspath(exclude_path):
            continue
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            existing.append((path, data))
        except Exception as e:
            warn(f"[目录] 无法解析已有批次 {os.path.basename(path)}：{e}")
    return existing


def validate_against_existing(batch, existing):
    """跨批次核对（spec 清单 6：batchOrder 不重复；8：疑似重复提示）。

    另覆盖清单 3 后半段的缺口：App 对与已导入题库重复的 questionId 是
    静默跳过而非报错（README 导入韧性），整批同步过去却少几道题很难察觉，
    故在电脑端提前拦下。
    """
    my_order = batch.get("batchOrder")
    questions = batch.get("questions") or []
    my_ids = {q.get("questionId") for q in questions if q.get("questionId")}
    my_stems = {}
    for q in questions:
        stem = normalize_identity(q.get("stem") or "")
        if stem:
            my_stems.setdefault(stem, q.get("questionId"))

    for path, other in existing:
        name = os.path.basename(path)
        if other.get("batchOrder") == my_order:
            err(f"[应用级] batchOrder {my_order} 与已有批次 {name} 重复"
                f"（batchId {other.get('batchId')}）")
        for q in other.get("questions") or []:
            qid = q.get("questionId")
            # 清单 3 后半段：ID 已在题库，App 导入时会跳过该题
            if qid in my_ids:
                err(f"[应用级] questionId {qid!r} 已存在于 {name}，"
                    f"App 导入时会静默跳过该题——请换用新 ID 或从本批次移除")
            # 疑似重复：题干 NFC+trim 完全相同且 questionId 不同
            stem = normalize_identity(q.get("stem") or "")
            if stem and stem in my_stems and qid != my_stems[stem]:
                warn(f"[应用级] 疑似重复：题 {my_stems[stem]} 与 {name} 的 {qid} "
                     f"题干完全相同（ID 不同）")


def main():
    ap = argparse.ArgumentParser(description="知练批次文件预校验")
    ap.add_argument("batch_file", help="待校验的批次 JSON 路径")
    ap.add_argument("--batches-dir", default=DEFAULT_BATCHES_DIR,
                    help=f"已导入批次所在目录（默认 {DEFAULT_BATCHES_DIR}）")
    args = ap.parse_args()

    path = args.batch_file
    if not os.path.isfile(path):
        print(f"找不到批次文件：{path}")
        sys.exit(1)

    with open(SCHEMA_PATH, encoding="utf-8") as f:
        schema = json.load(f)
    try:
        with open(path, encoding="utf-8") as f:
            batch = json.load(f)
    except json.JSONDecodeError as e:
        print(f"JSON 解析失败：{e}")
        sys.exit(1)

    print(f"校验文件：{path}")
    print(f"对照 Schema：{SCHEMA_PATH}")
    print("-" * 60)

    schema_ok = validate_schema(batch, schema)
    validate_app_level(batch)

    # 与已有批次核对（仅当目录存在）
    batches_dir = args.batches_dir
    if os.path.isdir(batches_dir):
        existing = load_existing_batches(batches_dir, path)
        if existing:
            print(f"发现已有批次 {len(existing)} 个（{batches_dir}），核对 batchOrder 与疑似重复")
            validate_against_existing(batch, existing)
        else:
            print(f"批次目录为空或无 JSON：{batches_dir}")
    else:
        warn(f"批次目录不存在，跳过与已有批次的核对：{batches_dir}")

    # 汇总
    if warnings:
        print(f"\n警告 {len(warnings)} 条：")
        for w in warnings:
            print(f"  ! {w}")
    if errors:
        print(f"\n错误 {len(errors)} 条：")
        for e in errors:
            print(f"  x {e}")
        print(f"\n结论：不通过（{len(errors)} 个错误）。"
              f"请修正后重新校验——整批拒绝或导入时静默跳题，都别等到手机端才发现。")
        sys.exit(1)

    n = len(batch.get("questions") or [])
    print(f"\n结论：通过。批次 {batch.get('batchId')} 顺序号 {batch.get('batchOrder')}，"
          f"{n} 题，Schema 校验{'通过' if schema_ok else '未通过'}"
          f"{'，有 ' + str(len(warnings)) + ' 条警告' if warnings else ''}。")
    sys.exit(0)


if __name__ == "__main__":
    main()
