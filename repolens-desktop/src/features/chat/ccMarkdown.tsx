import React from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

/**
 * 把 agent 输出的 markdown 渲染成 Claude 那样干净易读的富文本：
 * 标题、加粗/斜体、有序/无序列表、`行内代码`、``` 代码块、> 引用、--- 分隔线、
 * 以及 GFM 表格（remark-gfm）。样式见 chat.css 的 `.cc-md`。
 *
 * 用 react-markdown 而非手写解析——手写版覆盖不了表格且容易把 `**`/`|` 漏成裸符号。
 */
const components: Components = {
  // 链接新窗口打开（Tauri webview 里等于走系统浏览器/外部）
  a: ({ node, ...props }) => <a {...props} target="_blank" rel="noreferrer" />,
  // 表格套一层可横向滚动的容器，宽表不撑破气泡
  table: ({ node, ...props }) => (
    <div className="md-table-wrap">
      <table {...props} />
    </div>
  ),
};

export function renderMarkdown(text: string): React.ReactNode {
  if (!text) return null;
  // 折叠 3+ 连续换行为一个段落分隔，避免模型输出的大量空行撑出巨大空白。
  const cleaned = text.replace(/\r\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {cleaned}
    </ReactMarkdown>
  );
}
