import type { RepoVO } from "../../api/repoApi";

/**
 * Reconciles a persisted repo id against the current list of repos visible to the user.
 *
 * Returns null if persistedId is null OR if persistedId is not found in the list
 * (i.e., stale — e.g., left over in localStorage from a previous user session or
 * a repo that was deleted).  Returns persistedId unchanged when it is still present.
 *
 * Pure function: no side-effects, safe to unit-test without mocking.
 */
export function reconcileRepoId(
  persistedId: number | null,
  list: RepoVO[],
): number | null {
  if (persistedId === null) return null;
  return list.some((r) => r.id === persistedId) ? persistedId : null;
}
