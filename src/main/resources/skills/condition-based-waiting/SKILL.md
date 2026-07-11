---
name: condition-based-waiting
description: 每当你需要等某样东西就绪后才能继续时使用——等服务器开始监听、等构建或后台任务完成、等文件出现、等端口打开、等健康检查转绿、等异步结果返回。触发点：当你发现自己正打算 sleep 固定的秒数、指望"应该够了"时。本 skill 教你用"带超时的轮询直到条件满足"来替代固定 sleep——固定 sleep 正是自动化脆弱、缓慢的经典根源。如果某样东西是真的坏了而不只是慢，改用 systematic-debugging。
---

# Condition-Based Waiting

Wait for the **condition**, not for the **clock**. A fixed `sleep 5` is a bug generator: too short and it flakes intermittently (the thing wasn't ready), too long and every run wastes time. The correct pattern is: poll for the actual readiness signal, at an interval, with an overall timeout.

## The core pattern

```
deadline = now + TIMEOUT
until <condition is true>:
    if now > deadline: FAIL loudly with diagnostics
    <check the condition cheaply>
    sleep SHORT_INTERVAL   # e.g. 0.5–2s, small relative to timeout
proceed
```

Three parameters, chosen deliberately:
- **Condition** — a cheap, reliable check for actual readiness (see below). This is the important part.
- **Interval** — short enough to be responsive, long enough not to hammer (0.5s–2s typical).
- **Timeout** — generous enough for a slow-but-normal case, finite so you never hang forever.

## Choosing the right condition — check the real thing
Poll the signal that actually means "ready," not a proxy:
- **Server up:** the port accepts a connection / a health endpoint returns 200 — NOT "the process was launched."
- **Job done:** the output file exists AND is complete (e.g. a sentinel marker, or size stable across two polls), a "DONE" line in the log, or the process has exited 0 — NOT "enough time has probably passed."
- **File ready:** it exists AND isn't still being written (poll size/mtime twice and compare; or wait for an atomic rename).
- **Async result:** the status flips to a terminal state — NOT a guessed duration.

A weak condition (e.g. "log file exists") that's true before the thing is actually ready is as bad as a fixed sleep — pick a condition that's only true when you can really proceed.

## Doing it in this kernel

- For a background process you started (`bash` with `run_in_background`, or a launched server), poll with a `bash` loop that exits when the condition holds. Prefer a self-contained loop in ONE `bash` call — e.g. a `while`/`until` with `curl`/`nc`/test-for-file and an iteration cap — so you don't spin turns re-checking manually. Foreground fixed `sleep` is discouraged (and may be blocked); use the loop.
- If a `Monitor`-style waiting tool is available, prefer it for waiting on a condition — it's built for exactly this (fetch its schema via ToolSearch if you need it).
- Always cap iterations so the loop can't run forever, and on timeout **print diagnostics** (last log lines, the failing check's output) so the failure is debuggable — not a bare "timed out."

## HARD rules
- **No bare fixed sleeps as a readiness mechanism.** `sleep N && proceed` with no condition check is banned. (A tiny sleep as the *interval inside* a polling loop is fine — that's the interval, not the wait.)
- **Every wait has a timeout.** Unbounded waits hang the whole task. Choose a finite, generous cap.
- **Timeout must fail loudly with context**, never silently continue as if ready — proceeding on a not-ready system causes a confusing downstream failure far from the real cause.
- **Poll the real readiness signal**, not a proxy that's true too early.

## Anti-patterns
- `sleep 10` "to let the server start," then hitting it and getting connection-refused on slow machines.
- Waiting on "process launched" instead of "port listening."
- A polling loop with no timeout that hangs forever when the thing never comes up.
- Catching the readiness of a half-written file (didn't confirm the write finished).
- Swallowing the timeout and continuing — turning a clear "startup failed" into a mysterious later crash.

If polling reveals the thing genuinely never becomes ready (not just slow), stop waiting and load `systematic-debugging` — you have a real failure, not a timing problem.
