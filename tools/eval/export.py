#!/usr/bin/env python3
"""导出已分析条目，供人工盲标后算准确率。

用法：
    python3 tools/eval/export.py --base-url http://8.136.19.57:8080 \
        --sample 30 --out tools/eval/labels.csv

产出两个文件：
    labels.csv   只有原文 + 空的 human_sentiment / human_note  ← 你标这个
    answers.csv  模型的判断与置信度，按 id 对应              ← 别提前看

为什么拆两个文件：这就是「盲标」。模型结论摆在旁边时，人会不自觉地被它带偏
（顺手点了同意），最后算出来的准确率是虚高的。先把 30 条标完再跑分，数字才算数。

只依赖标准库，不需要 pip install。其余取舍：
- 只导出 analysis_status=done 的条目 —— 没结论的没法评测。
- 默认随机抽样而不是取最新 N 条：最新 30 条往往集中在同一时段、同一题材，
  会让准确率严重失真。--seed 固定，保证可复现。
- 输出 utf-8-sig：Excel / Numbers 双击打开中文不乱码。
- 强制不走系统代理：本机代理会给直连返回 502 而不是 connection refused，
  排查时极容易误判成服务挂了。
"""

import argparse
import base64
import csv
import json
import os
import random
import sys
import urllib.error
import urllib.request

LABEL_FIELDS = [
    "id", "publish_time", "level", "title", "content_200",
    "human_sentiment", "human_note",
]
ANSWER_FIELDS = ["id", "model", "model_sentiment", "model_confidence", "model_summary"]


def build_opener():
    # 空 ProxyHandler = 显式禁用环境变量里的 http_proxy/https_proxy
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fetch_page(opener, base, user, password, limit, before):
    url = f"{base.rstrip('/')}/api/news?limit={limit}"
    if before:
        url += f"&before={before}"
    req = urllib.request.Request(url)
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    req.add_header("Authorization", "Basic " + token)
    with opener.open(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def collect(opener, base, user, password, want_pages, page_size):
    """按 before 游标往前翻，攒够 want_pages 页就停。"""
    items, before, pages = [], 0, 0
    while pages < want_pages:
        body = fetch_page(opener, base, user, password, page_size, before)
        page = body.get("items") or []
        if not page:
            break
        items.extend(page)
        pages += 1
        before = page[-1]["id"]
        if not body.get("hasMore"):
            break
    return items


def one_line(s, limit=None):
    t = (s or "").replace("\n", " ").replace("\r", " ").strip()
    return t[:limit] if limit else t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--sample", type=int, default=30, help="抽样条数")
    ap.add_argument("--pages", type=int, default=10, help="最多翻几页（每页 200）")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default="tools/eval/labels.csv")
    ap.add_argument("--answers", default=None, help="默认与 --out 同目录的 answers.csv")
    args = ap.parse_args()

    answers_path = args.answers or os.path.join(os.path.dirname(args.out) or ".", "answers.csv")

    opener = build_opener()
    try:
        items = collect(opener, args.base_url, args.user, args.password, args.pages, 200)
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}：认证失败或接口不可用（检查账号密码）", file=sys.stderr)
        return 2
    except Exception as e:
        print(f"拉取失败：{e}", file=sys.stderr)
        return 2

    done = [it for it in items if it.get("analysisStatus") == "done" and it.get("analysis")]
    print(f"共拉到 {len(items)} 条，其中已分析 {len(done)} 条")
    if not done:
        print("没有已分析的条目，先在页面上点几条「分析」再来", file=sys.stderr)
        return 1

    # 按 id 排序后固定种子抽样，同一条命令每次抽到同一批，评测结果可比
    done.sort(key=lambda x: x["id"])
    k = min(args.sample, len(done))
    picked = sorted(random.Random(args.seed).sample(done, k), key=lambda x: x["id"])

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=LABEL_FIELDS)
        w.writeheader()
        for it in picked:
            w.writerow({
                "id": it.get("id"),
                "publish_time": it.get("time"),
                "level": it.get("level") or "",
                "title": one_line(it.get("title")),
                "content_200": one_line(it.get("text"), 200),
                "human_sentiment": "",
                "human_note": "",
            })

    with open(answers_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=ANSWER_FIELDS)
        w.writeheader()
        for it in picked:
            a = it.get("analysis") or {}
            w.writerow({
                "id": it.get("id"),
                "model": a.get("model") or "",
                "model_sentiment": a.get("sentiment") or "",
                "model_confidence": a.get("confidence", ""),
                "model_summary": one_line(a.get("summary")),
            })

    print(f"待标注 → {args.out}（{k} 条，只含原文，不含模型结论）")
    print(f"模型答案 → {answers_path}（标完之前别看）")
    print("接下来：把 human_sentiment 填成 bullish / bearish / neutral（或 利好/利空/中性），")
    print(f"        然后跑 python3 tools/eval/score.py --csv {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
