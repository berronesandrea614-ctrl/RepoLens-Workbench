import { http } from "./http";
import { CodeGraph } from "../types/graph";
import { Requirement } from "../types/requirement";

export async function fetchRequirements(repoId: number): Promise<Requirement[]> {
  return (await http.get(`/api/repos/${repoId}/requirements`)) as unknown as Requirement[];
}

export async function fetchRequirementGraph(
  repoId: number,
  requirementId: number,
): Promise<CodeGraph> {
  return (await http.get(
    `/api/repos/${repoId}/requirements/${requirementId}/graph`,
  )) as unknown as CodeGraph;
}

export async function deleteRequirement(
  repoId: number,
  requirementId: number,
): Promise<void> {
  await http.delete(`/api/repos/${repoId}/requirements/${requirementId}`);
}

/**
 * Summarize a batch of externally-changed files (Claude Code file-watcher path) into a
 * requirement.  Returns the new requirement ID, or null if nothing was summarized.
 *
 * @param repoId       Target repository.
 * @param changedFiles Relative file paths changed by Claude.
 * @param realDir      Absolute path to the real project directory (optional).
 * @param sessionHint  Optional context hint (e.g. Claude session intent).
 */
export async function externalChangesSummarize(
  repoId: number,
  changedFiles: string[],
  realDir?: string,
  sessionHint?: string,
): Promise<number | null> {
  const body: Record<string, unknown> = { changedFiles };
  if (realDir) body.realDir = realDir;
  if (sessionHint) body.sessionHint = sessionHint;
  const result = await http.post<number | null>(
    `/api/repos/${repoId}/external-changes/summarize`,
    body,
  );
  return (result as unknown as number | null) ?? null;
}
