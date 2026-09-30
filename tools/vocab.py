#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""标签封闭词表加载器（question-authoring.md §八是唯一权威源）。

batch-check.py（词表拦截）与 bank-stats.py（存量标签报告）共用，
避免"脚本内硬编码一份、文档登记一份"的双源漂移。

解析约定（与 §八 现行表格格式钉死）：
  - 以 "## 八、标签词表" 到下一个 "## " 之间的内容为准；
  - "### <节名>" 决定科目：Kotlin→kotlin、Java→java、ArkTS→arkts、
    面试*/常见面试题*→interview（节名括号里的说明忽略）；
  - 表格行按 | 切列，取第 2 列，以顿号拆分标签；跳过表头与分隔行；
  - 标签尾部的 \\* 表示冻结标记（如 basics\\*），剥掉后进词表，
    冻结语义由 FROZEN_TAGS 表达。
"""

import os
import re

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC_PATH = os.path.join(REPO_ROOT, "docs", "conventions", "question-authoring.md")

SECTION_HEADER = "## 八、标签词表"

# 节名前缀 → subject（顺序无关，前缀互不冲突）
_SUBJECT_PREFIXES = [
    ("Kotlin", "kotlin"),
    ("Java", "java"),
    ("ArkTS", "arkts"),
    ("常见面试题", "interview"),
    ("面试", "interview"),
]

# 冻结标签：存量题保留、新题不得使用（§八 表内以 \* 标注）
FROZEN_TAGS = {"basics"}


def load_vocabulary(doc_path=DOC_PATH):
    """解析 §八，返回 {subject: set(标签)}；无表格的科目（如 ArkTS）得到空集。"""
    with open(doc_path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    try:
        start = next(i for i, l in enumerate(lines) if l.startswith(SECTION_HEADER))
    except StopIteration:
        raise RuntimeError(f"在 {doc_path} 中找不到「{SECTION_HEADER}」节")
    vocab = {}
    subject = None
    for line in lines[start + 1:]:
        if line.startswith("## "):  # 下一节，§八结束
            break
        if line.startswith("### "):
            header = line[4:].strip()
            subject = next((s for p, s in _SUBJECT_PREFIXES if header.startswith(p)), None)
            continue
        if subject is None or not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2 or cells[0] in ("分类",) or set(cells[0]) <= {"-", ":"}:
            continue  # 表头行 / 分隔行
        for tag in cells[1].split("、"):
            # 剥行内注释（如「collections（复用「基础面试题」）」）与冻结标记 basics\*
            tag = re.sub(r"[（(][^）)]*[）)]", "", tag).strip()
            tag = tag.rstrip("*").rstrip("\\").strip()
            if tag:
                vocab.setdefault(subject, set()).add(tag)
    return vocab
