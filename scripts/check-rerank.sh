#!/usr/bin/env bash
# 单独验证百炼重排模型（不需要启动应用）
#
# 用法:
#   ./scripts/check-rerank.sh                              默认用例
#   ./scripts/check-rerank.sh "年假有几天"                   只给 query，用默认候选
#   ./scripts/check-rerank.sh "年假有几天" "满一年7天" "满五年15天" "出差600元"
#   AI_RERANK_MODEL=gte-rerank ./scripts/check-rerank.sh     换模型对比
#   ./scripts/check-rerank.sh -h

set -uo pipefail

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

KEY="${DASHSCOPE_API_KEY:-${AI_API_KEY:-}}"
if [ -z "$KEY" ]; then
  echo "错误: DASHSCOPE_API_KEY / AI_API_KEY 都没设置" >&2
  echo "先执行: export DASHSCOPE_API_KEY=sk-xxxx" >&2
  exit 1
fi

MODEL="${AI_RERANK_MODEL:-qwen3.7-text-rerank}"
BASE="${AI_RERANK_BASE_URL:-https://dashscope.aliyuncs.com}"
ENDPOINT="/api/v1/services/rerank/text-rerank/text-rerank"

QUERY="${1:-出差住宿标准}"
if [ $# -gt 0 ]; then shift; fi
if [ $# -eq 0 ]; then
  set -- "一线城市600元/天" "员工年假7天"
fi

payload=$(python3 - "$MODEL" "$QUERY" "$@" <<'PY'
import json, sys
model, query, docs = sys.argv[1], sys.argv[2], sys.argv[3:]
print(json.dumps({"model": model,
                  "input": {"query": query, "documents": docs},
                  "parameters": {"return_documents": False, "top_n": len(docs)}},
                 ensure_ascii=False))
PY
)

echo "model  : $MODEL"
echo "query  : $QUERY"
echo "候选   :"
python3 -c '
import json, sys
p = json.loads(sys.argv[1])
for i, d in enumerate(p["input"]["documents"]):
    print("  %d. %s" % (i, d))
' "$payload"
echo

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

code=$(curl -sS -o "$tmp" -w '%{http_code}' --max-time 30 \
  -X POST "$BASE$ENDPOINT" \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d "$payload" 2>/dev/null)

echo "HTTP $code"
echo

if [ "$code" = "200" ]; then
  python3 -c '
import json, sys
p = json.loads(sys.argv[1])
body = json.load(open(sys.argv[2], encoding="utf-8"))
docs = p["input"]["documents"]
results = body.get("output", {}).get("results", [])
if not results:
    print("没有 results，原始返回：")
    print(json.dumps(body, ensure_ascii=False, indent=2))
    sys.exit(1)
print("重排结果（按分数从高到低）：")
for r in sorted(results, key=lambda x: -(x.get("relevance_score") or 0)):
    i = r.get("index")
    txt = docs[i] if isinstance(i, int) and 0 <= i < len(docs) else "?"
    print("  score=%-8s [%s] %s" % (r.get("relevance_score"), i, txt))
print()
print("判断标准: 真正能回答 query 的那条，分数应明显高于其他条目")
' "$payload" "$tmp"
  exit 0
fi

python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1])), ensure_ascii=False, indent=2))' "$tmp" 2>/dev/null || cat "$tmp"
echo
case "$code" in
  401) echo "→ Key 无效或过期" >&2 ;;
  400) echo "→ 多半是模型名不存在，检查 AI_RERANK_MODEL" >&2 ;;
  404) echo "→ 端点路径不对" >&2 ;;
  *)   echo "→ 看上面返回内容" >&2 ;;
esac
exit 1
