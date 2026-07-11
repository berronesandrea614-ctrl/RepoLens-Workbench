import { http } from "./http";
import { SearchResult } from "../types/search";

export async function searchText(
  repoId: number,
  q: string,
  caseSensitive: boolean,
  offset: number = 0,
  limit: number = 100
): Promise<SearchResult> {
  return (await http.get(`/api/repos/${repoId}/search`, {
    params: { q, caseSensitive, offset, limit },
  })) as unknown as SearchResult;
}
