---
name: test-driven-development
description: 当你要实现任何新行为、函数、接口，或修 bug，且正确性很重要、该行为又能用测试表达出来时使用。触发场景：开始做一个预期输出明确的功能、修一个 bug（先写出能复现它的失败测试）、或用户说"TDD""先写测试""测试驱动"。本 skill 强制你走"红 → 绿 → 重构"，并对常见的 TDD 反模式给出警告。如果正确行为本身都还不清楚，先用 brainstorm。任务收尾时的行为验收请用 verification-before-completion。
---

# Test-Driven Development

Write the test first, watch it fail, make it pass with the minimum, then clean up. TDD keeps you honest: a test written *after* the code tends to test what you built, not what was required. A test written *first* tests the requirement.

## The cycle — RED, GREEN, REFACTOR

### RED — write a failing test that captures the requirement
- Express exactly one behavior as a test: given this input/state, expect this output/effect. Use the real assertion, not a placeholder.
- **Run it and watch it fail** (`runVerification`). This is non-negotiable. A test you never saw fail might be passing for the wrong reason (wrong file, tautology, already-implemented). Confirm it fails **for the reason you expect** — read the failure message. A test that fails to compile or errors out is not yet a red test; fix it until it fails on the assertion.

### GREEN — minimum code to pass
- Write the least code that makes the failing test pass. Not the elegant general solution — the smallest thing that turns it green. Hardcoding to start is fine; the next test will force generality.
- Run the test. It must pass. Run the neighbors too — you didn't break anything.

### REFACTOR — clean up under green
- Now improve the code (dedupe, rename, extract) while the tests stay green. Re-run after each change. If a change turns something red, you learned something — back it out or fix it.
- Refactor the *tests* too if they've gotten repetitive or unclear. Test code is real code.

Then loop back to RED for the next behavior. Small cycles — one behavior at a time.

## For bug fixes specifically
Write the test that **reproduces the bug** first, watch it fail (this proves you've actually reproduced it), then fix the code until it goes green. This dovetails with `systematic-debugging` — the reproduction test is your regression guard.

## HARD rules
- **Never write implementation before its failing test exists.** If you already wrote code, write the test anyway and make sure it can fail (temporarily break the code to confirm the test catches it).
- **Always watch the test fail before making it pass.** Skipping RED is the #1 way to ship a test that asserts nothing.
- **One behavior per cycle.** Don't write ten tests then a big blob of code — you lose the tight feedback that makes TDD work.
- **Real assertions only.** A test with no meaningful assertion, or that asserts `true`, is worse than no test — it gives false confidence.

## Anti-patterns to avoid
- **Testing the implementation, not the behavior.** Asserting "method X was called" instead of "the output is correct" makes tests that break on every refactor and pass on real bugs. Test observable behavior.
- **Tautological tests.** `assert add(2,3) == add(2,3)` or mocking the thing under test. Assert against an independently-known expected value.
- **The always-green test.** Written after the code, never seen fail — often silently broken (wrong import, disabled, wrong assertion). Watching RED prevents this.
- **Over-mocking.** Mocking so much that the test verifies your mocks, not your code. Mock at real boundaries (network, clock, filesystem), use the real thing inside.
- **Giant setup, tiny assertion.** If setup dwarfs the check, the unit is too big — decompose.
- **Snapshot-everything.** Golden-file tests that assert a huge blob nobody reads; they rot into "just update the snapshot" and stop catching bugs. Assert the specific thing that matters.

## Verify at the end
When the feature's cycles are done, run the whole suite with `runVerification`, and for user-facing behavior, load `verification-before-completion` to actually exercise it — green units don't guarantee the feature works end to end.
