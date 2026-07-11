import { useMemo } from 'react';

interface JsonViewerProps {
  value?: unknown;
  emptyText?: string;
  maxHeight?: number;
}

function JsonViewer({ value, emptyText = '暂无数据', maxHeight = 360 }: JsonViewerProps) {
  const content = useMemo(() => {
    if (value === undefined || value === null || value === '') {
      return emptyText;
    }
    try {
      return typeof value === 'string' ? JSON.stringify(JSON.parse(value), null, 2) : JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }, [emptyText, value]);

  return (
    <pre className="json-viewer" style={{ maxHeight }}>
      {content}
    </pre>
  );
}

export default JsonViewer;
