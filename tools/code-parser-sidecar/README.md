# RepoLens 多语言解析 sidecar

进程外代码解析器，供后端索引链路抽取**符号 + 调用边**，写入 `code_symbol` / `code_dependency`。
已支持 **TypeScript / JavaScript / Python / Go / Rust / C# / Ruby**（tree-sitter）。
加语言 = 加一个 grammar + 一个 walker，不动 JVM。（PHP 需 tree-sitter@0.23 运行时，待整体升级后加。）

## 安装（首次必做）

后端调用本 sidecar 前，需要先装依赖：

```bash
cd tools/code-parser-sidecar
npm install
```

未安装时后端会**跳过 TS/JS 解析并记一条日志**（fail-safe，不影响 Java 索引）。

## 契约

- 调用：`node index.js <repoDir>`，stdin 传 JSON 相对路径数组（为空则自动遍历 `<repoDir>`）。
- 输出：`{ ok, results: [ { path, language, symbols[], dependencies[] } ] }`。
  字段定义见 `index.js` 头部注释。

## 自测

```bash
npm run selftest   # 解析 fixtures/ts-sample，打印 symbols/dependencies
```

## 后端相关配置

- `repolens.parser.node-bin`（默认 `node`，或 env `REPOLENS_PARSER_NODE_BIN`）
- `repolens.parser.sidecar-dir`（默认 `tools/code-parser-sidecar`）
- `repolens.parser.timeout-ms`（默认 120000）

## 扩展新语言（Phase 2+）

1. `npm i tree-sitter-<lang>`；
2. 在 `index.js` 的 `loadGrammars()` 注册扩展名 → grammar；
3. 在 `walk()` 里按该语言的节点类型补充符号/调用边抽取（多数语言的
   function/class/method/call 节点类型与 TS 类似，可复用）。
