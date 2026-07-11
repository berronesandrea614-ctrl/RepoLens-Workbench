import { http } from "./http";
import { AgentMemory } from "../types/memory";

export async function fetchMemory(repoId: number): Promise<AgentMemory[]> {
  return (await http.get(`/api/repos/${repoId}/memory`)) as unknown as AgentMemory[];
}

export async function forgetMemory(repoId: number, memoryId: number): Promise<void> {
  await http.delete(`/api/repos/${repoId}/memory/${memoryId}`);
}
