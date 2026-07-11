---
name: receiving-code-review
description: 当你收到了代码评审反馈、需要逐条处理时使用——反馈可能来自真人、PR 评论，或某个 Task 评审子代理。触发场景：用户粘贴了评审意见、说"评审者是这么说的""把这些意见处理一下"，或 requesting-code-review 返回了发现之后。本 skill 强制你对每一条意见做分诊（采纳 / 需讨论 / 附理由拒绝）、动手处理、再重新验证——不许默默无视反馈，也不许条件反射地全盘照收。改动之后要重新跑一遍验证。
---

# Receiving Code Review

Review feedback is a gift and a filter — not orders to obey blindly, and not opinions to brush off. Your job: process **every** comment deliberately, respond to each, act, and re-verify. The two failure modes are (1) silently ignoring comments and (2) reflexively "fixing" every comment including the wrong ones. This skill guards against both.

## HARD rule — every comment gets an explicit disposition

No comment may be dropped on the floor. For each one, pick exactly one:

- **ACCEPT** — it's right; you'll make the change.
- **DISCUSS** — you're unsure, it's ambiguous, or there's a trade-off worth surfacing; you'll ask the reviewer/user before acting.
- **REJECT (with reason)** — you disagree; you'll state *why*, concretely, so the reviewer can push back. "Rejecting" silently is not allowed; rejecting with a clear technical reason is completely fine.

Track this as a `TodoWrite` list — one item per comment with its disposition — so nothing gets lost, especially in a large review.

## Step 1 — Triage the whole list first

Read all comments before touching anything. Group them: real bugs/blockers, design concerns, and nits. Handle blockers first. Reading everything up front prevents you from fixing comment 1 in a way comment 7 contradicts.

Watch for comments that **conflict with each other** or with the agreed design — surface those rather than picking silently.

## Step 2 — Act on ACCEPTs

- Make each accepted change at the right spot with `edit`/`multi_edit`. Fix the **root** issue, not just the exact line cited — if a reviewer flags one instance of a bug, `grep` for the same pattern elsewhere and fix all of them. A review comment is a sample, not the whole population.
- Keep changes scoped to the feedback. Don't smuggle in unrelated refactors while "addressing review" — that just spawns a new review.

## Step 3 — Handle DISCUSS and REJECT

- For **DISCUSS**: use `askUser` (or reply to the reviewer) with the specific question and the trade-off. One question at a time if there are several. Don't guess on genuinely ambiguous feedback.
- For **REJECT**: write the reason plainly — "keeping this because X; the suggested change would break Y." Be open to being wrong. If the reviewer re-pushes with a good point, flip to ACCEPT. Ego is not a reason to reject.

## Step 4 — Re-verify everything you changed

Changes made in response to review are still changes — they can break things. After acting:
- `runVerification` — full suite, not just the touched files.
- Re-run any behavior the comments were about, plus the boundary cases (load `verification-before-completion` if the changes were non-trivial). A "fix" that addresses the comment but breaks a neighbor is not addressed.
- If a change turned out to be wrong or introduced a bug, load `systematic-debugging` — don't thrash.

## Step 5 — Report back the dispositions

Reply with a clear per-comment summary: for each, what you did (accepted+fixed / discussed→outcome / rejected+why) and the verification result. This closes the loop so the reviewer can confirm and re-review only what's needed, and it makes your reasoning auditable.

## Discipline
- Never mark review "addressed" while comments sit un-triaged.
- Never accept-and-implement something you believe is wrong just to close the thread — DISCUSS or REJECT with reason instead.
- Never let "addressing review" balloon into unrelated changes.
- Always re-verify; review fixes are exactly the kind of "small change" that silently breaks things.
