import { useState } from 'react';
import { Alert, Badge, Button, Card, Col, Empty, Input, InputNumber, Row, Space, Tabs, Tag, Typography, message } from 'antd';
import { answerCodeQuestion } from '../api/chatApi';
import ReferenceCard from '../components/ReferenceCard';
import AgentTrace from '../components/AgentTrace';
import type { CodeAnswer } from '../types/chat';

const QUICK_QUESTIONS = ['创建用户接口在哪里？', '用户查询的调用链是怎样的？', 'UserService 有哪些方法？'];

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function ChatPage() {
  const [repoId, setRepoId] = useState<number | null>(5);
  const [question, setQuestion] = useState(QUICK_QUESTIONS[0]);
  const [topK, setTopK] = useState<number | null>(5);
  const [answer, setAnswer] = useState<CodeAnswer>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  async function handleAsk() {
    if (!repoId) {
      message.error('请输入 repoId');
      return;
    }
    if (!question.trim()) {
      message.error('请输入问题');
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      setAnswer(await answerCodeQuestion(repoId, { question: question.trim(), topK: topK || 5 }));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  const references = answer?.references || [];
  const agentSteps = answer?.agentSteps || [];
  const isAgent = Boolean(answer?.agentMode);

  return (
    <div className="page">
      <div className="page-header">
        <Typography.Title level={3}>代码问答</Typography.Title>
        <Typography.Paragraph className="page-description">
          基于 RAG 证据 + 多步工具检索的可追溯问答。开启 agent 模式后，可查看完整的“思考 → 工具 → 观察”推理轨迹。
        </Typography.Paragraph>
      </div>

      {error && <Alert className="section-card" type="error" message="提问失败" description={error} showIcon />}

      <Row gutter={16} align="stretch">
        <Col span={14}>
          <Card title="问题与回答" className="full-height" variant="borderless">
            <Space direction="vertical" size={14} className="full-width">
              <Space wrap>
                <InputNumber min={1} value={repoId} onChange={(value) => setRepoId(value)} addonBefore="repoId" />
                <InputNumber min={1} max={20} value={topK} onChange={(value) => setTopK(value)} addonBefore="topK" />
              </Space>
              <Space wrap size={6}>
                {QUICK_QUESTIONS.map((q) => (
                  <Button key={q} size="small" onClick={() => setQuestion(q)}>
                    {q}
                  </Button>
                ))}
              </Space>
              <Input.TextArea
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                autoSize={{ minRows: 3, maxRows: 8 }}
                placeholder="输入与当前仓库代码相关的问题"
              />
              <Button type="primary" size="large" loading={loading} onClick={handleAsk} block>
                提问
              </Button>

              {answer?.degraded && (
                <Alert type="warning" showIcon message="当前回答为降级结果" description={answer.degradeReason || '-'} />
              )}

              {answer && (
                <div className="answer-box">
                  <div className="answer-box-head">
                    <Typography.Text type="secondary">回答</Typography.Text>
                    {isAgent && <Tag color="purple">Agent 多步检索</Tag>}
                  </div>
                  <Typography.Paragraph className="answer-content">{answer.answer || '暂无回答'}</Typography.Paragraph>
                </div>
              )}

              {answer && (
                <Space wrap size={6}>
                  {answer.modelName && <Tag color="blue">model {answer.modelName}</Tag>}
                  {typeof answer.costMs === 'number' && <Tag>cost {answer.costMs} ms</Tag>}
                  {typeof answer.promptTokens === 'number' && <Tag>prompt {answer.promptTokens}</Tag>}
                  {typeof answer.completionTokens === 'number' && <Tag>completion {answer.completionTokens}</Tag>}
                </Space>
              )}
            </Space>
          </Card>
        </Col>

        <Col span={10}>
          <Card className="full-height" variant="borderless">
            {!answer ? (
              <Empty description="提问后展示引用证据与 Agent 轨迹" />
            ) : (
              <Tabs
                defaultActiveKey={isAgent ? 'trace' : 'refs'}
                items={[
                  {
                    key: 'refs',
                    label: (
                      <Badge count={references.length} size="small" offset={[8, -2]} color="#2563eb">
                        引用证据
                      </Badge>
                    ),
                    children:
                      references.length === 0 ? (
                        <Alert type="info" showIcon message="本次回答未返回引用。" />
                      ) : (
                        <div className="reference-list">
                          {references.map((reference, index) => (
                            <ReferenceCard
                              key={`${reference.filePath}-${reference.startLine}-${index}`}
                              reference={reference}
                              index={index}
                            />
                          ))}
                        </div>
                      )
                  },
                  {
                    key: 'trace',
                    label: (
                      <Badge count={agentSteps.length} size="small" offset={[8, -2]} color="#7c3aed">
                        Agent 轨迹
                      </Badge>
                    ),
                    children: (
                      <AgentTrace
                        steps={agentSteps}
                        iterations={answer.agentIterations}
                        toolCalls={answer.agentToolCalls}
                      />
                    )
                  }
                ]}
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}

export default ChatPage;
