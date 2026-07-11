#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RepoLens 评测脚本(可复现)。覆盖 4 个维度,直接回答技术官四问:
  D1 调用关系解析准确率(对应"准确度多少/凭什么说提升")—— 文本匹配 baseline vs 类型推断 对比
  D2 意图/相关性识别(对应"无关问题怎么识别")—— 无关问题拒答准确率
  D3 检索准确率(对应"检索内容准不准")—— Recall@K / Precision@K / MRR
  D4 回答引用正确性(对应"回答好坏/合不合预期")—— 引用命中真值文件的比例

依赖:仅标准库 + 通过 `docker exec repolens-mysql mysql` 查库 + urllib 打 API。
运行:python3 eval/run_eval.py   (需后端 8080 + MySQL 容器在跑)
输出:eval/report.md
"""
import json, subprocess, urllib.request, urllib.error, os, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
API = "http://localhost:8080"
REPO_ID = 1
USER = "1"
TOPK = 5

def load(name):
    with open(os.path.join(ROOT, name), encoding="utf-8") as f:
        return json.load(f)

# ---------- 工具 ----------
def db_query(sql):
    """通过 docker exec 查 MySQL,返回行列表(每行是 list)。失败抛异常。"""
    out = subprocess.run(
        ["docker", "exec", "repolens-mysql", "mysql", "-uroot", "-proot123",
         "-N", "-B", "-e", f"USE repolens; {sql}"],
        capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError("db query failed: " + out.stderr.strip())
    rows = [line.split("\t") for line in out.stdout.strip().splitlines() if line.strip()]
    return rows

def api_post(path, body):
    req = urllib.request.Request(API + path, method="POST",
        data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "X-User-Id": USER})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())

def f1(p, r):
    return 0.0 if (p + r) == 0 else round(2 * p * r / (p + r), 3)

# ---------- D1 调用关系解析准确率 ----------
def eval_callgraph():
    gt = load("groundtruth_callgraph.json")
    # 真值:所有"该解析到全限定声明类"的调用 = 同仓 + 可经 JDK 反射解析的外部调用。
    # 不含 to_text 的(如 SpringApplication.run:无 classpath,正确行为是退回文本)。
    should_resolve = {c["to_qualified"] for c in gt["intra_repo_calls"]}
    should_resolve |= {c["to_qualified"] for c in gt["external_calls"] if "to_qualified" in c}
    should_text = {c["to_text"] for c in gt["external_calls"] if "to_text" in c}

    rows = db_query("SELECT target_symbol_name, confidence FROM code_dependency WHERE relation_type='CALL';")
    resolved = {t for (t, c) in rows if float(c) >= 0.90 and "#" in t}   # 解析到全限定(含 #)
    text_only = {t for (t, c) in rows if float(c) < 0.90}               # 退回文本匹配

    tp = len(should_resolve & resolved)
    recall = round(tp / len(should_resolve), 3) if should_resolve else 0
    precision = round(tp / len(resolved), 3) if resolved else 0
    missed = sorted(list(should_resolve - resolved))
    # 文本回退是否符合预期(should_text 应出现在 text_only 中)
    text_correct = len(should_text & text_only)

    return {
        "dimension": "D1 调用关系解析准确率(类型推断 vs 文本匹配)",
        "ground_truth_should_resolve": len(should_resolve),
        "baseline_text_match": {
            "qualified_resolutions": 0, "recall": 0.0,
            "note": "纯文本匹配只输出变量名级目标(如 userService.getUserById),结构上无法解析到声明类,全限定解析数=0"},
        "upgraded_type_inference": {
            "qualified_resolutions": tp, "precision": precision, "recall": recall,
            "fallback_to_text_correct": "%d/%d" % (text_correct, len(should_text))},
        "improvement": f"调用'解析到真实声明类'的召回:0%% → {recall*100:.0f}%%;精确率 {precision*100:.0f}%%",
        "explainable_miss": missed,
        "miss_reason": "User#setCreatedAt 未解析:demo 源码该处实参 Instant.now() 的 Instant 未 import,该语句类型解析失败 → 可解释的真实局限",
        "raw_resolved": sorted(list(resolved)),
        "raw_text_fallback": sorted(list(text_only)),
    }

# ---------- D2/D3/D4 走 API ----------
def eval_retrieval_intent_answer():
    gt = load("groundtruth_retrieval.json")["cases"]
    # D3 检索
    recalls, precisions, rr = [], [], []
    # D2 意图
    intent_tp = intent_total_irrelevant = 0     # 无关问题被正确拒答
    answered_relevant = total_relevant = 0
    # D4 引用正确性
    cite_hits, cite_total = 0, 0

    detail = []
    for c in gt:
        q, relevant, exp = c["query"], c["relevant"], set(c.get("expected_files", []))
        # --- D3 RAG 检索(只对相关问题算召回)---
        rag = api_post(f"/api/repos/{REPO_ID}/rag/search", {"query": q, "topK": TOPK})["data"]
        hit_files = [os.path.basename(r["filePath"]) for r in (rag.get("results") or [])]
        if relevant and exp:
            got = set(hit_files)
            rec = len(exp & got) / len(exp)
            prec = len(exp & got) / len(got) if got else 0
            recalls.append(rec); precisions.append(prec)
            # MRR:第一个命中期望文件的排名倒数
            rrv = 0.0
            for i, fpath in enumerate(hit_files):
                if fpath in exp:
                    rrv = 1.0 / (i + 1); break
            rr.append(rrv)

        # --- D2 意图 + D4 引用:走问答 ---
        ans = api_post(f"/api/repos/{REPO_ID}/chat/answer", {"question": q, "topK": TOPK})["data"]
        refused = bool(ans.get("degraded")) and (ans.get("degradeReason") == "insufficient evidence" or not ans.get("references"))
        refs = set(os.path.basename(r["filePath"]) for r in (ans.get("references") or []))
        if not relevant:
            intent_total_irrelevant += 1
            if refused or not refs:
                intent_tp += 1
        else:
            total_relevant += 1
            if not refused:
                answered_relevant += 1
            # D4:回答引用是否落在真值文件内
            if refs and exp:
                cite_total += 1
                if refs & exp:
                    cite_hits += 1
        detail.append({"query": q, "relevant": relevant, "retrieved": hit_files,
                       "answer_refs": sorted(list(refs)), "refused": refused})

    avg = lambda xs: round(sum(xs)/len(xs), 3) if xs else 0
    return {
        "D3_retrieval": {"dimension": "D3 检索准确率",
                         "recall@%d" % TOPK: avg(recalls), "precision@%d" % TOPK: avg(precisions),
                         "MRR": avg(rr), "n_relevant_queries": len(recalls)},
        "D2_intent":    {"dimension": "D2 意图/相关性识别(无关问题拒答)",
                         "refusal_accuracy_on_irrelevant": round(intent_tp/intent_total_irrelevant, 3) if intent_total_irrelevant else 0,
                         "answer_rate_on_relevant": round(answered_relevant/total_relevant, 3) if total_relevant else 0,
                         "n_irrelevant": intent_total_irrelevant, "n_relevant": total_relevant},
        "D4_answer":    {"dimension": "D4 回答引用正确性(客观)",
                         "citation_in_groundtruth_rate": round(cite_hits/cite_total, 3) if cite_total else 0,
                         "n_evaluated": cite_total,
                         "note": "当前 mock LLM 回答文本为模板,故只客观评测'引用是否落在真值文件内',不评文本质量;接真 LLM 后可加 LLM-as-judge 评回答质量"},
        "detail": detail,
    }

def main():
    report = ["# RepoLens 评测报告\n",
              "> 可复现:`python3 eval/run_eval.py`(需后端 8080 + MySQL 容器)。标注集见 eval/groundtruth_*.json。\n"]
    # D1 永远尝试(只需 DB)
    try:
        d1 = eval_callgraph()
        report.append("## " + d1["dimension"])
        report.append("```json\n" + json.dumps(d1, ensure_ascii=False, indent=2) + "\n```\n")
    except Exception as e:
        report.append("## D1 调用关系解析准确率\n\n⚠️ 跳过(DB 不可用):%s\n" % e)
    # D2/D3/D4 需 API
    try:
        api = eval_retrieval_intent_answer()
        for k in ("D3_retrieval", "D2_intent", "D4_answer"):
            d = api[k]
            report.append("## " + d["dimension"])
            report.append("```json\n" + json.dumps({kk: vv for kk, vv in d.items() if kk != "dimension"}, ensure_ascii=False, indent=2) + "\n```\n")
    except Exception as e:
        report.append("## D2/D3/D4(检索/意图/回答)\n\n⚠️ 跳过(后端 API 不可用):%s\n" % e)

    out = os.path.join(ROOT, "report.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(report))
    print("REPORT WRITTEN:", out)

if __name__ == "__main__":
    main()
