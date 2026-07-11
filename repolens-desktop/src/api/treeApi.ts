import { http } from "./http";
import { FileTreeNode } from "../types/tree";

export async function fetchTree(repoId: number): Promise<FileTreeNode> {
  return (await http.get(`/api/repos/${repoId}/tree`)) as unknown as FileTreeNode;
}
