export interface SearchMatch {
  filePath: string;
  line: number;
  lineContent: string;
  startCol: number;
}

export interface SearchResult {
  query: string;
  matches: SearchMatch[];
  matchCount: number;
  truncated: boolean;
  offset: number;
  limit: number;
  hasMore: boolean;
}
