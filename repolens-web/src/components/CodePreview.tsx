import { useMemo } from 'react';
import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import xml from 'highlight.js/lib/languages/xml';
import json from 'highlight.js/lib/languages/json';
import yaml from 'highlight.js/lib/languages/yaml';
import sql from 'highlight.js/lib/languages/sql';
import properties from 'highlight.js/lib/languages/properties';
import plaintext from 'highlight.js/lib/languages/plaintext';
import 'highlight.js/styles/github-dark.css';

hljs.registerLanguage('java', java);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('json', json);
hljs.registerLanguage('yaml', yaml);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('properties', properties);
hljs.registerLanguage('plaintext', plaintext);

const EXT_LANG: Record<string, string> = {
  java: 'java',
  xml: 'xml',
  html: 'xml',
  json: 'json',
  yml: 'yaml',
  yaml: 'yaml',
  sql: 'sql',
  properties: 'properties'
};

function langFromPath(filePath?: string): string {
  if (!filePath) return 'plaintext';
  const ext = filePath.split('.').pop()?.toLowerCase() ?? '';
  return EXT_LANG[ext] ?? 'plaintext';
}

interface CodePreviewProps {
  code: string;
  filePath?: string;
  /** 代码起始行号，用于行号侧栏对齐真实行号。 */
  startLine?: number;
  /** 最大高度（px），超出滚动；不传则不限制。 */
  maxHeight?: number;
}

/**
 * 带语法高亮 + 行号侧栏的代码片段展示。
 * 行号从 startLine 起算，与后端返回的 startLine/endLine 对齐，呼应“可追溯”卖点。
 */
function CodePreview({ code, filePath, startLine = 1, maxHeight }: CodePreviewProps) {
  const language = langFromPath(filePath);
  const lines = useMemo(() => (code ?? '').replace(/\n$/, '').split('\n'), [code]);

  const highlightedLines = useMemo(() => {
    return lines.map((line) => {
      try {
        return hljs.highlight(line, { language }).value || '&nbsp;';
      } catch {
        return line || '&nbsp;';
      }
    });
  }, [lines, language]);

  return (
    <div className="code-preview" style={maxHeight ? { maxHeight, overflow: 'auto' } : undefined}>
      <table className="code-preview-table">
        <tbody>
          {highlightedLines.map((html, idx) => (
            <tr key={idx}>
              <td className="code-gutter">{startLine + idx}</td>
              <td className="code-line">
                <code className="hljs" dangerouslySetInnerHTML={{ __html: html }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default CodePreview;
