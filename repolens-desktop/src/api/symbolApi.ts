import { http } from "./http";
import { SymbolHit } from "../types/graph";

export async function searchSymbols(repoId: number, keyword: string): Promise<SymbolHit[]> {
  return (await http.get("/api/symbols/search", {
    params: { repoId, symbolName: keyword },
  })) as unknown as SymbolHit[];
}
