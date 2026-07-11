---
name: root-cause-tracing
description: 当一个 bug 表面症状离它的根因很远、你必须沿着调用链把坏值或错误状态往回追溯到它"最早出错的那一点"时使用。触发场景：异常在栈很深处冒出来但其实源头在别处、某个错误的值到了某处而你不知道是谁产生的、"报错在这里但这段代码看着没问题"，或 systematic-debugging 的定位步骤需要更深入的调用链追溯时。本 skill 是 systematic-debugging 的细粒度追溯搭档——当根因在上游好几跳之外时，在那套流程内部启用它。
---

# Root-Cause Tracing

The symptom is where the program *notices* the problem; the root cause is where the problem is *created*. They're often many function calls apart. This skill is the disciplined backward walk from symptom to origin, so you fix the source instead of clamping the symptom.

This is a companion to `systematic-debugging` — invoke it when the "localize the cause" step needs to trace a value across many hops.

## The trace-backward method

### 1. Pin the symptom precisely
Capture the exact bad observation: the wrong value (and what it *should* be), the exact line where it's detected, the full stack trace. `read` that line and understand what it's asserting. The stack trace is a map of *how execution got here*, not *where the bug is* — read it as the trail, not the destination.

### 2. Find the last known-good and first known-bad point
The bug lives on the path between "state was correct here" and "state was wrong here." Your job is to shrink that interval to a single step.
- Walk **upstream** from the symptom: who passed this value in? `grep` for the callers, `read` the call sites. At each hop ask: "was the value already wrong when it entered this function, or did this function corrupt it?"
- **Instrument the boundaries.** Add a log/print of the suspect value at each function entry/exit along the chain, then re-run (`runVerification`/`bash`). Find the first boundary where it's wrong. The corruption happened between the previous good boundary and this bad one.
- **Binary-search the chain** rather than checking every hop linearly: instrument the midpoint of the suspect span first, then recurse into the half that contains the transition.

### 3. Identify the exact origin
Keep narrowing until you're at the single statement that first produces the wrong state. Common origins to check when you get there:
- A transformation that's subtly wrong (int/double truncation, rounding, sign, off-by-one, unit mismatch).
- A default/uninitialized value silently substituted for a real one.
- A wrong branch taken (a condition that's inverted or mis-computed).
- A mutation from an unexpected caller (shared/aliased state changed elsewhere — `grep` for every writer of that field).
- A boundary assumption violated upstream (empty/null/large input never handled).

### 4. Confirm causation
State it: "the value is wrong because <origin> does X." Then prove it — a targeted fix at the origin should make the symptom vanish, and reverting it should bring the symptom back. If fixing the origin doesn't fix the symptom, you found *an* origin, not *the* origin — keep tracing.

## HARD rules
- **Trace to the origin; never patch the symptom.** Clamping/validating at the point of detection hides the bug and leaves the real defect to resurface elsewhere. Fix where the value first goes wrong.
- **Follow evidence, not hunches.** Each hop is justified by an observed value, not a guess about where the bug "feels like" it is.
- **Instrument, then read.** Actually run with logging to see where the value flips — don't just eyeball the code and assume.
- **`grep` for every writer** of a suspicious piece of shared state — corruption often comes from an unexpected caller you didn't think to check.

## Handing back
Once you've found and fixed the origin, return to `systematic-debugging`'s verify + regression-guard steps: re-run the repro, run neighbors, and add a test that fails without the origin fix. A root cause found but not guarded will be reintroduced.

## Anti-patterns
- Fixing the line in the stack trace where the exception was thrown (usually just where it was *detected*).
- Adding a null-check at the crash site instead of finding why null got there.
- Guessing the culprit and "fixing" it without instrumenting to confirm the value actually goes wrong there.
- Stopping at the first suspicious-looking code without proving it's the origin.
