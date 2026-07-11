import { useCallback, useEffect, useRef, useState } from "react";
import { useWorkbench } from "../../state/workbenchStore";
import { fetchTimeline, fetchFrameGraph, Timeline, Frame } from "../../api/timelineApi";
import { CodeGraph } from "../../types/graph";
import { GraphCanvas } from "../graph/GraphCanvas";
import { TimelineScrubber } from "./TimelineScrubber";
import "./timeline.css";

/**
 * Feature J: 时间轴回放视图。
 *
 * 按会话（agentRun）为单位逐帧展示代码图演化过程。
 * 每帧图按节点 changeType（NEW/MODIFIED/STABLE）染色以直观呈现变更热区。
 *
 * 动画说明（P1 简化版）：每帧切换均重新调用 fetchFrameGraph + dagre 布局。
 * 若切帧明显卡顿，P2 可引入末帧坐标缓存（visible/opacity 切换）优化。
 */
export function TimelineView() {
  const repoId = useWorkbench((s) => s.repoId);
  const openFile = useWorkbench((s) => s.openFile);

  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineError, setTimelineError] = useState<string | null>(null);

  const [frameIndex, setFrameIndex] = useState(0);
  const [frameGraph, setFrameGraph] = useState<CodeGraph | null>(null);
  const [frameLoading, setFrameLoading] = useState(false);
  const [visible, setVisible] = useState<Set<string>>(new Set());

  const [isPlaying, setIsPlaying] = useState(false);
  const playTimer = useRef<number | null>(null);

  // ── Fetch timeline on mount / repoId change ──────────────────────────────
  useEffect(() => {
    if (repoId == null) return;
    setTimelineLoading(true);
    setTimelineError(null);
    setTimeline(null);
    setFrameGraph(null);
    setFrameIndex(0);
    setIsPlaying(false);
    fetchTimeline(repoId)
      .then((t) => {
        setTimeline(t);
        setFrameIndex(0);
      })
      .catch((e: unknown) => setTimelineError(String(e)))
      .finally(() => setTimelineLoading(false));
  }, [repoId]);

  // ── Fetch frame graph when frameIndex / timeline changes ─────────────────
  useEffect(() => {
    if (repoId == null || timeline == null || timeline.frameCount < 1) return;
    // Guard: clamp index to valid range (defensive)
    const safeIdx = Math.min(Math.max(frameIndex, 0), timeline.frameCount - 1);
    setFrameLoading(true);
    fetchFrameGraph(repoId, safeIdx)
      .then((g) => {
        setFrameGraph(g);
        setVisible(new Set(g.nodes.map((n) => n.id)));
      })
      .catch((e: unknown) => {
        console.error("[TimelineView] fetchFrameGraph error:", e);
      })
      .finally(() => setFrameLoading(false));
  }, [repoId, frameIndex, timeline]);

  // ── Play/pause interval ──────────────────────────────────────────────────
  useEffect(() => {
    // Clear any existing interval first.
    if (playTimer.current != null) {
      window.clearInterval(playTimer.current);
      playTimer.current = null;
    }
    if (!isPlaying) return;

    playTimer.current = window.setInterval(() => {
      setFrameIndex((idx) => {
        const total = timeline?.frameCount ?? 0;
        if (total <= 0 || idx >= total - 1) {
          // Reached the last frame — stop playback.
          setIsPlaying(false);
          return idx;
        }
        return idx + 1;
      });
    }, 800);

    return () => {
      if (playTimer.current != null) {
        window.clearInterval(playTimer.current);
        playTimer.current = null;
      }
    };
  }, [isPlaying, timeline]);

  // ── Handlers ─────────────────────────────────────────────────────────────
  const handlePlayPause = useCallback(() => {
    setIsPlaying((playing) => {
      if (!playing && timeline && frameIndex >= timeline.frameCount - 1) {
        // If at the last frame, restart from the beginning.
        setFrameIndex(0);
      }
      return !playing;
    });
  }, [timeline, frameIndex]);

  const handleStepBack = useCallback(() => {
    setIsPlaying(false);
    setFrameIndex((idx) => Math.max(idx - 1, 0));
  }, []);

  const handleStepForward = useCallback(() => {
    setIsPlaying(false);
    setFrameIndex((idx) => Math.min(idx + 1, (timeline?.frameCount ?? 1) - 1));
  }, [timeline]);

  const handleOpenFile = useCallback(
    (filePath: string, line: number) => openFile(filePath, line),
    [openFile],
  );

  // ── Render states ─────────────────────────────────────────────────────────
  if (repoId == null) {
    return (
      <div className="timeline-view timeline-state-center">
        请先打开一个仓库
      </div>
    );
  }

  if (timelineLoading) {
    return (
      <div className="timeline-view timeline-state-center">
        <span className="codicon codicon-loading codicon-modifier-spin" style={{ marginRight: 8 }} />
        加载时间轴中…
      </div>
    );
  }

  if (timelineError) {
    return (
      <div className="timeline-view timeline-state-center timeline-error">
        加载失败：{timelineError}
      </div>
    );
  }

  if (timeline == null) return null;

  if (timeline.frameCount < 1) {
    return (
      <div className="timeline-view timeline-state-center">
        <span className="codicon codicon-history" style={{ fontSize: 36, marginBottom: 12 }} />
        <div>暂无会话，无法回放</div>
        <div className="timeline-hint-text">请先使用 AI Agent 对仓库进行分析，产生至少一个会话帧</div>
      </div>
    );
  }

  const currentFrame: Frame | undefined = timeline.frames[frameIndex];

  return (
    <div className="timeline-view">
      {/* ── Header bar ──────────────────────────────────────────────────── */}
      <div className="timeline-header">
        <span className="timeline-title">
          ⏱ 从第一个会话起统计（{timeline.frameCount} 帧）
        </span>
        {timeline.historyLimited && (
          <span className="timeline-badge timeline-badge-warn">
            符号级历史无法回补（历史有限制）
          </span>
        )}
        {timeline.frameCount < 3 && (
          <span className="timeline-badge">会话较少，回放效果有限</span>
        )}
      </div>

      {/* ── Current frame info ──────────────────────────────────────────── */}
      <div className="timeline-frame-info">
        <span className="timeline-frame-label">
          第 {frameIndex + 1}&nbsp;/&nbsp;{timeline.frameCount} 帧
        </span>
        {currentFrame && (
          <span className="timeline-frame-detail">
            · 该帧改 {currentFrame.changedFileCount} 文件
            {currentFrame.touchedSymbolCount > 0 &&
              `，触碰 ${currentFrame.touchedSymbolCount} 符号`}
          </span>
        )}
        {frameLoading && (
          <span className="timeline-frame-loading">
            <span className="codicon codicon-loading codicon-modifier-spin" />
            &nbsp;加载中…
          </span>
        )}
      </div>

      {/* ── Scrubber ────────────────────────────────────────────────────── */}
      <TimelineScrubber
        frameCount={timeline.frameCount}
        frameIndex={frameIndex}
        onFrameChange={setFrameIndex}
        isPlaying={isPlaying}
        onPlayPause={handlePlayPause}
        onStepBack={handleStepBack}
        onStepForward={handleStepForward}
      />

      {/* ── Legend ──────────────────────────────────────────────────────── */}
      <div className="timeline-legend">
        <span className="timeline-legend-item" style={{ color: "#4daafc" }}>● NEW（首次出现）</span>
        <span className="timeline-legend-item" style={{ color: "#f39c12" }}>● MODIFIED（有变动）</span>
        <span className="timeline-legend-item" style={{ color: "#6e7681" }}>● STABLE（无变动）</span>
      </div>

      {/* ── Graph canvas ────────────────────────────────────────────────── */}
      <div className="timeline-canvas">
        {frameGraph && frameGraph.nodes.length > 0 ? (
          <GraphCanvas
            graph={frameGraph}
            openFile={handleOpenFile}
            visible={visible}
            onSetVisible={setVisible}
            frameColorMode={true}
          />
        ) : frameGraph && frameGraph.nodes.length === 0 ? (
          <div className="timeline-state-center" style={{ flex: 1, flexDirection: "column", gap: 6, textAlign: "center", padding: 24 }}>
            <span className="codicon codicon-circle-slash" style={{ fontSize: 30, marginBottom: 8 }} />
            <div>该帧无符号级变更可展示</div>
            <div className="timeline-hint-text" style={{ maxWidth: 460, lineHeight: 1.7 }}>
              时间轴按 <b>Java 符号</b>的出现/变动回放。本帧虽改动了文件，但没有可解析的 Java 符号——
              常见于<b>非 Java 项目</b>，或符号级历史无法回补（见上方标记）。切换到已索引的 Java 仓库可看到完整回放。
            </div>
          </div>
        ) : (
          !frameLoading && (
            <div className="timeline-state-center" style={{ flex: 1 }}>
              选择一帧查看图
            </div>
          )
        )}
      </div>
    </div>
  );
}
