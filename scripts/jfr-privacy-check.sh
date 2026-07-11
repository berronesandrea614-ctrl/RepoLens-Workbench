#!/usr/bin/env bash
# =============================================================================
# scripts/jfr-privacy-check.sh
# RepoLens JFR Socket 捕获脚本 — 代码0出网 JVM 层交叉验证
#
# 用途：抓取 RepoLens 后端进程的 JVM 层 Socket 事件，对比应用层 EgressPolicy
#       网关日志，完成 OS/JVM 层面的零出网交叉验证（Feature G-P2）。
#
# 前置条件：
#   - JDK 11+（Java Flight Recorder 内置于 JDK，无需额外安装）
#   - RepoLens 后端已在本地运行（默认端口 8080）
#   - 需要 sudo 或足够权限以附加到目标 JVM 进程
#
# 使用示例：
#   chmod +x scripts/jfr-privacy-check.sh
#   ./scripts/jfr-privacy-check.sh              # 自动探测 PID，录制 60s
#   ./scripts/jfr-privacy-check.sh 12345        # 指定 PID
#   ./scripts/jfr-privacy-check.sh 12345 120    # 指定 PID + 时长（秒）
# =============================================================================

set -euo pipefail

# ─── 参数 ────────────────────────────────────────────────────────────────────
TARGET_PID="${1:-}"
DURATION="${2:-60}"
JFR_FILE="/tmp/repolens-privacy-$(date +%Y%m%d-%H%M%S).jfr"

# ─── 探测 PID ────────────────────────────────────────────────────────────────
if [[ -z "$TARGET_PID" ]]; then
    echo "[INFO] 自动探测 RepoLens 后端 PID..."
    TARGET_PID=$(jcmd 2>/dev/null | grep -i "repolens\|spring\|Application" | awk '{print $1}' | head -1 || true)
    if [[ -z "$TARGET_PID" ]]; then
        echo "[ERROR] 未能自动探测到 RepoLens 进程，请手动指定 PID："
        echo "  jcmd | grep -i repolens"
        echo "  ./scripts/jfr-privacy-check.sh <PID>"
        exit 1
    fi
    echo "[INFO] 探测到 PID: $TARGET_PID"
fi

echo ""
echo "============================================================"
echo "  RepoLens JFR Socket 捕获 — 代码0出网 JVM 层交叉验证"
echo "  PID      : $TARGET_PID"
echo "  时长     : ${DURATION}s"
echo "  输出文件 : $JFR_FILE"
echo "============================================================"
echo ""

# ─── 步骤 1: 开始 JFR 录制（Socket Write + Read 事件）───────────────────────
echo "[步骤 1] 开始 JFR 录制..."
jcmd "$TARGET_PID" JFR.start \
    name=privacy-check \
    settings=profile \
    duration="${DURATION}s" \
    filename="$JFR_FILE" \
    jdk.SocketWrite#enabled=true \
    jdk.SocketRead#enabled=true \
    jdk.SocketConnect#enabled=true 2>&1 || {
    echo "[WARN] JFR.start 失败，尝试不带 jdk.SocketConnect 参数..."
    jcmd "$TARGET_PID" JFR.start \
        name=privacy-check \
        settings=profile \
        duration="${DURATION}s" \
        filename="$JFR_FILE"
}

echo "[INFO] 录制已启动，等待 ${DURATION}s..."
sleep "$DURATION"

# ─── 步骤 2: 确认录制完成 ────────────────────────────────────────────────────
echo ""
echo "[步骤 2] 确认录制状态..."
jcmd "$TARGET_PID" JFR.check name=privacy-check 2>/dev/null || echo "[INFO] 录制已自动完成"

# ─── 步骤 3: 解析 Socket 事件并过滤外网流量 ──────────────────────────────────
echo ""
echo "[步骤 3] 解析 Socket 事件（过滤外网流量）..."
echo "------------------------------------------------------------"

if [[ ! -f "$JFR_FILE" ]]; then
    echo "[ERROR] JFR 文件未找到: $JFR_FILE"
    echo "  录制可能尚未完成，请稍等并手动运行："
    echo "  jfr print --events jdk.SocketWrite,jdk.SocketRead $JFR_FILE"
    exit 1
fi

# 提取所有 Socket 事件并过滤掉回环地址（127.x / ::1 / localhost）
echo "[Socket Write 事件（非回环外网流量）]"
jfr print --events jdk.SocketWrite "$JFR_FILE" 2>/dev/null \
    | grep -v "127\.\|::1\|localhost" \
    | head -40 \
    || echo "  (无非回环 SocketWrite 事件)"

echo ""
echo "[Socket Read 事件（非回环外网流量）]"
jfr print --events jdk.SocketRead "$JFR_FILE" 2>/dev/null \
    | grep -v "127\.\|::1\|localhost" \
    | head -40 \
    || echo "  (无非回环 SocketRead 事件)"

# ─── 步骤 4: 期望输出说明 ────────────────────────────────────────────────────
echo ""
echo "============================================================"
echo "[期望结果]"
echo "  LOCAL_ONLY 模式下："
echo "  • 无任何非回环 SocketWrite / SocketRead 事件 → JVM 层零出网 PASS"
echo "  • 有非回环事件 → 存在未被应用层网关覆盖的出网路径，需排查："
echo "    - JVM 遥测（JMX/gRPC 健康检查等）"
echo "    - Spring Actuator 远程推送"
echo "    - 数据库/Redis 连接（若非本机部署）"
echo "    - OS 级代理（需 OS 层工具进一步确认）"
echo "============================================================"
echo ""
echo "[OS 层补充验证]"
echo "  lsof -i TCP -n -P | grep $TARGET_PID    # 查看进程开放的 TCP 连接"
echo "  # macOS:"
echo "  sudo tcpdump -i any -n not host 127.0.0.1 and not host ::1 2>/dev/null | head -20"
echo "  # Linux:"
echo "  ss -tpn | grep $TARGET_PID"
echo ""
echo "[JFR 完整报告]"
echo "  jfr summary $JFR_FILE"
echo "  jfr print --events jdk.SocketWrite,jdk.SocketRead $JFR_FILE > /tmp/repolens-sockets.txt"
echo ""
echo "  JFR 文件已保存至: $JFR_FILE"
