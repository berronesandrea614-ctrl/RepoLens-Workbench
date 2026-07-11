/** 每个打开文件的「已保存版本号」登记；Monaco model 本体由 @monaco-editor/react 按 URI 管理。 */
const savedVersions = new Map<string, number>();
const contentCache = new Map<string, string>();

let monacoNs: typeof import("monaco-editor") | null = null;
export const registerMonaco = (m: typeof import("monaco-editor")) => {
  monacoNs = m;
};

/** 缓存 / model 均按 (repoId, path) 作用域，避免切换仓库后同相对路径复用错误内容。 */
export const modelKey = (repoId: number, path: string) => `${repoId}::${path}`;
export const modelUriString = (repoId: number, path: string) => `file:///${repoId}/${path}`;
export const modelUri = (repoId: number, path: string) =>
  monacoNs ? monacoNs.Uri.parse(modelUriString(repoId, path)) : null;

export const getSavedVersion = (repoId: number, path: string) =>
  savedVersions.get(modelKey(repoId, path));
export const setSavedVersion = (repoId: number, path: string, v: number) => {
  savedVersions.set(modelKey(repoId, path), v);
};
export const getCachedContent = (repoId: number, path: string) =>
  contentCache.get(modelKey(repoId, path));
export const setCachedContent = (repoId: number, path: string, c: string) => {
  contentCache.set(modelKey(repoId, path), c);
};

export function disposeModelFor(repoId: number, path: string) {
  const key = modelKey(repoId, path);
  savedVersions.delete(key);
  contentCache.delete(key);
  const uri = modelUri(repoId, path);
  if (uri) monacoNs?.editor.getModel(uri)?.dispose();
}
