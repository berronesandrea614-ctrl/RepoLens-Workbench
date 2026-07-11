#!/usr/bin/env bash
# RepoLens 一键启动：中间件(docker) + 后端(Spring Boot) + 前端(Tauri/Vite)。
#
# 用法：
#   export REPOLENS_LLM_API_KEY=sk-xxxx   # 用 DeepSeek（可选；不设则用 mock，无需联网）
#   ./scripts/start-all.sh                # 浏览器模式（默认）
#   ./scripts/start-all.sh --tauri        # 原生 App 窗口
#
# 说明：key 只走环境变量，绝不写入任何文件。mock 模式下整条链路可跑通（无真实语义/回答）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "[1/4] 起中间件（mysql/redis/etcd/minio/milvus）…"
docker compose up -d mysql redis etcd minio milvus

echo "[2/4] 构建后端 jar（如无）…"
[ -f target/repolens-0.0.1-SNAPSHOT.jar ] || mvn -q -DskipTests package

echo "[3/4] 启动后端 :8080 …"
if [ -n "${REPOLENS_LLM_API_KEY:-}" ]; then
  export REPOLENS_LLM_PROVIDER=deepseek-compatible
  export REPOLENS_LLM_BASE_URL=https://api.deepseek.com
  export REPOLENS_LLM_MODEL_NAME=deepseek-chat
  export REPOLENS_LLM_TIMEOUT_MS=30000
  echo "     LLM = DeepSeek"
else
  echo "     LLM = mock（未设 REPOLENS_LLM_API_KEY；链路可跑，回答为占位）"
fi
export REPOLENS_AGENT_ENABLED=true
nohup java \
  -Drepolens.repo-storage-root="$ROOT/workspace/repos" \
  -Drepolens.repo.allowed-local-repo-root="$HOME" \
  -Drepolens.index.consumer-enabled=false \
  -jar target/repolens-0.0.1-SNAPSHOT.jar > /tmp/repolens-backend.log 2>&1 &
echo "     后端日志：/tmp/repolens-backend.log"

echo "[4/4] 启动前端…"
cd repolens-desktop
[ -d node_modules ] || npm install
if [ "${1:-}" = "--tauri" ]; then
  npm run tauri dev
else
  echo "     浏览器打开 http://localhost:1420"
  npm run dev
fi
