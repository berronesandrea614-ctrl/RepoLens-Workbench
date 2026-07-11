import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Divider, Form, Input, InputNumber, Space, Tag, Typography, message } from 'antd';
import type { CreateRepoPayload, Repo } from '../types/repo';
import {
  buildChunks,
  buildVectors,
  createRepo,
  getRepo,
  importRepo,
  parseRepo,
  submitAsyncIndex
} from '../api/repoApi';
import JsonViewer from '../components/JsonViewer';

type RepoActionKey = 'import' | 'parse' | 'buildChunks' | 'buildVectors' | 'asyncIndex';

interface RepoAction {
  key: RepoActionKey;
  label: string;
  run: (repoId: number) => Promise<unknown>;
}

const repoActions: RepoAction[] = [
  { key: 'import', label: '同步导入', run: importRepo },
  { key: 'parse', label: '解析代码', run: parseRepo },
  { key: 'buildChunks', label: '构建 Chunk', run: buildChunks },
  { key: 'buildVectors', label: '向量化', run: buildVectors },
  { key: 'asyncIndex', label: '异步索引', run: submitAsyncIndex }
];

const repoStatusColor: Record<string, string> = {
  PENDING: 'default',
  INDEXING: 'processing',
  INDEXED: 'success',
  FAILED: 'error'
};

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function repoDescriptions(repo: Repo) {
  return [
    { key: 'id', label: 'id', children: repo.id },
    { key: 'repoName', label: 'repoName', children: repo.repoName },
    { key: 'repoUrl', label: 'repoUrl', children: repo.repoUrl },
    { key: 'branchName', label: 'branchName', children: repo.branchName },
    {
      key: 'latestCommitId',
      label: 'latestCommitId',
      children: repo.latestCommitId ? (
        <Typography.Text copyable={{ text: repo.latestCommitId }} ellipsis>
          {repo.latestCommitId}
        </Typography.Text>
      ) : (
        '-'
      )
    },
    {
      key: 'indexStatus',
      label: 'indexStatus',
      children: <Tag color={repoStatusColor[repo.indexStatus] || 'default'}>{repo.indexStatus || '-'}</Tag>
    },
    { key: 'createdAt', label: 'createdAt', children: repo.createdAt || '-' },
    { key: 'updatedAt', label: 'updatedAt', children: repo.updatedAt || '-' }
  ];
}

function RepoPage() {
  // 演示仓库创建、单阶段调试接口和 RocketMQ 异步索引入口。
  const [form] = Form.useForm<CreateRepoPayload>();
  const [repoId, setRepoId] = useState<number | null>(5);
  const [currentRepo, setCurrentRepo] = useState<Repo>();
  const [operationResult, setOperationResult] = useState<unknown>();
  const [error, setError] = useState<string>();
  const [queryLoading, setQueryLoading] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<RepoActionKey>();

  async function handleCreateRepo(payload: CreateRepoPayload) {
    setCreateLoading(true);
    setError(undefined);
    try {
      const result = await createRepo(payload);
      setCurrentRepo(result);
      setRepoId(result.id);
      setOperationResult(result);
      message.success('仓库创建成功');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setCreateLoading(false);
    }
  }

  async function handleQueryRepo() {
    if (!repoId) {
      message.error('请输入 repoId');
      return;
    }
    setQueryLoading(true);
    setError(undefined);
    try {
      const result = await getRepo(repoId);
      setCurrentRepo(result);
      setOperationResult(result);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setQueryLoading(false);
    }
  }

  async function handleRepoAction(action: RepoAction) {
    if (!repoId) {
      message.error('请输入 repoId');
      return;
    }
    setActionLoading(action.key);
    setError(undefined);
    try {
      const result = await action.run(repoId);
      setOperationResult({ action: action.label, result });
      message.success(`${action.label} 请求完成`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(undefined);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <Typography.Title level={3}>仓库管理</Typography.Title>
        <Typography.Paragraph className="page-description">
          演示仓库创建、JGit 导入、JavaParser 解析、Chunk 构建、向量写入和 RocketMQ 异步索引入口。
        </Typography.Paragraph>
      </div>

      {error && <Alert className="section-card" type="error" message="请求失败" description={error} showIcon />}

      <Card title="创建仓库" className="section-card">
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            workspaceId: 1,
            branchName: 'main',
            repoName: 'repolens-demo-service',
            repoUrl: 'file:///E:/shixi_xiangmu/RepoLens/test-repos/repolens-demo-service'
          }}
          onFinish={handleCreateRepo}
        >
          <div className="form-grid">
            <Form.Item label="workspaceId" name="workspaceId" rules={[{ required: true, message: '请输入 workspaceId' }]}>
              <InputNumber min={1} className="full-width" />
            </Form.Item>
            <Form.Item label="repoName" name="repoName" rules={[{ required: true, message: '请输入 repoName' }]}>
              <Input />
            </Form.Item>
            <Form.Item label="repoUrl" name="repoUrl" rules={[{ required: true, message: '请输入 repoUrl' }]}>
              <Input />
            </Form.Item>
            <Form.Item label="branchName" name="branchName">
              <Input />
            </Form.Item>
          </div>
          <Button type="primary" htmlType="submit" loading={createLoading}>
            创建仓库
          </Button>
        </Form>
      </Card>

      <Card title="仓库查询与单阶段操作" className="section-card">
        <Space wrap className="toolbar">
          <InputNumber min={1} value={repoId} onChange={(value) => setRepoId(value)} placeholder="repoId" />
          <Button type="primary" loading={queryLoading} onClick={handleQueryRepo}>
            查询仓库
          </Button>
        </Space>

        {currentRepo && (
          <>
            <Divider />
            <Descriptions bordered size="small" column={1} items={repoDescriptions(currentRepo)} />
          </>
        )}

        <Divider />
        <Space wrap>
          {repoActions.map((action) => (
            <Button key={action.key} loading={actionLoading === action.key} onClick={() => handleRepoAction(action)}>
              {action.label}
            </Button>
          ))}
        </Space>
      </Card>

      <Card title="接口返回 JSON" className="section-card">
        <JsonViewer value={operationResult} />
      </Card>

      <Alert
        type="info"
        showIcon
        message="同步接口用于调试单阶段链路，异步索引用于演示 RocketMQ 多阶段索引流水线。"
      />
    </div>
  );
}

export default RepoPage;
