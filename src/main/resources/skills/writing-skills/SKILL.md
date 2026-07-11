---
name: writing-skills
description: 当你要新建一个 skill、编辑现有的 SKILL.md，或用户说"给 X 做个 skill""加个 skill""这事老是出现，把它沉淀成一个 skill""把这个 skill 的 description 或触发做得更好"时使用。这是用来正确编写"渐进式披露"skill 的元 skill——正确的 frontmatter、一个能可靠触发又不泄露工作流的 description、以及具体且可执行的正文。如果你不是在编写 skill、而是在做正常工作，那就用错了。
---

# Writing Skills

Skills are progressive-disclosure capability packs. At rest the agent sees only `name` + `description`; when a skill fires, its `body` loads and the agent follows it. Getting a skill right means getting three things right: it triggers at the correct time, it doesn't waste context, and once loaded it actually changes behavior. Most bad skills fail on triggering or on being too vague to obey.

## File and format rules (strict)
- One skill per directory: `.../skills/<name>/SKILL.md`. **The directory name MUST equal the `name` in frontmatter.**
- `name`: kebab-case, matches the folder.
- Frontmatter is exactly `name` and `description`, fenced by `---`.
- Body is markdown, roughly 1500–2500 words of operational instruction — concrete enough to follow, not a treatise.

## Writing the description — the highest-leverage line
The description is the ONLY thing the agent sees when deciding whether to load the skill. It must answer **"when do I use this?"** — symptoms, situations, and trigger keywords the user might say.

**HARD rule: do NOT summarize the workflow steps in the description.** If the description says "does A then B then C", the agent will follow *that* and skip reading the body — defeating progressive disclosure. Describe the *situation that warrants the skill*, never the procedure. Also state when NOT to use it and which sibling skill to prefer, to prevent mis-fires. Keep it ≤1024 characters.

Good: "Use when a failing test / crash / wrong output appears and you're about to guess-and-change…"
Bad: "This skill reproduces the bug, then finds the root cause, then fixes it, then verifies." (leaks the workflow)

## Writing the body — specific and enforceable
- **Lead with the failure mode** the skill prevents — the agent needs to know what it's protecting against.
- **Use HARD-GATE / HARD-rule language** for the non-negotiables. Vague advice ("try to be careful") gets ignored; hard gates ("you MUST NOT write code until X") get obeyed.
- **Concrete, numbered steps** the agent can execute, referencing the **real tools** by their true names (`read`, `write`, `edit`, `multi_edit`, `grep`, `glob`, `bash`, `runVerification`, `TodoWrite`, `Task`, `askUser`, `Skill`, `WebSearch`, `WebFetch`). Never invent tools.
- **An explicit anti-patterns section** — naming the wrong behaviors is often more effective than describing the right ones.
- **Cite the kernel's real constraints** where relevant (e.g. `Task` subagents are read-only; `bash` cwd doesn't persist between calls; don't run grep/find/cat/compile via bash). A skill that ignores the kernel's reality will mislead.

## Skill-to-skill references (discipline)
- Reference another skill by **name only**: "call the `Skill` tool to load `test-driven-development`."
- **NEVER** reference by `@path` or `file://` — that force-loads the whole body and blows up context, defeating progressive disclosure. The `Skill` tool handles loading.
- Point to sibling skills for handoffs (e.g. brainstorm → writing-plans → executing-plans) so the agent chains correctly.

## Authoring workflow
1. Confirm the skill doesn't already exist (`glob` the skills dir). Prefer editing an existing skill over adding an overlapping one.
2. Draft the `description` first — get triggering right before the body.
3. Write the body: failure mode → hard gates → numbered steps → anti-patterns.
4. `write` to `.../skills/<name>/SKILL.md` (create the matching directory).
5. **Self-audit**: Does the directory name equal `name`? Does the description leak the workflow (fix if so)? Are all referenced tools real? Any `@path` skill refs (remove)? Any placeholder/TODO left? Is every hard rule actually enforceable by the agent?

## Anti-patterns
- Description that narrates the steps → agent skips the body.
- Description that's just a title ("Debugging skill") with no triggers → never fires.
- Body full of platitudes with no gates or concrete steps → doesn't change behavior.
- Inventing tools the kernel doesn't have.
- `@path`/`file://` cross-references → context blowup.
- Directory name ≠ `name` → the skill won't resolve.
- One mega-skill trying to do five jobs → split into focused skills that reference each other.
