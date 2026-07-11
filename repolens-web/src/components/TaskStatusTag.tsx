import { Tag } from 'antd';
import type { TagProps } from 'antd';
import type { TaskStatus } from '../types/repo';

const STATUS_COLOR_MAP: Record<TaskStatus, TagProps['color']> = {
  // 状态颜色和后端 index_task 状态机保持一致，便于演示异步索引流转。
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'success',
  WAIT_RETRY: 'warning',
  FAILED: 'error'
};

interface TaskStatusTagProps {
  status?: TaskStatus;
}

function TaskStatusTag({ status }: TaskStatusTagProps) {
  if (!status) {
    return <Tag>UNKNOWN</Tag>;
  }
  return <Tag color={STATUS_COLOR_MAP[status] || 'default'}>{status}</Tag>;
}

export default TaskStatusTag;
