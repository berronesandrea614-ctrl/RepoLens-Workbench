---
name: writing-plans
description: 当你已经有一份确认过的设计或一个明确目标、需要在写代码之前把它变成一份具体、分阶段的实现计划时使用。触发场景：brainstorm 产出了已确认的设计之后，或用户说"做个计划""这个我们怎么落地""拆成几步"，或当任务横跨多个文件、多个阶段、直接上手写代码会很鲁莽时。本 skill 产出一份书面计划，含各阶段划分、每阶段的验收标准、以及各改动明确落在哪些文件。如果计划已经存在、你可以动手了，改用 executing-plans。如果做法本身都还不清楚，先用 brainstorm。
---

# Writing Plans

Convert an approved design into an implementation plan that another engineer (or a future you with no memory of this conversation) could execute correctly step by step. A good plan front-loads the thinking so execution is mechanical.

## Prerequisite check

You need an agreed approach. If the design/approach is still fuzzy or contested, STOP and call the `Skill` tool to load `brainstorm` first. Planning an unsettled design just produces a plan you'll throw away.

If a design doc exists (e.g. `docs/specs/<slug>.md`), `read` it in full and plan against it. If not, write down the goal, constraints, and success criteria in one paragraph and confirm with the user before planning.

## Ground the plan in the real codebase

Before writing phases, use `grep`/`glob`/`read` to confirm where things actually live: the modules you'll touch, the existing patterns to follow, the tests to extend. A plan that names files that don't exist or ignores existing conventions is worse than no plan. If exploration is large, dispatch a read-only `Task` subagent to map the affected area and report back.

## Structure of the plan

Break the work into **phases**. A phase is a coherent, independently verifiable chunk — ideally each phase leaves the system in a working, committable state. Order phases so each builds on the last and risk is front-loaded (do the scary/uncertain part early, while it's cheap to change course).

For **each phase**, write:

1. **Goal** — one sentence: what this phase accomplishes.
2. **Changes** — the concrete edits, each mapped to a **file landing spot** (`path/to/File.java` — add method `foo`, modify `bar`). Name real paths. If a new file, say where and why.
3. **Acceptance criteria** — how you'll *verify this phase is done and correct*, in terms that can be checked by running something: "`runVerification` on the new test passes", "app boots and endpoint returns 200", "existing suite still green". Vague criteria ("looks right") are forbidden — every phase must have a criterion you can objectively pass or fail.
4. **Tests** — what test(s) prove this phase, added before or alongside the code. Prefer specifying the test first (dovetails with `test-driven-development`).
5. **Risks / rollback** — what could break, and how to back out if it does.

## Discipline (HARD rules)

- **Every phase ends with a runnable verification.** No phase may be "done" on the basis of "I wrote the code." If a phase can't be verified, it's mis-scoped — split it or add a check.
- **Name real files.** Landing spots must be actual paths you confirmed exist (or explicitly new). No hand-waving "the relevant module".
- **Sequence for reversibility.** Prefer an order where you can stop after any phase with a working system.
- **Keep phases small enough to hold in your head.** If a phase has 15 sub-steps, it's two phases.
- **Call out cross-cutting concerns once** (migrations, feature flags, backward compat, config) rather than scattering them — but make sure each lands in a specific phase.

## Write it down

Write the plan to `docs/plans/<slug>.md` (or wherever the project keeps plans — check first with `glob`). Mirror the phase structure into a `TodoWrite` list so execution can track progress. The written plan is the source of truth; the todo list is the live tracker.

## Self-audit before handoff

Re-read the plan and check: Does phase N depend on anything not delivered by phases 1..N-1? Are there orphan steps with no acceptance criterion? Did any design decision get silently changed? Is anything still "TBD"? Fix all of these.

## Terminal exit

An approved plan hands off to `executing-plans` — call the `Skill` tool to load it. Do not begin implementing here; writing-plans plans, executing-plans executes. Keeping them separate is what stops "planning" from quietly becoming unplanned coding.
