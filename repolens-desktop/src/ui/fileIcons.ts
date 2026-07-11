/** 扩展名 → codicon + VSCode 风格颜色。未匹配用默认文件图标。 */
const MAP: Record<string, { icon: string; color: string }> = {
  java: { icon: "codicon-symbol-class", color: "#e76f00" },
  xml: { icon: "codicon-code", color: "#8bc34a" },
  json: { icon: "codicon-json", color: "#cbcb41" },
  yml: { icon: "codicon-settings-gear", color: "#a074c4" },
  yaml: { icon: "codicon-settings-gear", color: "#a074c4" },
  md: { icon: "codicon-markdown", color: "#519aba" },
  sql: { icon: "codicon-database", color: "#dd8844" },
  ts: { icon: "codicon-symbol-method", color: "#3178c6" },
  tsx: { icon: "codicon-symbol-method", color: "#3178c6" },
  js: { icon: "codicon-symbol-method", color: "#cbcb41" },
  css: { icon: "codicon-symbol-color", color: "#519aba" },
  html: { icon: "codicon-code", color: "#e34c26" },
  properties: { icon: "codicon-settings", color: "#6e7681" },
};

export function iconForFile(name: string): { icon: string; color: string } {
  const ext = name.includes(".") ? name.split(".").pop()!.toLowerCase() : "";
  return MAP[ext] ?? { icon: "codicon-file", color: "#8b949e" };
}
