#!/usr/bin/env python3
"""召回率评测：对 golden_qa.jsonl 逐条调用 /api/retrieve，统计 Recall@K。

用法：
    python3 eval/eval_recall.py --base http://localhost:8080 --k 6

每个问题配置了若干关键词组（expect），只要命中的 chunk 同时包含某一组的全部关键词，
即认为该问题召回成功。这样衡量的是"检索有没有把能回答问题的片段捞上来"，
不依赖模型生成质量。
"""
import argparse
import json
import os
import sys
import urllib.request


def retrieve(base, question, top_k):
    payload = json.dumps({"question": question}).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + "/api/retrieve",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("code") != 0:
        raise RuntimeError("api error: %s" % body.get("message"))
    return body["data"][:top_k]


def matched(sources, groups):
    for source in sources:
        text = (source.get("text") or "") + (source.get("title") or "")
        for group in groups:
            if all(kw in text for kw in group):
                return True, source.get("chunkId"), source.get("from")
    return False, None, None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--k", type=int, default=6)
    parser.add_argument("--file", default=os.path.join(os.path.dirname(__file__), "golden_qa.jsonl"))
    args = parser.parse_args()

    with open(args.file, encoding="utf-8") as fh:
        cases = [json.loads(line) for line in fh if line.strip()]

    hits = 0
    print("%-6s %-6s %-10s %s" % ("id", "hit", "from", "question"))
    print("-" * 78)
    for case in cases:
        try:
            sources = retrieve(args.base, case["question"], args.k)
            ok, chunk_id, src = matched(sources, case["expect"])
        except Exception as exc:  # noqa: BLE001
            print("%-6s %-6s %-10s %s  (error: %s)" % (case["id"], "ERR", "-", case["question"], exc))
            continue
        hits += 1 if ok else 0
        print("%-6s %-6s %-10s %s" % (case["id"], "Y" if ok else "N", src or "-", case["question"]))

    total = len(cases)
    recall = hits / total * 100 if total else 0.0
    print("-" * 78)
    print("Recall@%d = %d/%d = %.1f%%" % (args.k, hits, total, recall))
    return 0 if hits == total else 1


if __name__ == "__main__":
    sys.exit(main())
