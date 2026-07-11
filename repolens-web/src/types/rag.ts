export interface RagSearchPayload {
  query: string;
  topK: number;
}

export interface RagChunk {
  chunkId: string;
  filePath: string;
  chunkType: string;
  language: string;
  startLine: number;
  endLine: number;
  score: number;
  content?: string;
  contentPreview?: string;
}

export interface RagSearchResult {
  repoId: number;
  query: string;
  topK: number;
  hitCount: number;
  degraded?: boolean;
  degradeReason?: string;
  results: RagChunk[];
}
