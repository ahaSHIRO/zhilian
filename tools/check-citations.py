#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""规范章节号核查：解析里引用的 JLS / JVMS 章节号是否真实存在、标题是什么。

为什么需要它：解析里写「JLS §15.28 的常量表达式规则」这类引用时，**编号写错一位**
（常量表达式其实是 §15.29）不会让任何校验变红，却会让读者按错章节去查。2026-10-05
本轮题库复核中，全库 32 个被引编号逐个核过，唯一的错位就是这种「编号错位」。

**协议自证（本工具的硬前置）**：负结果（「该编号不存在」）在检索协议本身无效时会
变成假结论。所以脚本先跑阳性对照（已知存在的 §15.29 / §8.3.1.4 必须命中）与阴性
对照（已知不存在的 §15.99 必须不命中），任一不通过就**拒绝输出任何结论**并返回 2。
（来历见 pitfalls 3.14：本章节号核查过程中，作者本人连续两次因协议错误得出「8 个
编号全部不存在」的假结论。）

用法：
    # 1) 先准备官方页（离线核查，可反复跑）；需要代理时设环境变量 ZHILIAN_SPEC_PROXY
    $env:ZHILIAN_SPEC_PROXY = "http://127.0.0.1:<port>"; python tools/check-citations.py --fetch
    # 2) 核查（默认 spec-dir = app/build/spec-cache）
    python tools/check-citations.py
    # --batches-dir 缺省读环境变量 ZHILIAN_BATCHES_DIR，未设置回落当前目录
    # --proxy 也可用环境变量 ZHILIAN_SPEC_PROXY（本机配置一律走环境变量，不进仓库）

