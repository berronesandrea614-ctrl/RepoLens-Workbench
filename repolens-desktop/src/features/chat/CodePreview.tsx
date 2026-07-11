import { useMemo } from "react";
import hljs from "highlight.js/lib/core";
import java from "highlight.js/lib/languages/java";
import xml from "highlight.js/lib/languages/xml";
import json from "highlight.js/lib/languages/json";
import yaml from "highlight.js/lib/languages/yaml";
import sql from "highlight.js/lib/languages/sql";
import properties from "highlight.js/lib/languages/properties";
import "highlight.js/styles/github-dark.css";
import "./codePreview.css";

hljs.registerLanguage("java", java);
hljs.registerLanguage("xml", xml);
hljs.registerLanguage("json", json);
hljs.registerLanguage("yaml", yaml);
hljs.registerLanguage("sql", sql);
hljs.registerLanguage("properties", properties);

function langFromPath(path?: string): string {
  if (!path) return "plaintext";
  if (path.endsWith(".java")) return "java";
  if (path.endsWith(".xml") || path.endsWith(".html")) return "xml";
  if (path.endsWith(".json")) return "json";
  if (path.endsWith(".yml") || path.endsWith(".yaml")) return "yaml";
  if (path.endsWith(".sql")) return "sql";
  if (path.endsWith(".properties")) return "properties";
  return "plaintext";
}

export function CodePreview({ code, filePath, startLine = 1, maxHeight }: {
  code: string; filePath?: string; startLine?: number; maxHeight?: number;
}) {
  const lang = langFromPath(filePath);
  const lines = useMemo(() => code.replace(/\n$/, "").split("\n"), [code]);

  const highlighted = useMemo(
    () => lines.map((line) => {
      try {
        return lang === "plaintext" ? escapeHtml(line) : hljs.highlight(line, { language: lang }).value;
      } catch {
        return escapeHtml(line);
      }
    }),
    [lines, lang],
  );

  return (
    <div className="code-preview" style={maxHeight ? { maxHeight } : undefined}>
      <table className="code-preview-table">
        <tbody>
          {highlighted.map((html, i) => (
            <tr key={i}>
              <td className="code-gutter">{startLine + i}</td>
              <td className="code-line hljs" dangerouslySetInnerHTML={{ __html: html || "&nbsp;" }} />
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
