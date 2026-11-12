#!/usr/bin/env python3
"""读标注好的 CSV，算准确率 / 混淆矩阵 / 置信度分层表现。

用法：
    python3 tools/eval/score.py --csv tools/eval/labels.csv

模型答案从同目录的 answers.csv 按 id 合并（export.py 生成的）。
如果你把模型结论直接写进了 labels.csv，也支持从行内读取。

未标注（human_sentiment 为空）的行会被跳过，并在末尾提示还剩多少条。

为什么要有「置信度分层」这一栏：
整体准确率只是一个数，无法指导下一步。真正有用的是「模型自评高置信时准不准」——
高置信错、低置信对，说明 self-confidence 不可信，提示词里「低置信给中性」那条没生效；
反过来说，高置信段明显高于低置信段，才证明这条约束真的在起作用。
"""

import argparse
import csv
import os
import sys
from collections import Counter

LABELS = ["bullish", "neutral", "bearish"]
CN = {"bullish": "利好", "neutral": "中性", "bearish": "利空"}

# 允许手填中文或简写，统一到三个标准值。标注时顺手写「看多」也算，降低标注摩擦。
ALIASES = {
    "bullish": "bullish", "利好": "bullish", "看多": "bullish", "多": "bullish", "up": "bullish",
    "bearish": "bearish", "利空": "bearish", "看空": "bearish", "空": "bearish", "down": "bearish",
    "neutral": "neutral", "中性": "neutral", "无关": "neutral", "平": "neutral", "n": "neutral",
}


def normalize(v):
    if v is None:
        return None
    s = str(v).strip().lower()
    return ALIASES.get(s) if s else None


def read_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def load_answers(path):
    """id → 模型答案。文件不存在时返回空字典，调用方回退到行内字段。"""
    if not path or not os.path.exists(path):
        return {}
    out = {}
    for r in read_csv(path):
        out[str(r.get("id")).strip()] = r
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="tools/eval/labels.csv")
    ap.add_argument("--answers", default=None, help="默认取 --csv 同目录的 answers.csv")
    ap.add_argument("--show-errors", type=int, default=8, help="打印多少条判错的样本")
    args = ap.parse_args()

    answers_path = args.answers or os.path.join(os.path.dirname(args.csv) or ".", "answers.csv")
    answers = load_answers(answers_path)

    rows = read_csv(args.csv)
    if not rows:
        print("CSV 是空的", file=sys.stderr)
        return 2

    # 找不到答案文件时必须硬报错。曾经踩过：labels.csv 被挪到别的目录后
    # answers.csv 没跟着走，代码回退到「行内字段为空 → 一律当 neutral」，
    # 结果在模型几乎全判中性的样本上算出 85% 的假准确率。静默降级比报错危险得多。
    has_inline = any(str(r.get("model_sentiment") or "").strip() for r in rows)
    if not answers and not has_inline:
        print(f"找不到模型答案：{answers_path}", file=sys.stderr)
        print("用 --answers 指定 answers.csv，或确认它和 labels.csv 在同一个目录。", file=sys.stderr)
        return 2

    scored, skipped, bad = [], 0, []
    for r in rows:
        human = normalize(r.get("human_sentiment"))
        if human is None:
            skipped += 1
            if str(r.get("human_sentiment") or "").strip():
                bad.append((r.get("id"), r.get("human_sentiment")))
            continue
        ans = answers.get(str(r.get("id")).strip()) or r
        model = normalize(ans.get("model_sentiment"))
        if model is None:
            model = "neutral"   # 与后端 normalize() 一致：非法值按中性处理
        scored.append({
            "id": r.get("id"),
            "title": (r.get("title") or "")[:40],
            "model": model,
            "human": human,
            "conf": ans.get("model_confidence") or "",
            "summary": ans.get("model_summary") or "",
            "note": (r.get("human_note") or "").strip(),
        })

    if bad:
        print(f"⚠ 有 {len(bad)} 行标注无法识别，已跳过：" +
              ", ".join(f"{i}={v}" for i, v in bad[:5]), file=sys.stderr)

    if not scored:
        print(f"一条都没标注（共 {len(rows)} 条）。把 human_sentiment 列填上再跑。", file=sys.stderr)
        return 1

    if not answers:
        print(f"⚠ 没找到 {answers_path}，模型结论从 labels.csv 行内读取", file=sys.stderr)

    n = len(scored)
    correct = sum(1 for s in scored if s["model"] == s["human"])
    print("=" * 52)
    print(f"样本数 {n}（未标注 {skipped} 条已跳过）")
    print(f"准确率 {correct}/{n} = {correct / n:.1%}")
    print("=" * 52)

    print("\n混淆矩阵（行=人工标注，列=模型判断）")
    print("真值\\模型".ljust(10) + "".join(CN[l].ljust(8) for l in LABELS))
    for truth in LABELS:
        line = CN[truth].ljust(10)
        for pred in LABELS:
            c = sum(1 for s in scored if s["human"] == truth and s["model"] == pred)
            line += str(c).ljust(8)
        print(line)

    # 每类 P/R/F1：单看准确率会掩盖「全判中性」这种偷懒策略
    print("\n分类指标")
    print("类别".ljust(8) + "精确率".ljust(10) + "召回率".ljust(10) + "F1".ljust(8) + "样本数")
    for l in LABELS:
        tp = sum(1 for s in scored if s["human"] == l and s["model"] == l)
        pred_all = sum(1 for s in scored if s["model"] == l)
        truth_all = sum(1 for s in scored if s["human"] == l)
        p = tp / pred_all if pred_all else 0.0
        r = tp / truth_all if truth_all else 0.0
        f1 = 2 * p * r / (p + r) if (p + r) else 0.0
        print(CN[l].ljust(8) + f"{p:.1%}".ljust(10) + f"{r:.1%}".ljust(10) +
              f"{f1:.2f}".ljust(8) + str(truth_all))

    print("\n置信度分层")
    for name, pred in [("≥0.7", lambda c: c >= 0.7),
                       ("0.5~0.7", lambda c: 0.5 <= c < 0.7),
                       ("<0.5", lambda c: c < 0.5)]:
        sub = []
        for s in scored:
            try:
                c = float(s["conf"])
            except (TypeError, ValueError):
                continue
            if pred(c):
                sub.append(s)
        if sub:
            ok = sum(1 for s in sub if s["model"] == s["human"])
            print(f"  {name:9s} {ok}/{len(sub)} = {ok / len(sub):.1%}")

    errs = [s for s in scored if s["model"] != s["human"]]
    if errs and args.show_errors:
        print(f"\n判错样本（最多 {args.show_errors} 条）")
        for s in errs[:args.show_errors]:
            print(f"  #{s['id']} 模型{CN[s['model']]}({s['conf']}) / 人工{CN[s['human']]}  {s['title']}")
            if s["summary"]:
                print(f"        模型理由：{s['summary'][:50]}")
    elif not errs:
        print("\n没有判错的样本。")

    # 「全判中性」是常见退化策略，单独点出来
    pred_counter = Counter(s["model"] for s in scored)
    neutral_ratio = pred_counter["neutral"] / n
    truth_neutral = sum(1 for s in scored if s["human"] == "neutral") / n
    if neutral_ratio - truth_neutral > 0.25:
        print(f"\n⚠ 模型给了 {neutral_ratio:.0%} 的「中性」，而人工只有 {truth_neutral:.0%}："
              "可能提示词过于保守，把该给方向的都推成中性了")
    return 0


if __name__ == "__main__":
    sys.exit(main())
