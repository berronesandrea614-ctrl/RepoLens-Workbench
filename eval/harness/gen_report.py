#!/usr/bin/env python3
"""把 results.csv 汇总成 report.md：各配置成功率 + 谎报次数 + FULL vs 各消融的 delta。"""
import csv, collections, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
CSV = os.path.join(HERE, "results.csv")
OUT = os.path.join(HERE, "report.md")

CONFIG_DESC = {
    "FULL": "完整 harness（全部组件开）",
    "NO_ADVERSARIAL": "去掉「收尾前对抗性自检门」",
    "NO_VERIFY": "去掉验证（runVerification + 自检门）",
    "NO_TODO": "去掉 TodoWrite 任务清单",
    "NO_COMPACT": "去掉上下文压缩",
    "NO_BASH": "去掉 bash（无法运行程序验证）",
    "THIN_DESC": "工具描述砍成只剩名字",
}
ORDER = ["FULL", "NO_ADVERSARIAL", "NO_VERIFY", "NO_BASH", "NO_TODO", "NO_COMPACT", "THIN_DESC"]

rows = []
with open(CSV) as f:
    for r in csv.DictReader(f):
        rows.append(r)

by_cfg = collections.defaultdict(lambda: {"pass": 0, "total": 0, "fs": 0, "tasks": {}})
for r in rows:
    c = r["config"]
    ok = r["result"].startswith("ABLATION_PASS")
    by_cfg[c]["total"] += 1
    by_cfg[c]["pass"] += 1 if ok else 0
    by_cfg[c]["fs"] += int(r.get("false_success", "0") or 0)
    by_cfg[c]["tasks"][r["task"]] = ("✅" if ok else "❌")

tasks = sorted({r["task"] for r in rows})
configs = [c for c in ORDER if c in by_cfg] + [c for c in by_cfg if c not in ORDER]

full_rate = (by_cfg["FULL"]["pass"] / by_cfg["FULL"]["total"] * 100) if by_cfg.get("FULL", {}).get("total") else None

lines = []
lines.append("# 自研 AI Harness 消融评测报告\n")
lines.append("> 每个配置对同一组端到端编码任务跑真 DeepSeek（deepseek-chat，direct 模式，Auto 权限）。")
lines.append("> 成功 = 自动判分 Checker 断言全过（编译 + 运行 + 边界/精度正确）；谎报 = agent 自称完成但判分 FAIL。\n")

# 明细表
head = "| 配置 | " + " | ".join(tasks) + " | 成功率 | 谎报 |"
sep = "|---|" + "".join("---|" for _ in tasks) + "---|---|"
lines.append(head); lines.append(sep)
for c in configs:
    d = by_cfg[c]
    cells = " | ".join(d["tasks"].get(t, "–") for t in tasks)
    rate = d["pass"] / d["total"] * 100 if d["total"] else 0
    lines.append(f"| **{c}** | {cells} | {d['pass']}/{d['total']} ({rate:.0f}%) | {d['fs']} |")
lines.append("")

# 头条数字：FULL vs 各消融
lines.append("## 关键结论（简历数字）\n")
if full_rate is not None:
    lines.append(f"- **完整 harness 成功率：{by_cfg['FULL']['pass']}/{by_cfg['FULL']['total']}（{full_rate:.0f}%），约束违反（谎报）{by_cfg['FULL']['fs']} 次。**")
    for c in configs:
        if c == "FULL":
            continue
        d = by_cfg[c]
        rate = d["pass"] / d["total"] * 100 if d["total"] else 0
        delta = full_rate - rate
        lines.append(f"- {CONFIG_DESC.get(c, c)}：成功率 {rate:.0f}%（比完整版**低 {delta:.0f} 个百分点**），谎报 {d['fs']} 次。")
lines.append("")
lines.append("## 配置说明\n")
for c in configs:
    lines.append(f"- **{c}**：{CONFIG_DESC.get(c, c)}")

with open(OUT, "w") as f:
    f.write("\n".join(lines) + "\n")
print("report.md 已生成：", OUT)
print("\n".join(lines))
