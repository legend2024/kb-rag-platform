#!/usr/bin/env bash
# 一键启动：查环境 -> 起数据库 -> 跑应用
# 用法: ./scripts/start.sh
#       ./scripts/start.sh --build
#       ./scripts/start.sh --kb.rag.rerank.enabled=false
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAR="kb-bootstrap/target/kb-rag-platform.jar"
BUILD=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --build) BUILD=1 ;;
    *) ARGS+=("$a") ;;
  esac
done

# ---- 1. 找 java（macOS 的 /usr/bin/java 是空壳，必须实际跑一次才算数）----
JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
elif java -version >/dev/null 2>&1; then
  JAVA_BIN="java"
else
  FOUND=$(ls -d "$HOME"/.jdks/jdk-17*/Contents/Home/bin/java 2>/dev/null | head -1 || true)
  if [ -n "$FOUND" ]; then
    JAVA_BIN="$FOUND"
  fi
fi

if [ -z "$JAVA_BIN" ]; then
  echo "[x] 找不到可用的 JDK 17" >&2
  echo '    把这两行加进 ~/.zshrc 后新开终端：' >&2
  echo '    export JAVA_HOME="$HOME/.jdks/jdk-17.0.20.1+1/Contents/Home"' >&2
  echo '    export PATH="$JAVA_HOME/bin:$PATH"' >&2
  exit 1
fi
# 让后续的 mvn 也用同一个 JDK（mvn 依赖 JAVA_HOME，否则会调用 /usr/bin/java 空壳）
if [ "$JAVA_BIN" != "java" ]; then
  export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi
echo "[1/4] $("$JAVA_BIN" -version 2>&1 | head -1)"
echo "      JAVA_HOME=${JAVA_HOME:-未设置}"

# ---- 2. 检查 API Key ----
KEY="${DASHSCOPE_API_KEY:-${AI_API_KEY:-}}"
if [ -z "$KEY" ]; then
  echo "[x] 未设置 DASHSCOPE_API_KEY（或 AI_API_KEY）" >&2
  exit 1
fi
echo "[2/4] API Key 已设置，长度 ${#KEY}"

# ---- 3. 检查/生成 jar ----
MVN_BIN="$(command -v mvn 2>/dev/null || echo "$HOME/.maven/apache-maven-3.9.16/bin/mvn")"
if [ "$BUILD" = "1" ] || [ ! -f "$JAR" ]; then
  if [ ! -x "$MVN_BIN" ]; then
    echo "[x] 需要 mvn，但 $MVN_BIN 不可执行" >&2
    exit 1
  fi
  echo "[3/4] 编译中..."
  "$MVN_BIN" -q -DskipTests package
  echo "      编译完成"
else
  echo "[3/4] jar 已存在，跳过编译（加 --build 强制重编）"
  NEWER=$(find . -name '*.java' -newer "$JAR" -o -name '*.yml' -newer "$JAR" 2>/dev/null | grep -v '/target/' | head -3 || true)
  if [ -n "$NEWER" ]; then
    echo "      注意：这些文件比 jar 新，改动尚未生效"
    printf '%s\n' "$NEWER" | sed 's/^/        /'
  fi
fi

# ---- 4. 数据库 ----
if command -v docker >/dev/null 2>&1; then
  if docker compose ps --format '{{.State}}' 2>/dev/null | grep -q running; then
    echo "[4/4] 数据库已在运行"
  else
    echo "[4/4] 启动数据库容器..."
    docker compose up -d >/dev/null 2>&1
    for i in $(seq 1 30); do
      if docker compose ps --format '{{.Status}}' 2>/dev/null | grep -q healthy; then
        echo "      数据库就绪"
        break
      fi
      sleep 1
    done
  fi
else
  echo "[4/4] 未检测到 docker，跳过（假设你已自备 PostgreSQL）"
fi

echo
echo ">>> "$JAVA_BIN" -jar $JAR ${ARGS[*]:-}"
echo
exec "$JAVA_BIN" -jar "$JAR" ${ARGS[@]+"${ARGS[@]}"}
