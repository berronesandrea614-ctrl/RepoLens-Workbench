import { useState } from 'react';
import { Alert, Button, Card, InputNumber, Space, Table, Tooltip, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listRepoTasks } from '../api/repoApi';
import TaskStatusTag from '../components/TaskStatusTag';
import IndexPipeline from '../components/IndexPipeline';
import type { IndexTask } from '../types/repo';

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function TaskPage() {
  // 演示 index_task 状态流转、RocketMQ 异步索引、失败重试和幂等键。
  const [repoId, setRepoId] = useState<number | null>(5);
  const [tasks, setTasks] = useState<IndexTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const columns: ColumnsType<IndexTask> = [
    { title: 'id', dataIndex: 'id', width: 90 },
    { title: 'taskType', dataIndex: 'taskType', width: 150 },
    {
      title: 'status',
      dataIndex: 'status',
      width: 130,
      render: (status) => <TaskStatusTag status={status} />
    },
    { title: 'retryCount', dataIndex: 'retryCount', width: 120 },
    { title: 'maxRetry', dataIndex: 'maxRetry', width: 110 },
    {
      title: 'idempotentKey',
      dataIndex: 'idempotentKey',
      width: 260,
      render: (value?: string) =>
        value ? (
          <Tooltip title={value}>
            <Typography.Text copyable={{ text: value }} ellipsis className="ellipsis-text">
              {value}
            </Typography.Text>
          </Tooltip>
        ) : (
          '-'
        )
    },
    { title: 'errorMsg', dataIndex: 'errorMsg', width: 260, render: (value?: string) => value || '-' },
    { title: 'createdAt', dataIndex: 'createdAt', width: 180, render: (value?: string) => value || '-' },
    { title: 'updatedAt', dataIndex: 'updatedAt', width: 180, render: (value?: string) => value || '-' }
  ];

  async function handleQuery() {
    if (!repoId) {
      message.error('请输入 repoId');
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      setTasks(await listRepoTasks(repoId));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <Typography.Title level={3}>索引任务</Typography.Title>
        <Typography.Paragraph className="page-description">
          CLONE_REPO、PARSE_CODE、BUILD_CHUNK、VECTORIZE_CHUNK 分别对应仓库拉取、代码解析、Chunk 构建和向量写入。
        </Typography.Paragraph>
      </div>

      {error && <Alert className="section-card" type="error" message="查询失败" description={error} showIcon />}

      <Card className="section-card">
        <Space wrap className="toolbar">
          <InputNumber min={1} value={repoId} onChange={(value) => setRepoId(value)} placeholder="repoId" />
          <Button type="primary" loading={loading} onClick={handleQuery}>
            查询任务
          </Button>
        </Space>
      </Card>

      {tasks.length > 0 && (
        <Card title="索引流水线进度" className="section-card">
          <IndexPipeline tasks={tasks} />
        </Card>
      )}

      <Card title="index_task 列表">
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={tasks}
          pagination={{ pageSize: 10 }}
          scroll={{ x: 1480 }}
        />
      </Card>
    </div>
  );
}

export default TaskPage;
