---
name: systematic-debugging
description: 每当有东西行为出错时使用——测试失败、崩溃、异常、输出错误、结果时好时坏、"之前好好的现在不行了"、回归，或任何"这到底为什么会发生"。触发点：一旦你发现自己正打算连蒙带改、或在还不知道为什么坏的情况下就试着修，立刻启用。本 skill 强制你走"复现 → 定位根因 → 最小修复 → 验证 → 加防护"的路径，并禁止散弹式乱改。如果代码本身是对的、你只是在加功能，那就用错了（改用 writing-plans / executing-plans）。如果找根因需要深入的调用链追溯，它与 root-cause-tracing 配合使用。
---

# Systematic Debugging

Bugs are solved by understanding, not by luck. The failure mode this skill exists to kill is **changing code before you know why it's broken** — every "let me just try…" edit that isn't grounded in a diagnosed cause makes the tree noisier and the real bug harder to see.

## HARD-GATE — No fix before a diagnosed root cause

You may NOT edit code to "fix" the bug until you can state, in one sentence, the actual cause: "X happens because Y." If you can't fill that in, you're not ready to fix — you're guessing. Guessing edits are banned here.

## Step 1 — Reproduce it reliably

You cannot fix what you can't reproduce. Get a deterministic reproduction:
- Find or write the smallest input/command that triggers it. `runVerification` on the failing test, or `bash` to run the exact repro command.
- If it's flaky, pin down what makes it flip (ordering, timing, environment, state). A bug you can only sometimes see is a bug you can't confirm you fixed — invest in making it reliable first. (For timing/race issues, `condition-based-waiting` may be the real problem.)
- Capture the exact failure signal: the stack trace, the wrong value, the assertion. Read it carefully — the answer is often right there and skimmed past.

## Step 2 — Localize the root cause (don't guess — instrument)

Narrow down where reality first diverges from expectation:
- **Bisect.** Halve the search space: is the bad value already wrong at the function boundary, or does it go wrong inside? Binary-search the call chain, the commit history (`bash` with git if available), or the data path.
- **Add observability.** Insert logging / print of the key values at suspect points, then re-run. Watch where a value first becomes wrong — that's your suspect line, not where the exception finally surfaced.
- **Read the code path** with `read`/`grep` from the failure point backward. Trace where the bad state originates. For deep chains, call the `Skill` tool to load `root-cause-tracing`.
- **Check the boundaries** — off-by-one, null/empty, type coercion (int vs double, truncation), sign, overflow, timezone, encoding. This kernel's own history shows int-vs-double truncation as a real culprit; distrust silent numeric conversions.

Keep narrowing until you can say "X because Y" and point at the exact line/condition. Then remove the temporary logging (or keep the genuinely useful bits — see Step 5).

## Step 3 — Minimal fix at the root

Fix the *cause*, not the symptom. Do not paper over a wrong value downstream — go to where it becomes wrong. The fix should be the smallest change that corrects the root cause. Resist the temptation to also "clean up" nearby code in the same edit; keep the fix isolated so you can prove it's what mattered.

If the root cause reveals a design flaw (not just a typo), stop and consider whether this needs a real plan — `askUser` or `writing-plans` — rather than a band-aid.

## Step 4 — Verify the fix actually fixes it

- Re-run the exact repro from Step 1 with `runVerification`/`bash`. It must now pass.
- Run the surrounding test suite — confirm you didn't break neighbors.
- **Prove causation, not correlation.** Ideally revert the fix mentally (or actually) and confirm the bug returns — so you know it was your change that fixed it and not something incidental. Compiling clean is not fixing; behavior is the bar. Pairs with `verification-before-completion`.

## Step 5 — Guard against regression

A bug that had no test will come back. Add a test that fails without your fix and passes with it — this both proves the fix and prevents the regression. Put it where the suite will keep running it. If you added diagnostic logging that would help future debugging, consider keeping the durable bits.

## Anti-patterns (the things that waste hours)
- Changing code and re-running to "see if that helped" without a hypothesis.
- Fixing the symptom (clamping the bad output) instead of the cause (why it's bad).
- Declaring victory because it compiles or the specific case passes, without checking neighbors.
- Multiple simultaneous changes so you can't tell which one mattered.
- Not adding a regression test, so the bug silently returns next month.
