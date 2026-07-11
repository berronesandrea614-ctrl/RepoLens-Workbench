---
name: verification-before-completion
description: 在告诉用户任务已完成、修复已生效、或功能已做好之前，作为最后一道关卡使用。触发点：每当你正要说"搞定了""修好了""这样应该就行了""完成"，或要把活交回去时。本 skill 强制你真的把东西跑起来、亲眼确认行为正确——包括边界和极端情况——而不是凭"能编译"或"正常路径过了"就下结论。它对应内核里的对抗性自检。如果验证暴露出 bug，交给 systematic-debugging 处理。
---

# Verification Before Completion

The gate between "I think it works" and "it works." The failure this kills: declaring done based on weak evidence — it compiled, the one case I tried passed, the diff looks right — when the actual behavior is wrong at a boundary you never exercised.

**Compiling is not correct. Happy-path passing is not correct. Looks-right is not correct.** Only observed correct behavior across the cases that matter is correct.

## HARD-GATE — Do not claim completion without observed evidence

Before you write "done"/"fixed"/"works", you must have **run the actual behavior and watched it produce the right result**, including at least the boundary cases below. If you haven't run it, you don't know — say "I believe this is correct but haven't verified X" instead of asserting success.

## The verification checklist

### 1. Run the real thing, not a proxy
- `runVerification` for compile + full test suite — but that's the floor, not the ceiling.
- Actually execute the behavior: run the app/command/endpoint via `bash`, feed it real input, observe real output. For a bug fix, re-run the exact original repro and confirm it's gone. For a feature, drive it the way a user would.

### 2. Exercise the boundaries (this is where bugs hide)
Deliberately test the cases the happy path skips — this is the adversarial self-check:
- **Empty / zero / null** — empty input, empty list, missing field, zero count.
- **One and many** — single element vs. large N; off-by-one at both ends.
- **Numeric edges** — negative, overflow, and especially **int-vs-double / truncation** (a known culprit in this kernel — a value that's right for `2` but wrong for `2.5`).
- **Invalid / malformed input** — does it fail safely with a clear error, or silently corrupt?
- **The stated requirement's exact wording** — re-read what was asked and confirm the behavior matches *that*, not what you assumed.

Pick the boundaries that actually apply and run them. If a boundary is impossible to hit, say why.

### 3. Check you didn't break the neighbors
Run the surrounding suite / adjacent features. A fix that breaks something else is not done.

### 4. Reconcile with the original request
Re-read the user's task. Walk each stated requirement and point to the evidence it's satisfied. If any requirement lacks evidence, it's not verified — either verify it or flag it explicitly.

## When verification fails
Good — you caught it before the user did. Do NOT patch blindly. Call the `Skill` tool to load `systematic-debugging`, find the root cause, fix, then re-run this whole gate from the top. A fix that hasn't been re-verified is just another guess.

## Reporting completion honestly
When you do report done, state **what you verified and how**, not just "done":
> Verified: full suite green (`runVerification`); ran the endpoint with empty input → 400 as expected, with 1 and 500 items → correct output; re-ran the original repro → fixed; adjacent auth tests still pass.

If something is verified-by-reasoning rather than by running, say so explicitly and let the user decide if that's enough. Never dress an unverified claim as a verified one — that's the exact trust breach this gate exists to prevent.
