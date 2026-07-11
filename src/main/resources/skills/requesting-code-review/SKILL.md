---
name: requesting-code-review
description: 当你完成了一块实现、想在合并或收尾前让它接受评审时使用——或者用户说"评审一下这个""检查我的改动""这样行不行"，或在开 PR 之前。本 skill 会打包好 diff、说明意图、点出风险区，让评审更精准；它还能派出一个只读的 Task 子代理做一遍独立评审。如果你是评审意见的接收方，改用 receiving-code-review。如果还没做过验证，先做 verification-before-completion——别拿没验证过的代码去请人评审。
---

# Requesting Code Review

Make it easy to find your bugs. A good review request does the reviewer's setup work for them: shows exactly what changed, says what it's supposed to do, and points at the parts most likely to be wrong. A dump of "here's my diff, thoughts?" wastes the review.

## Prerequisite — verify first

Don't request review on code you haven't verified. Run `verification-before-completion` (or at least `runVerification`) first, so the review is about design and correctness of *working* code, not about "does it even run." Note the verification status in your request.

## Step 1 — Assemble the change set

- Get the actual diff: `bash` with git (`git diff`, `git diff --stat`) if this is a repo, or list the files you touched with a short note on each. The reviewer needs to see scope precisely, not guess it.
- Keep the review unit coherent — one feature/fix. If you've bundled unrelated changes, say so, or split them; mixed diffs get shallow reviews.

## Step 2 — State intent and context

Write a short brief:
- **What this change does** and **why** (link the design/plan doc or the issue if there is one).
- **What behavior should now be true** — the acceptance criteria, so the reviewer can check against intent, not just style.
- **How you verified it** (tests added, cases run).
- **What's explicitly out of scope** so the reviewer doesn't flag intentional omissions.

## Step 3 — Point at the risk (the most valuable part)

Reviewers have limited attention — aim it. Call out honestly:
- The parts you're **least sure about** — a tricky algorithm, a concurrency point, an error path you couldn't fully test.
- **Boundary/edge behavior** — how the code handles empty/null/large/invalid, numeric edges (int-vs-double), timeouts. Say what you did and didn't cover.
- **Assumptions you made** that could be wrong (about inputs, ordering, an API's behavior).
- **Anything you'd want a second opinion on** — a design trade-off, a naming choice, a dependency you added.

Volunteering "here's what worries me" gets you a far better review than pretending it's all solid.

## Step 4 — Get an independent pass with a subagent (optional but recommended)

Dispatch a read-only `Task` subagent to review the diff cold, so it isn't anchored on your framing. Give it a brief like:

> Review these changes for correctness bugs, missed edge cases, and design smells. Files: <list>. Intended behavior: <criteria>. Read the diff and surrounding code with read/grep. Return: concrete issues with file:line, severity (blocker/should-fix/nit), and a one-line rationale each. Do not rubber-stamp — actively look for what's wrong. You are read-only; do not attempt to edit.

Because Task subagents in this kernel are read-only, the subagent reviews and reports; **you** apply any fixes. Fold its findings in with your own risk list before presenting to the user.

## Step 5 — Present the review-ready package

Hand the user (or the human reviewer) a tidy package: the diff/summary, the intent brief, the verification status, the risk list, and the subagent's findings if you ran one. Make it a 2-minute read, not an archaeology dig.

## Discipline
- Never ask for review on unverified or half-finished code without labeling it as such.
- Never hide the sketchy part hoping it won't get noticed — surfacing it is the point.
- Don't argue the review before it happens; present neutrally and let it land. Then use `receiving-code-review` to handle what comes back.
