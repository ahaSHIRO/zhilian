#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""题目篇幅硬线的唯一定义（供 batch-check.py 与 bank-stats.py 共用）。

数值与口径见 docs/conventions/question-authoring.md §三「篇幅约束 + 内容类型配比」，
由来见 docs/adr/0016-question-brevity-hard-limits-and-full-bank-reflow.md。
改硬线只改这里，避免两个工具各持一份而漂移。

口径（权威）：程序题 = 题干含围栏代码块（连续三个反引号）；「去代码纯文字」=
剥除围栏代码块后 strip 的字符数。
"""

LIMIT_STEM_TEXT = 100      # 题干去代码后纯文字上限（字符）
LIMIT_STEM_TOTAL = 180     # 题干含代码总长上限（字符）
LIMIT_CODE_LINES = 6       # 单个代码块行数上限
LIMIT_CODE_BLOCKS = 1      # 单题代码块个数上限
LIMIT_EXPLANATION = 400    # 解析字符上限
LIMIT_OPTION_TEXT = 60     # 单个选项文本字符上限（选择题；判断/填空无选项）
LIMIT_PROGRAM_PCT = 10     # 程序题（题干含围栏代码块）占比上限（%）


def split_code_blocks(stem):
    """拆出题干里的围栏代码块：返回 (去代码后的纯文字, [各代码块行数])。

    围栏以连续三个反引号开头（可带语言标注），至下一个三反引号或文末结束；
    未闭合的按到文末计（出题要求代码块必须闭合，未闭合会被这里当成超长块暴露）。
    """
    text_lines = []
    blocks = []
    in_code = False
    cur = 0
    for line in (stem or "").split("\n"):
        if line.lstrip().startswith("```"):
            if in_code:
                blocks.append(cur)
                cur = 0
                in_code = False
            else:
                in_code = True
            continue
        if in_code:
            cur += 1
        else:
            text_lines.append(line)
    if in_code:
        blocks.append(cur)
    return "\n".join(text_lines).strip(), blocks


def question_metrics(q):
    """一道题的篇幅指标：纯文字长度 / 题干总长 / 代码块数 / 最大块行数 / 解析长度 / 最长选项 / 是否程序题。"""
    text, blocks = split_code_blocks(q.get("stem") or "")
    opt_lens = [len(o.get("text") or "") for o in (q.get("options") or [])]
    return {
        "stem_text": len(text),
        "stem_total": len((q.get("stem") or "").strip()),
        "blocks": len(blocks),
        "max_block": max(blocks) if blocks else 0,
        "explanation": len(q.get("explanation") or ""),
        "max_option": max(opt_lens) if opt_lens else 0,
        "is_program": bool(blocks),
    }


def over_limit(m):
    """按硬线返回该题超标的项名列表（空列表 = 达标）。"""
    bad = []
    if m["stem_text"] > LIMIT_STEM_TEXT:
        bad.append("题干文字")
    if m["stem_total"] > LIMIT_STEM_TOTAL:
        bad.append("题干总长")
    if m["blocks"] > LIMIT_CODE_BLOCKS:
        bad.append("代码块数")
    if m["max_block"] > LIMIT_CODE_LINES:
        bad.append("代码块行数")
    if m["explanation"] > LIMIT_EXPLANATION:
        bad.append("解析")
    if m["max_option"] > LIMIT_OPTION_TEXT:
        bad.append("选项")
    return bad