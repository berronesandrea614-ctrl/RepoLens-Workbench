import React, { useState } from "react";
import { InsightStep, FlowNode } from "./insightTypes";
import { getStepCls } from "./insightUtils";
import { MiniFlow } from "./MiniFlow";

interface PlanStepProps {
  step: InsightStep;
  selected: boolean;
  onSelect: () => void;
  onNodeDiff?: (node: FlowNode) => void;
  onNodeOpenFile?: (node: FlowNode, line?: number) => void;
  onEvidenceClick?: (filePath: string) => void;
}

export function PlanStep({
  step,
  selected,
  onSelect,
  onNodeDiff,
  onNodeOpenFile,
  onEvidenceClick,
}: PlanStepProps) {
  const [provOpen, setProvOpen] = useState(false);

  const cls = [
    "ins-step",
    getStepCls(step.kind),
    selected ? "sel" : "",
  ]
    .filter(Boolean)
    .join(" ");

  const isOff = step.kind === "off";
  const isRisk = step.kind === "risk";
  const isWarnInsight = isOff || isRisk;

  function handleStepClick() {
    onSelect();
  }

  function handleProvToggle(e: React.MouseEvent) {
    e.stopPropagation();
    setProvOpen((v) => !v);
  }

  function handleEvidenceClick(e: React.MouseEvent, path: string) {
    e.stopPropagation();
    onEvidenceClick?.(path);
  }

  return (
    <div className={cls} onClick={handleStepClick}>
      <div className="ins-stepnum">
        <span className="ins-dot" />
      </div>

      {/* Left: reasoning */}
      <div className="ins-left">
        <h4>
          {step.title}
          {isOff ? (
            <span className="ins-plabel offplan">计划外</span>
          ) : (
            <span className="ins-plabel inplan">计划内</span>
          )}
        </h4>

        {step.why && (
          <div className={`ins-why${isOff ? " offwhy" : ""}`}>
            <b>{isOff ? "AI 说明：" : "为什么："}</b>
            {step.why}
          </div>
        )}

        {isRisk && step.riskNote && (
          <div className="ins-riskflag">⚠ {step.riskNote}</div>
        )}
        {isOff && step.riskNote && (
          <div className="ins-offflag">▸ {step.riskNote}</div>
        )}

        {step.toolReads && step.toolReads.length > 0 && (
          <>
            <button className="ins-provtoggle" onClick={handleProvToggle}>
              👁 决策依据 {provOpen ? "▴" : "▾"}
            </button>
            {provOpen && (
              <div className="ins-prov open">
                <b>AI 做这步前读了：</b>
                <br />
                {step.toolReads.map((p, i) => (
                  <span
                    key={i}
                    className="ins-ev"
                    onClick={(e) => handleEvidenceClick(e, p)}
                    title={p}
                  >
                    {p.split("/").pop() ?? p}
                  </span>
                ))}
              </div>
            )}
          </>
        )}
      </div>

      {/* Right: miniflow + insight */}
      <div className="ins-right">
        <MiniFlow
          flow={step.flow}
          onNodeDiff={onNodeDiff}
          onNodeOpenFile={onNodeOpenFile ? (n) => onNodeOpenFile(n, n.startLine) : undefined}
        />
        {step.insight && (
          <div className={`ins-insight${isWarnInsight ? " warn" : ""}`}>
            <span className="ins-ib">{isWarnInsight ? "⚠ AI 洞察" : "💡 AI 洞察"}</span>
            <span>{step.insight}</span>
          </div>
        )}
      </div>
    </div>
  );
}
