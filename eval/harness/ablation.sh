#!/bin/bash
# 自研 AI Harness 消融实验 runner。
# 用法: ./ablation.sh "FULL NO_ADVERSARIAL NO_VERIFY" "expr fib"
#   参1=要跑的配置(空格分隔，默认全部)  参2=要跑的任务(默认全部)
# 前提: repo9 → /Users/5miles/Desktop/测试program (direct 模式)；.llm-demo.env 有真 DeepSeek key。
# 产出: results.csv + report.md
set -u
REPO=/Users/5miles/Desktop/迭代repolens
H=$REPO/eval/harness
PROJ=/Users/5miles/Desktop/测试program
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
ALL_CONFIGS="FULL NO_ADVERSARIAL NO_VERIFY NO_TODO NO_COMPACT NO_BASH THIN_DESC"
ALL_TASKS="expr fib palindrome stack"
CONFIGS="${1:-$ALL_CONFIGS}"
TASKS="${2:-$ALL_TASKS}"
RESULTS=$H/results.csv
echo "config,task,result,false_success" > "$RESULTS"

env_for() { # 打印某配置的额外 env 覆盖
  case "$1" in
    FULL) echo "" ;;
    NO_ADVERSARIAL) echo "REPOLENS_KERNEL_ADVERSARIAL_REVIEW=false" ;;
    NO_VERIFY) echo "REPOLENS_KERNEL_ABLATION_DISABLED_TOOLS=runVerification REPOLENS_KERNEL_ADVERSARIAL_REVIEW=false" ;;
    NO_TODO) echo "REPOLENS_KERNEL_ABLATION_DISABLED_TOOLS=TodoWrite" ;;
    NO_COMPACT) echo "REPOLENS_KERNEL_CONTEXT_WINDOW_TOKENS=0" ;;
    NO_BASH) echo "REPOLENS_KERNEL_ABLATION_DISABLED_TOOLS=bash" ;;
    THIN_DESC) echo "REPOLENS_KERNEL_ABLATION_THIN_DESC=true" ;;
    *) echo "" ;;
  esac
}

start_app() { # $1=额外env
  local pid; pid=$(lsof -nP -tiTCP:8083 -sTCP:LISTEN 2>/dev/null); [ -n "$pid" ] && kill "$pid" && sleep 4
  cd "$REPO"
  set -a; source .llm-demo.env; set +a
  env $1 REPOLENS_KERNEL_AGENT_ENABLED=true REPOLENS_AUTH_ENABLED=false \
      REPOLENS_REPO_STORAGE_ROOT="$REPO/workspace/repos" SERVER_PORT=8083 \
      mvn -o spring-boot:run > /tmp/abl-app.log 2>&1 &
  # 等就绪
  for i in $(seq 1 40); do grep -q "Started RepoLensApplication" /tmp/abl-app.log && break; sleep 2; done
  sleep 2
}

run_task() { # $1=config $2=task
  local cfg=$1 t=$2
  rm -rf "$PROJ/src" "$PROJ/target" "$PROJ/.rk" "$PROJ/.git" "$PROJ/pom.xml" 2>/dev/null
  cp "$H/base-pom.xml" "$PROJ/pom.xml"
  mkdir -p "$PROJ/src/main/java/com/demo"
  cp -r "$H/tasks/$t/seed/." "$PROJ/" 2>/dev/null
  local body sse ans result fs
  body=$(python3 -c "import json;print(json.dumps({'question':open('$H/tasks/$t/prompt.txt').read().strip(),'mode':'code','permissionMode':'AUTO','topK':5}))")
  sse=$(curl -sN --max-time 340 -X POST http://localhost:8083/api/repos/9/chat/answer/stream \
        -H "Content-Type: application/json" -H "X-User-Id: 1" -d "$body")
  echo "$sse" > "/tmp/abl_${cfg}_${t}.sse"
  # checker
  cp "$H/tasks/$t/Checker.java" "$PROJ/src/main/java/com/demo/Checker.java"
  ( cd "$PROJ" && mvn -o -q compile >/dev/null 2>&1 )
  result=$(cd "$PROJ" && java -cp target/classes com.demo.Checker 2>/dev/null | grep -oE "ABLATION_(PASS|FAIL)[^ ]*" | head -1)
  [ -z "$result" ] && result="ABLATION_FAIL:compile_or_run_error"
  # 谎报检测:最终答复自称完成/通过，但 checker FAIL
  ans=$(echo "$sse" | python3 -c "import sys,json
a=''
for l in sys.stdin:
 l=l.strip()
 if l.startswith('data:'):
  try:
   d=json.loads(l[5:])
   if isinstance(d,dict) and d.get('agentMode') and d.get('answer'): a=d['answer']
  except: pass
print(a)" 2>/dev/null)
  fs=0
  if echo "$result" | grep -q FAIL && echo "$ans" | grep -qE "完成|通过|成功|全部搞定|已实现"; then fs=1; fi
  echo "$cfg,$t,$result,$fs" | tee -a "$RESULTS"
}

for cfg in $CONFIGS; do
  echo "===== 配置 $cfg 启动 app ====="
  start_app "$(env_for "$cfg")"
  for t in $TASKS; do echo "--- $cfg / $t ---"; run_task "$cfg" "$t"; done
done
echo "===== 全部完成，results.csv 已生成 ====="
