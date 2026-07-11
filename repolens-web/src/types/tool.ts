export type ToolName =
  | 'searchCodeChunks'
  | 'getFileContent'
  | 'findApiByPath'
  | 'findSymbolByName'
  | 'findMethodCallers'
  | 'findMethodCallees'
  | 'analyzeImpact';

export type ToolInvokePayload = Record<string, unknown>;
