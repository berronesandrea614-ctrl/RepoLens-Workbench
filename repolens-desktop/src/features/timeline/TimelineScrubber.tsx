import "./timeline.css";

export interface TimelineScrubberProps {
  frameCount: number;
  frameIndex: number;
  onFrameChange: (idx: number) => void;
  isPlaying: boolean;
  onPlayPause: () => void;
  onStepBack: () => void;
  onStepForward: () => void;
}

/**
 * Horizontal playback scrubber for the J-timeline feature.
 *
 * Layout: ◀  [=====slider=====]  ▶  ▶/⏸
 *         [tick tick tick ...]
 *
 * The range input handles arbitrary frame counts; the tick strip provides
 * per-frame click targets (up to 60 rendered directly; beyond that they scale).
 */
export function TimelineScrubber({
  frameCount,
  frameIndex,
  onFrameChange,
  isPlaying,
  onPlayPause,
  onStepBack,
  onStepForward,
}: TimelineScrubberProps) {
  const total = Math.max(frameCount, 1);

  return (
    <div className="timeline-scrubber">
      <div className="timeline-scrubber-controls">
        <button
          className="timeline-btn"
          onClick={onStepBack}
          disabled={frameIndex <= 0}
          title="上一帧 (◀)"
          aria-label="上一帧"
        >
          ◀
        </button>

        <input
          type="range"
          className="timeline-slider"
          min={0}
          max={total - 1}
          value={frameIndex}
          onChange={(e) => onFrameChange(Number(e.target.value))}
          title={`第 ${frameIndex + 1} 帧（共 ${frameCount} 帧）`}
          aria-label="帧选择"
        />

        <button
          className="timeline-btn"
          onClick={onStepForward}
          disabled={frameIndex >= frameCount - 1}
          title="下一帧 (▶)"
          aria-label="下一帧"
        >
          ▶
        </button>

        <button
          className="timeline-btn timeline-btn-play"
          onClick={onPlayPause}
          title={isPlaying ? "暂停 (⏸)" : "播放 (▶)"}
          aria-label={isPlaying ? "暂停" : "播放"}
        >
          {isPlaying ? "⏸" : "▶"}
        </button>
      </div>

      {/* Per-frame tick strip — one tick per frame, scrollable for large timelines */}
      <div className="timeline-ticks" role="listbox" aria-label="帧列表">
        {Array.from({ length: frameCount }, (_, i) => (
          <div
            key={i}
            role="option"
            aria-selected={i === frameIndex}
            className={`timeline-tick${i === frameIndex ? " active" : ""}`}
            onClick={() => onFrameChange(i)}
            title={`第 ${i + 1} 帧`}
          />
        ))}
      </div>
    </div>
  );
}
