---
name: subagent-driven-development
description: 当任务大到能从并行调查、独立评审或分而治之的研究中获益时使用——需要先摸清很多文件的大型重构、横跨整个代码库的问题，或任何"侦察 / 验证工作可以和主线程并行推进"的活。触发场景：当你发现自己正打算串行地读几十个文件，或想对自己的成果来一遍全新、无偏见的复核时。关键：在本内核里，Task 子代理是只读的（read/grep/glob 加联网）——它们只负责调查并汇报，不能写。所有写入都由主循环完成。如果任务很小或纯粹是串行的，就别费劲上子代理了。
---

# Subagent-Driven Development

Use read-only subagents to parallelize the parts of development that don't require writing — reconnaissance, research, and independent review — while the main loop stays the single writer. This keeps your context clean (subagents burn their own context and return only a summary) and gives you unbiased second opinions.

## The one constraint that shapes everything

**`Task` subagents in this kernel can only `read`/`grep`/`glob` (plus `WebSearch`/`WebFetch`). They CANNOT write, edit, run builds, or change anything.** So the classic "each subagent implements a module" pattern does NOT apply here. Instead:

- **Subagents:** explore, map, research, review, gather evidence → return a summary.
- **Main loop (you):** make ALL edits, run ALL verification, own the plan.

Never ask a subagent to "implement", "fix", or "write" — it will fail or hallucinate having done so. Ask it to *find, analyze, and report*.

## Pattern 1 — Parallel reconnaissance (map before you build)
Before a large change, dispatch several read-only subagents **in one message** (so they run concurrently), each mapping a slice:
> "Map how <feature> is wired: find every file that references <symbol>, describe the call flow, and list the exact files/functions a change would touch. Return a concise map with file:line anchors. Read-only — do not edit."

Fold their maps into your `TodoWrite`/plan. This replaces reading 40 files yourself and keeps your context focused.

## Pattern 2 — Parallel research
When several independent external/factual questions block the design, give each to its own subagent with `WebSearch`+`WebFetch` (see the `research`/`deep-research` skills for the rigor). They report; you decide.

## Pattern 3 — Independent review
After you implement, dispatch a subagent to review your diff cold (see `requesting-code-review`). Because it isn't anchored on your reasoning, it catches things you rationalized. It reports issues with file:line + severity; **you** apply the fixes.

## Pattern 4 — Adversarial verification helper
Have a subagent enumerate the boundary/edge cases a change should handle and check the code paths for them, reporting gaps. You then write the tests/fixes and run them (`runVerification`) — the subagent can't run anything.

## Writing a good subagent brief
Every dispatch must include:
- **A single, bounded objective** — one question or one slice, not "look at everything."
- **Exactly what to return** — the shape of the summary (a map, a list of file:line findings, a yes/no + evidence). Vague briefs return vague noise.
- **A reminder of the read-only boundary** — "do not attempt to edit; report only."
- **Enough context to start** — the relevant paths/symbols so it doesn't flail.

## Orchestration discipline
- **Fan out in a single message** when subtasks are independent, so they run in parallel. Serialize only when one depends on another's output.
- **You are the integrator.** Subagents return summaries; reconcile conflicts, dedupe, and decide. Don't blindly paste a subagent's summary as truth — sanity-check load-bearing claims (they can be wrong or over-confident).
- **Keep the write path single-threaded (you).** All edits go through the main loop so there's one coherent state and one place that runs verification. This is both a kernel constraint and good hygiene.
- **Don't over-parallelize.** Three sharp subagents beat ten fuzzy ones; each one you dispatch you also have to read and integrate.

## Anti-patterns
- Asking a subagent to write/fix/build — it can't; you'll get a fabricated "done."
- One giant "go understand the whole repo" subagent that returns mush — scope each one.
- Treating subagent output as verified fact without checking the load-bearing parts.
- Spawning subagents for a task small enough to just do inline.
