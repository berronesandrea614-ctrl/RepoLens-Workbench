import { Empty, Tag, Timeline, Typography } from 'antd';
import type { AgentStep } from '../types/chat';

interface AgentTraceProps {
  steps?: AgentStep[];
  iterations?: number;
  toolCalls?: number;
}

function prettyArgs(raw?: string): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw));
  } catch {
    return raw;
  }
}

/**
 * Agent 执行轨迹可视化：把“思考 → 调工具 → 观察”逐步用时间线展示。
 * 这是 RepoLens 区别于普通问答的核心——让用户看到 agent 的多步推理过程，而非黑盒。
 */
function AgentTrace({ steps, iterations, toolCalls }: AgentTraceProps) {
  if (!steps || steps.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本次回答未触发多步工具检索" />;
  }

  return (
    <div className="agent-trace">
      <div className="agent-trace-stats">
        <Tag color="purple">迭代 {iterations ?? steps.length} 轮</Tag>
        <Tag color="geekblue">工具调用 {toolCalls ?? steps.length} 次</Tag>
      </div>
      <Timeline
        items={steps.map((step) => ({
          color: 'blue',
          children: (
            <div className="agent-step">
              <div className="agent-step-head">
                <span className="agent-step-index">步骤 {step.stepIndex}</span>
                <Tag color="blue" className="agent-tool-tag">
                  {step.toolName}
                </Tag>
                {typeof step.discoveredCount === 'number' && step.discoveredCount > 0 && (
                  <Tag color="green">+{step.discoveredCount} 证据</Tag>
                )}
              </div>
              {step.thought && (
                <div className="agent-step-block">
                  <span className="agent-step-label">思考</span>
                  <Typography.Paragraph className="agent-step-text" ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}>
                    {step.thought}
                  </Typography.Paragraph>
                </div>
              )}
              {step.toolArgs && (
                <div className="agent-step-block">
                  <span className="agent-step-label">参数</span>
                  <code className="agent-step-args">{prettyArgs(step.toolArgs)}</code>
                </div>
              )}
              {step.observation && (
                <div className="agent-step-block">
                  <span className="agent-step-label">观察</span>
                  <Typography.Paragraph className="agent-step-obs" ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}>
                    {step.observation}
                  </Typography.Paragraph>
                </div>
              )}
            </div>
          )
        }))}
      />
    </div>
  );
}

export default AgentTrace;
