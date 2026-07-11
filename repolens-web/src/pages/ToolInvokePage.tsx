import { useState } from 'react';
import { Alert, Button, Card, Col, Input, InputNumber, Row, Select, Space, Typography, message } from 'antd';
import JsonViewer from '../components/JsonViewer';
import { invokeTool } from '../api/toolApi';
import type { ToolName } from '../types/tool';

const TOOL_TEMPLATES: Record<ToolName, Record<string, unknown>> = {
  searchCodeChunks: {
    query: 'create user api',
    topK: 5
  },
  getFileContent: {
    filePath: 'src/main/java/com/example/demo/controller/UserController.java',
    startLine: 1,
    endLine: 80
  },
  findApiByPath: {
    apiPath: '/api/users'
  },
  findSymbolByName: {
    symbolName: 'UserController'
  },
  findMethodCallers: {
    symbolName: 'save'
  },
  findMethodCallees: {
    symbolName: 'createUser'
  },
  analyzeImpact: {
    methodName: 'save'
  }
};

const toolOptions = Object.keys(TOOL_TEMPLATES).map((name) => ({ label: name, value: name }));

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function ToolInvokePage() {
  // 演示只读工具调用、工具白名单、接口查询、调用关系查询和影响分析。
  const [repoId, setRepoId] = useState<number | null>(5);
  const [toolName, setToolName] = useState<ToolName>('searchCodeChunks');
  const [requestJson, setRequestJson] = useState(JSON.stringify(TOOL_TEMPLATES.searchCodeChunks, null, 2));
  const [requestPayload, setRequestPayload] = useState<unknown>();
  const [responseJson, setResponseJson] = useState<unknown>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  function handleToolChange(value: ToolName) {
    setToolName(value);
    setRequestJson(JSON.stringify(TOOL_TEMPLATES[value], null, 2));
    setRequestPayload(undefined);
    setResponseJson(undefined);
    setError(undefined);
  }

  async function handleInvoke() {
    if (!repoId) {
      message.error('请输入 repoId');
      return;
    }

    let payload: Record<string, unknown>;
    try {
      // 工具调用前先在前端解析 JSON，避免把无效参数发到后端只读工具白名单入口。
      const parsed = JSON.parse(requestJson);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('JSON root must be an object');
      }
      payload = parsed as Record<string, unknown>;
    } catch {
      setError('JSON 格式错误');
      message.error('JSON 格式错误');
      return;
    }

    setLoading(true);
    setError(undefined);
    setRequestPayload({ repoId, toolName, payload });
    try {
      setResponseJson(await invokeTool(repoId, toolName, payload));
    } catch (err) {
      setResponseJson(undefined);
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <Typography.Title level={3}>工具调试</Typography.Title>
        <Typography.Paragraph className="page-description">
          当前工具均为只读工具，用于补充 RAG 难以精确定位的信息；后端通过工具白名单、权限校验和 tool_call_log 控制访问边界。
        </Typography.Paragraph>
      </div>

      {error && <Alert className="section-card" type="error" message="调用失败" description={error} showIcon />}

      <Card className="section-card">
        <Space wrap className="toolbar">
          <InputNumber min={1} value={repoId} onChange={(value) => setRepoId(value)} addonBefore="repoId" />
          <Select<ToolName>
            value={toolName}
            options={toolOptions}
            onChange={handleToolChange}
            className="tool-select"
          />
          <Button type="primary" loading={loading} onClick={handleInvoke}>
            调用工具
          </Button>
        </Space>
      </Card>

      <Row gutter={16}>
        <Col span={12}>
          <Card title="请求 JSON">
            <Input.TextArea
              value={requestJson}
              onChange={(event) => setRequestJson(event.target.value)}
              autoSize={{ minRows: 16, maxRows: 24 }}
              className="json-textarea"
            />
          </Card>
        </Col>
        <Col span={12}>
          <Card title="实际请求">
            <JsonViewer value={requestPayload} maxHeight={260} />
          </Card>
          <Card title="返回 JSON" className="top-gap">
            <JsonViewer value={responseJson} maxHeight={360} />
          </Card>
        </Col>
      </Row>
    </div>
  );
}

export default ToolInvokePage;
