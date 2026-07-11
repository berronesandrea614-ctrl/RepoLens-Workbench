import { useState } from 'react';
import { Button, Card, Space, Tag, Typography } from 'antd';
import type { CodeReference } from '../types/chat';
import CodePreview from './CodePreview';

interface ReferenceCardProps {
  reference: CodeReference;
  index?: number;
}

function formatScore(score?: number) {
  return typeof score === 'number' ? score.toFixed(3) : '-';
}

function fileName(filePath?: string) {
  if (!filePath) return '-';
  const parts = filePath.split('/');
  return parts[parts.length - 1];
}

function ReferenceCard({ reference, index }: ReferenceCardProps) {
  const [expanded, setExpanded] = useState(false);
  const lines =
    typeof reference.startLine === 'number' && typeof reference.endLine === 'number'
      ? `${reference.startLine}-${reference.endLine}`
      : '-';
  const hasCode = Boolean(reference.contentPreview);

  return (
    <Card size="small" className="reference-card" hoverable>
      <Space direction="vertical" size={8} className="full-width">
        <div className="reference-head">
          <Space size={6} align="center">
            {typeof index === 'number' && <span className="reference-badge">{index + 1}</span>}
            <Typography.Text strong className="reference-filename">
              {fileName(reference.filePath)}
            </Typography.Text>
          </Space>
          <Typography.Text type="secondary" copyable={{ text: reference.filePath }} className="reference-path">
            {reference.filePath}
          </Typography.Text>
        </div>

        <Space size={6} wrap>
          <Tag color="blue">{reference.chunkType || 'CODE'}</Tag>
          <Tag>L{lines}</Tag>
          {typeof reference.score === 'number' && <Tag color="green">score {formatScore(reference.score)}</Tag>}
          {(reference.className || reference.methodName) && (
            <Tag color="geekblue">{[reference.className, reference.methodName].filter(Boolean).join('#')}</Tag>
          )}
        </Space>

        {hasCode ? (
          <>
            <CodePreview
              code={reference.contentPreview as string}
              filePath={reference.filePath}
              startLine={reference.startLine}
              maxHeight={expanded ? undefined : 160}
            />
            <Button type="link" size="small" className="reference-expand" onClick={() => setExpanded((v) => !v)}>
              {expanded ? '收起' : '展开全部'}
            </Button>
          </>
        ) : (
          <Typography.Text type="secondary">无内容预览</Typography.Text>
        )}
      </Space>
    </Card>
  );
}

export default ReferenceCard;
