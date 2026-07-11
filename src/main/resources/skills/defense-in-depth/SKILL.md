---
name: defense-in-depth
description: 当你在边界处处理不可信或可能出错的输入时使用——用户输入、网络或 API 响应、文件解析、反序列化、配置值——或者当"一层校验应该就够了"、但那里一旦失守代价高昂（数据损坏、安全漏洞、静默产出错误结果）时使用。触发场景：正在写会去信任某个不该信任之物的代码、在审查输入处理是否有疏漏、或用户问"这段够健壮吗 / 能扛住坏输入吗"。本 skill 教你分层校验与安全失败（fail safe）。如果你是在追查一个已有的 bug 而不是在做加固，改用 systematic-debugging。
---

# Defense in Depth

A single check is a single point of failure. Defense in depth means putting **independent, layered safeguards** along the path of untrusted or fallible data so that if one layer misses, another catches it — and when something does slip through, the system fails safe rather than silently producing garbage. The failure this prevents: one validation gets bypassed, refactored away, or has a gap, and there's nothing behind it.

## Principle: validate at every trust boundary, not just the front door
Each place where data crosses from less-trusted to more-trusted is a boundary that deserves its own check. Don't assume "it was validated upstream" — upstream code changes, gets reused, or gets called from a new path that skipped the check.

Typical layers, outermost to innermost:
1. **Input validation** at the entry point — shape, type, range, required fields. Reject malformed input early with a clear error.
2. **Normalization/canonicalization** — convert to a single canonical form before checking (paths, encodings, casing) so checks can't be tricked by an alternate representation.
3. **Business-rule checks** deeper in — invariants the type system can't express ("end date after start date", "amount within limit").
4. **Defensive assertions at the core** — assert the invariants that MUST hold before the sensitive operation. If an earlier layer failed, this is the last catch.
5. **Safe handling of the sensitive operation itself** — parameterized queries, bounds-checked writes, least-privilege.

You don't need all five everywhere — you need **more than one independent layer** wherever a miss would be costly.

## Applying it in code
- When you `write`/`edit` code that consumes external input, add the entry-point check AND at least one deeper invariant check. Use `grep` to find every path that reaches the sensitive operation and confirm each is covered — a boundary with one uncovered caller is not defended.
- Make each layer **independent** — if two "layers" both rely on the same helper, they're one layer. A real second layer catches what the first's specific bug would miss.
- Prefer **whitelisting** (accept known-good) over blacklisting (reject known-bad) — blacklists always miss a case.

## Fail safe, fail loud
When a check trips:
- **Fail closed**, not open — deny/abort the operation rather than proceeding with unvalidated data. The costly failure mode is silently continuing.
- **Fail loudly** — raise a clear, specific error (which layer, what was violated) so it's debuggable, not a swallowed exception that turns into a mysterious downstream corruption.
- **Never silently coerce bad input into "something"** (e.g. defaulting an unparseable number to 0). That's the classic hidden bug — surface it instead. (Echoes the kernel's distrust of silent int/double coercion.)

## Test the layers, don't just add them
For each layer, add a test that feeds input which that specific layer must catch, and confirm it's rejected safely (`test-driven-development` + `runVerification`). Also test that a value which passes an outer layer but violates an inner invariant is still caught — that proves the layers are actually independent and not redundant.

## Balance — don't gold-plate
Defense in depth is not "validate the same thing ten times." Layers must be *independent and meaningful*. Redundant identical checks add cost and false confidence without catching anything new. Aim for a small number of genuinely different safeguards positioned where a miss hurts. For low-stakes internal data with a trusted producer, one good check may be plenty — reserve the layering for real trust boundaries and costly failures.

## HARD rules
- Every trust boundary where a miss is costly gets **at least two independent** safeguards.
- **Fail closed and loud** — never continue with unvalidated data, never swallow the error.
- **No silent coercion** of malformed input into a default value.
- **Canonicalize before you check** so alternate encodings can't slip past.
- Each layer gets a test proving it catches what it's there for.

## Anti-patterns
- "It's validated at the API layer" as the sole defense — until a new internal caller skips that layer.
- Blacklists that miss the case you didn't think of.
- Catch-all `try/except: pass` that turns a caught violation into silent corruption.
- Defaulting malformed input to 0/""/null and marching on.
- Ten copies of the same check masquerading as depth.
