---
name: executing-plans
description: 当已经有一份写好、已确认的实现计划、到了动手落地的时候使用。触发场景：writing-plans 产出了分阶段计划之后，或用户说"开始实现吧""照计划开工""执行第一阶段"，或直接把一份现成的计划文档交给你去执行。本 skill 强制你有纪律地一阶段一阶段推进，每一步都做验证，不许跳步抢跑。如果还没有计划，先用 writing-plans。如果执行途中撞上 bug，改用 systematic-debugging，而不要瞎试乱改。
---

# Executing Plans

Carry out an approved plan phase by phase, verifying as you go, never running ahead of the plan. Discipline here is the whole point: a plan is only worth writing if you actually follow it.

## Before you touch code

- `read` the plan doc in full. If there isn't one, STOP — call the `Skill` tool to load `writing-plans`. Don't improvise a plan while executing it.
- Load the plan's phases into a `TodoWrite` list, one item per phase, all `pending`. This is your live tracker.
- Confirm the starting state is clean (tests pass, builds) with `runVerification` so you know any breakage you introduce is yours. If it's already broken, surface that to the user first.

## The per-phase loop (repeat for each phase, in order)

1. **Mark the phase `in_progress`** in `TodoWrite`. Exactly one phase in progress at a time.
2. **Re-read the phase** — its goal, changes, file landing spots, and acceptance criteria. Do only what this phase specifies.
3. **Write the test(s) first** if the phase calls for them (see `test-driven-development`). Watch them fail for the right reason.
4. **Make the changes** at the specified landing spots using `edit`/`multi_edit`/`write`. Follow existing conventions in those files. Stay inside the phase's scope.
5. **Verify against the acceptance criteria** with `runVerification` (compile + tests) and, where the criteria demand behavior, actually run it. Compiling is NOT passing — the criterion is the bar.
6. **Only when the criteria are objectively met**, mark the phase `completed` and move to the next. If the project commits per phase and the user wants that, this is the commit point.

## HARD rules

- **No skipping ahead.** Do not start phase N+1 before phase N's acceptance criteria pass. The plan's ordering encodes dependencies and reversibility — jumping breaks both.
- **No silent scope expansion.** If you notice work the plan missed, do NOT just do it. Note it, and either ask the user or add it as an explicit new phase. Sneaking in "while I'm here" changes is how plans rot.
- **Verify every phase.** A phase you didn't verify is a phase that's probably broken. "It should work" is not a completed phase.
- **Leave it working.** After each phase the system should build and pass. If a phase can't leave it working, the plan was mis-phased — flag it.

## When reality diverges from the plan

Plans meet reality and reality wins sometimes. When a phase turns out to be wrong, blocked, or based on a false assumption:

- **STOP executing.** Do not thrash by trying variation after variation — that's how you end up with a mangled tree and no idea what state it's in.
- If it's a **bug** (something behaves wrong), call the `Skill` tool to load `systematic-debugging` and fix the root cause before resuming.
- If it's a **plan defect** (the approach is wrong), surface it to the user with `askUser`: here's what the plan said, here's what's actually true, here's the adjustment I propose. Update the plan doc, then resume.
- Never quietly abandon the plan and free-code the rest. If you're off-plan, either fix the plan or get sign-off.

## Finishing

When all phases are `completed`:
- Run the full verification suite one more time (`runVerification`) — not just the last phase's tests.
- Call the `Skill` tool to load `verification-before-completion` before declaring the whole task done. Green tests on the last phase are not the same as the feature actually working end to end.
- Summarize what was built, mapped back to the plan's phases, and note any deviations that were agreed along the way.
