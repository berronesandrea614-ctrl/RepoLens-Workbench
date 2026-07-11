import { useState } from 'react';
import { Alert, Button, Card, Empty, Input, InputNumber, List, Space, Tag, Typography, message } from 'antd';
import { searchRag } from '../api/ragApi';
import CodePreview from '../components/CodePreview';
import type { RagSearchResult } from '../types/rag';

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function formatScore(score?: number) {
  return typeof score === 'number' ? score.toFixed(4) : '-';
}

function RagSearchPage() {
  // 演示 RAG 检索证据，不经过 LLM，方便直接排查召回质量。
  const [repoId, setRepoId] = useState<number | null>(5);
  const [query, setQuery] = useState('create user api');
  const [topK, setTopK] = useState<number | null>(5);
  const [result, setResult] = useState<RagSearchResult>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  async function handleSearch() {
    if (!repoId) {
      message.error('请输入 repoId');
      return;
    }
    if (!query.trim()) {
      message.error('请输入 query');
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      setResult(await searchRag(repoId, { query: query.trim(), topK: topK || 5 }));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <Typography.Title level={3}>RAG 检索</Typography.Title>
        <Typography.Paragraph className="page-description">
          RAG 检索页用于观察 Milvus 向量召回和 MySQL 回查结果，便于排查回答是否有足够代码证据。
        </Typography.Paragraph>
      </div>

      {error && <Alert className="section-card" type="error" message="检索失败" description={error} showIcon />}

      <Card className="section-card">
        <Space wrap className="toolbar">
          <InputNumber min={1} value={repoId} onChange={(value) => setRepoId(value)} addonBefore="repoId" />
          <Input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="query" className="wide-input" />
          <InputNumber min={1} max={20} value={topK} onChange={(value) => setTopK(value)} addonBefore="topK" />
          <Button type="primary" loading={loading} onClick={handleSearch}>
            检索
          </Button>
        </Space>
      </Card>

      {result?.degraded && (
        <Alert
          className="section-card"
          type="warning"
          showIcon
          message="当前使用 MySQL 关键词降级检索，语义召回能力下降。"
          description={result.degradeReason || '-'}
        />
      )}

      <Card
        title="检索结果"
        extra={
          result && (
            <Space wrap>
              <Tag color="blue">hitCount {result.hitCount}</Tag>
              <Tag>degraded {String(Boolean(result.degraded))}</Tag>
            </Space>
          )
        }
      >
        {!result && <Empty description="执行检索后展示召回证据" />}
        {result && result.results.length === 0 && <Empty description="未检索到证据" />}
        <List
          dataSource={result?.results || []}
          renderItem={(item) => (
            <List.Item>
              <Card size="small" className="full-width">
                <Space direction="vertical" size={8} className="full-width">
                  <Typography.Text strong copyable={{ text: item.filePath }}>
                    {item.filePath}
                  </Typography.Text>
                  <Space size={6} wrap>
                    <Tag>{item.chunkType}</Tag>
                    <Tag>{item.language}</Tag>
                    <Tag color="blue">
                      lines {item.startLine} - {item.endLine}
                    </Tag>
                    <Tag color="green">score {formatScore(item.score)}</Tag>
                    <Typography.Text copyable={{ text: item.chunkId }} type="secondary">
                      {item.chunkId}
                    </Typography.Text>
                  </Space>
                  <CodePreview
                    code={item.contentPreview || item.content || '无内容'}
                    filePath={item.filePath}
                    startLine={item.startLine}
                    maxHeight={220}
                  />
                </Space>
              </Card>
            </List.Item>
          )}
        />
      </Card>
    </div>
  );
}

export default RagSearchPage;
