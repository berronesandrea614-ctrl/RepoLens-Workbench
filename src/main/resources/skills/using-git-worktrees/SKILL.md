---
name: using-git-worktrees
description: 当你需要在隔离环境里改某个分支、又不想扰动当前工作树时使用——并行开发多个功能、做一个可能会丢弃的高风险实验、在保留自己改动不变的前提下评审或构建别人的分支，或同时推进两条工作线。触发场景：用户说"这个单独开一份来做""别动我当前的改动""给 X 开个分支""这个隔离开来试试"，或当你正要切分支但手上有未提交的工作想保住时。如果你只是需要一个普通分支、并不会同时跑两棵树，那用普通分支就行，比 worktree 更简单。
---

# Using Git Worktrees

A git worktree lets one repository have multiple working directories checked out to different branches at the same time. Use it to isolate a line of work so it can't collide with — or contaminate — what's in your main tree. This is the clean way to do parallel or throwaway work.

## When a worktree is the right tool
- You have uncommitted work in the current tree and need to do something on another branch **without stashing/switching** and risking your changes.
- You want to run two things at once (e.g. keep the app running from one tree while building a fix in another).
- You're trying a risky approach you may discard — an isolated tree makes "throw it all away" trivial (just remove the worktree).
- You're reviewing/building a colleague's branch and want it fully separate from your work.

If none of these apply and you can just commit-then-branch on one tree, do that — worktrees add directories to manage.

## Preconditions
Confirm you're in a git repo (`bash`: `git rev-parse --is-inside-work-tree`). Worktrees share the same `.git`, so all worktrees see the same commits/branches. A branch can be checked out in only ONE worktree at a time — that's a feature (prevents two trees fighting over one branch).

## Creating a worktree
Use `bash`. Put worktrees OUTSIDE the main tree (a sibling directory) so they don't get swept into builds or accidental commits.

- New branch in a new worktree:
  `git worktree add -b <branch> ../<repo>-<branch> <base-ref>`
- Existing branch:
  `git worktree add ../<repo>-<branch> <branch>`

Then `cd`-based work in that directory. Remember: in this kernel each `bash` call resets cwd between calls, so **always use absolute paths** to the worktree, or pass `-C <path>` to git — do not rely on a persisted `cd`.

## Working in the worktree
- Reference files by their absolute path inside the worktree for `read`/`edit`/`write`.
- Run builds/tests with `runVerification` or `bash` scoped to the worktree path.
- Commit as normal; commits land on the worktree's branch and are visible from all worktrees.

## Listing and inspecting
- `git worktree list` — shows every worktree, its path, and its branch. Use this to stay oriented when juggling several.

## Cleaning up (do NOT just `rm -rf`)
When done:
1. Make sure work is committed or intentionally discarded.
2. `git worktree remove <path>` — the correct way; it validates the tree is clean and unregisters it. (If it's intentionally dirty and you want it gone, `--force`.)
3. If a worktree directory got deleted manually and git still lists it, run `git worktree prune` to clear the stale registration.

Deleting the directory with `rm` without `git worktree remove` leaves git's bookkeeping pointing at a ghost — always remove/prune through git.

## Discipline
- **Absolute paths always** (cwd doesn't persist between `bash` calls here).
- **One branch, one worktree** — don't fight git trying to check the same branch out twice.
- **Worktrees live outside the main tree** to avoid build/commit contamination.
- **Clean up through git**, not `rm`.
- Don't leave a pile of stale worktrees around; `git worktree list` + remove when a line of work is done.
