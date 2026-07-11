---
name: brainstorm
description: 在任何有一定复杂度的功能、重构或"这事该怎么下手"的问题开始之前、写任何代码之前使用。触发场景：用户说"我们来设计一下""这个该怎么搭""我在考虑加个……""怎么组织结构最好"，或者只给了一个模糊目标却没有具体方案时。当任务比一行小修复更大、且正确做法确实不明朗时也用它。本 skill 产出的是已确认的设计文档，而不是代码。如果设计已经确认、只需把工作排出顺序，改用 writing-plans。如果问题是行为出错的 bug，改用 systematic-debugging。
---

# Brainstorm

Turn a fuzzy goal into a concrete, approved design. Your job here is thinking and aligning — not building.

## HARD-GATE — No code until the design is approved

You MUST NOT write, edit, or scaffold any implementation code (no `write`/`edit`/`multi_edit` on source files) until the user explicitly approves a design. If you feel the urge to "just prototype it," resist — that urge is the exact failure this gate exists to stop. The only thing you produce is a design doc. Violating this gate means starting over.

## Step 1 — Explore the real context first

Before proposing anything, understand what exists. Use `grep`/`glob`/`read` (or dispatch a read-only `Task` subagent) to learn the current architecture, conventions, and constraints. Never design in a vacuum against assumptions. If you're missing external facts (a library's capability, an API), use the `research` skill. Come back with a short, accurate picture of the terrain.

## Step 2 — Refine requirements, ONE question at a time

This is the core discipline. Ask the user **exactly one question per turn**. Wait for the answer before the next. Batching questions overwhelms and produces shallow answers.

- Prefer multiple-choice or "A or B?" framing — it's faster for the user and forces you to have actually thought.
- Drive toward pinning down: the real goal (the why behind the ask), hard constraints, what's explicitly OUT of scope, and the success criteria ("how will we know this worked?").
- Keep going until you could hand the spec to another engineer and they'd build the right thing. When in doubt, ask one more — don't fill gaps with assumptions.

## Step 3 — Propose 2-3 approaches with trade-offs

Present 2-3 genuinely different approaches, not one dressed three ways. For each: the core idea, what it costs, what it buys, and the main risk. **Lead with your recommendation and say why.** Be opinionated — "I'd pick B because…" is more useful than a neutral menu. Let the user pick or push back.

## Step 4 — Present the design in segments, confirming each

Once an approach is chosen, walk through the design **section by section** (data model, interfaces, control flow, error handling, migration, testing strategy — whatever applies). After each segment, pause and ask "does this look right?" Fix before moving on. Do NOT dump a giant finished design and ask for one big yes — incremental confirmation catches divergence early and cheaply.

## Step 5 — Write the design doc

Write the agreed design to `docs/specs/<short-slug>.md` using the `write` tool. Include:
- **Goal & non-goals** (from Step 2).
- **Chosen approach & why** (and what was rejected).
- **Design** — the segments confirmed in Step 4, concrete enough to implement: name the files/modules that will change, the interfaces, the data shapes.
- **Success criteria / how it's verified.**
- **Open questions / risks** — be honest about what's still unknown.

## Step 6 — Self-audit before you call it done

Re-read the doc adversarially and fix:
- **Placeholders** — any "TODO", "figure out later", "TBD" in load-bearing sections. Resolve or explicitly flag as an open question.
- **Contradictions** — does section 4 assume something section 2 ruled out?
- **Scope creep** — did non-goals sneak back in? Cut them.
- **Vagueness** — any sentence a reader could implement two different ways. Make it precise.

## Terminal exit — hand off to planning

When the design is approved, this skill's ONLY next step is to produce an implementation plan. Call the `Skill` tool to load `writing-plans`, feeding it the approved design doc. Do not slide from design straight into coding — the plan is the bridge, and it's what keeps execution disciplined.

## Anti-patterns
- Writing code "to explore" — that's what breaks the gate.
- Asking five questions at once.
- Presenting one approach as if it were the only option.
- A design doc full of "TBD" that defers all the hard decisions to implementation time.
