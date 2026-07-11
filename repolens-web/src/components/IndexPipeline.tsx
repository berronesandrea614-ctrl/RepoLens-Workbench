import { Steps } from 'antd';
import type { IndexTask, TaskStatus, TaskType } from '../types/repo';

interface IndexPipelineProps {
  tasks: IndexTask[];
}

const STAGES: { type: TaskType; title: string }[] = [
  { type: 'CLONE_REPO', title: '仓库拉取' },
  { type: 'PARSE_CODE', title: '代码解析' },
  { type: 'BUILD_CHUNK', title: 'Chunk 构建' },
  { type: 'VECTORIZE_CHUNK', title: '向量写入' }
];

type StepStatus = 'wait' | 'process' | 'finish' | 'error';

function mapStatus(status?: TaskStatus): StepStatus {
  switch (status) {
    case 'SUCCESS':
      return 'finish';
    case 'RUNNING':
      return 'process';
    case 'FAILED':
      return 'error';
    case 'WAIT_RETRY':
      return 'process';
    default:
      return 'wait';
  }
}

/**
 * 索引流水线进度可视化：把 4 个阶段任务用 Steps 串成一条进度，
 * 让“仓库现在索引到哪一步、哪步失败”一目了然，替代只看任务表格。
 */
function IndexPipeline({ tasks }: IndexPipelineProps) {
  const byType = new Map<TaskType, IndexTask>();
  for (const t of tasks) {
    // 同类型保留最新（updatedAt 最大）的任务
    const prev = byType.get(t.taskType);
    if (!prev || (t.updatedAt || '') >= (prev.updatedAt || '')) {
      byType.set(t.taskType, t);
    }
  }

  // 当前进行到的阶段索引：第一个非 finish 的阶段
  let current = STAGES.findIndex((s) => mapStatus(byType.get(s.type)?.status) !== 'finish');
  if (current < 0) current = STAGES.length - 1;

  const items = STAGES.map((stage) => {
    const task = byType.get(stage.type);
    const st = mapStatus(task?.status);
    const desc = task
      ? `${task.status}${task.retryCount ? ` · 重试${task.retryCount}` : ''}`
      : '未开始';
    return { title: stage.title, description: desc, status: st };
  });

  return <Steps size="small" current={current} items={items} className="index-pipeline" />;
}

export default IndexPipeline;