退出码：0 = 全部存在；1 = 有编号不存在或缺少对应章节页；2 = 协议自证未通过（结论作废）。
"""

import argparse
import glob
import html
import json
import os
import re
import sys
import urllib.request

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(TOOLS_DIR)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"

# 章节号首段 -> (文件名, 官方 URL)。「2」按 JVMS 处理：批次里 §2.5.x 引的是 JVMS 运行时数据区
CHAPTERS = {
    "2": ("jvms-se21-2.html", "https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html"),
    "4": ("jls-se21-4.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html"),
    "5": ("jls-se21-5.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html"),
    "8": ("jls-se21-8.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html"),
    "9": ("jls-se21-9.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html"),
    "12": ("jls-se21-12.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-12.html"),
    "14": ("jls-se21-14.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html"),
    "15": ("jls-se21-15.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html"),
    "17": ("jls-se21-17.html", "https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html"),
}
# 阳性/阴性对照（协议自证用）
POSITIVE = [("15.29", "jls-se21-15.html"), ("8.3.1.4", "jls-se21-8.html")]
NEGATIVE = ("15.99", "jls-se21-15.html")


def fetch_all(spec_dir, proxy):
    os.makedirs(spec_dir, exist_ok=True)
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy, "https": proxy}) if proxy else urllib.request.HTTPHandler())
    for ch, (fn, url) in sorted(CHAPTERS.items()):
        dest = os.path.join(spec_dir, fn)
        if os.path.exists(dest) and os.path.getsize(dest) > 50000:
            print(f"  已存在 {fn}（{os.path.getsize(dest)} 字节），跳过")
            continue
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with opener.open(req, timeout=90) as r:
                data = r.read()
            open(dest, "wb").write(data)
            print(f"  已下载 {fn} <- {url}（{len(data)} 字节）")
        except Exception as ex:
            print(f"  !!! 下载失败 {url}：{ex}")
            return False
    return True


def load(spec_dir, fn):
    p = os.path.join(spec_dir, fn)
    if not os.path.exists(p):
        return None
    return open(p, encoding="utf-8", errors="replace").read()


def section_title(num, text):
    """在原始 HTML 里找该编号的官方标题；找不到返回 None。

    三种形态都遇到过（见 pitfalls 3.14）：
      <a href="jls-15.html#jls-15.29">15.29. Constant Expressions</a>   ← 目录锚点，最干净
      <a name="jls-8.3.1.4">8.3.1.4.</a> volatile Fields                 ← 编号与标题隔着 </a>
    """
    esc_num = re.escape(num)
    for pat in (r'#(?:jls|jvms)-' + esc_num + r'"[^>]*>\s*([^<]{2,90})<',
                r'>\s*' + esc_num + r'\.\s*(?:<[^>]+>\s*)*([^<]{2,90})',
                r'(?m)^\s*' + esc_num + r'\.\s*(?:<[^>]+>\s*)*([^<]{2,90})'):
        m = re.search(pat, text)
        if m:
            t = re.sub(r'\s+', ' ', html.unescape(m.group(1))).strip()
            if t:
                return t
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="解析里的 JLS/JVMS 章节号核查（含协议自证）")
    ap.add_argument("--batches-dir", default=os.environ.get("ZHILIAN_BATCHES_DIR", "."))
    ap.add_argument("--spec-dir", default=os.path.join(REPO_ROOT, "app", "build", "spec-cache"))
    ap.add_argument("--fetch", action="store_true", help="先把各章官方页抓到 spec-dir")
    ap.add_argument("--proxy", default=os.environ.get("ZHILIAN_SPEC_PROXY"),
                    help="抓取用代理；也可用环境变量 ZHILIAN_SPEC_PROXY")
    a = ap.parse_args()

    if a.fetch:
        print(f"=== 抓取官方页 -> {a.spec_dir}（代理：{a.proxy or '直连'}） ===")
        if not fetch_all(a.spec_dir, a.proxy):
            return 1

    # ---- 协议自证 ----
    print("=== 步骤 0：检索协议自证（不通过则拒绝输出任何结论） ===")
    ok = True
    for num, fn in POSITIVE:
        txt = load(a.spec_dir, fn)
        t = section_title(num, txt) if txt else None
        print(f"  阳性对照 §{num:<10} -> {t!r}   {'通过' if t else '失败'}")
        ok &= bool(t)
    txt = load(a.spec_dir, NEGATIVE[1])
    neg = section_title(NEGATIVE[0], txt) if txt else None
    print(f"  阴性对照 §{NEGATIVE[0]:<9} -> {neg!r}   {'通过（确不存在）' if not neg else '失败'}")
    ok &= not neg
    if not ok:
        print("\n协议自证未通过 —— 结论作废（先 --fetch 取到官方页，再检查章节号写法）")
        return 2

    # ---- 抽取批次里的引用 ----
    refs, ctx = {}, []
    for f in sorted(glob.glob(os.path.join(a.batches_dir, "batch-*.json"))):
        d = json.load(open(f, encoding="utf-8"))
        for q in d.get("questions", []):
            blob = (q.get("explanation") or "") + " " + json.dumps(q.get("source") or {}, ensure_ascii=False)
            for m in re.finditer(r"§+\s*(\d+(?:\.\d+)*)", blob):
                num = m.group(1)
                r = refs.setdefault(num, {"文件": set(), "题": set()})
                r["文件"].add(os.path.basename(f))
                r["题"].add(q["questionId"][:8])
                ctx.append((os.path.basename(f).replace("batch-", "").replace(".json", ""),
                            q["questionId"][:8], num, blob[max(0, m.start() - 55):m.end() + 55]))

    print(f"\n=== 步骤 1：{len(refs)} 个不同章节号的存在性与官方标题 ===")
    missing, nopage = [], []
    for num in sorted(refs, key=lambda s: [int(x) for x in s.split(".")]):
        ch = num.split(".")[0]
        if ch not in CHAPTERS:
            print(f"  §{num:<10} 未登记该章 -> {sorted(refs[num]['题'])[:3]}")
            nopage.append(num)
            continue
        fn = CHAPTERS[ch][0]
        txt = load(a.spec_dir, fn)
        if txt is None:
            print(f"  §{num:<10} 缺章节页 {fn}（先跑 --fetch）")
            nopage.append(num)
            continue
        title = section_title(num, txt)
        if not title:
            missing.append(num)
        print(f"  §{num:<10} {'存在' if title else '**不存在**':<12} {(title or '')[:50]:<52} {fn}")

    print("\n=== 步骤 2：引用语境（判断「引的话题与该章标题是否对得上」） ===")
    for b, qid, num, s in ctx:
        print(f"  [{b} {qid}] §{num:<10} …{s.strip()[:100]}…")

    print(f"\n汇总：{len(refs)} 个编号；不存在 {len(missing)} 个 -> {missing or '无'}；"
          f"缺页/未登记 {len(nopage)} 个 -> {nopage or '无'}")
    if missing or nopage:
        print("提示：编号不存在 = 解析里的章节号写错；缺页 = 先 --fetch 再核。")
        return 1
    print("全部章节号存在。仍需人工确认「所引话题与该章标题是否一致」——本工具只能证明编号存在。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
