---
name: research
description: 当某个事实性问题需要借助当前网络、给出一个快速且有来源的答案时使用——单个事实、版本号、一个定义、"库 X 支不支持 Y"、当前价格、简短的"大家一般怎么做 Z"。触发场景：用户问的东西不该凭过时的记忆回答，但又不至于动用一份完整的多来源报告。需要联网能力（WebSearch/WebFetch）。如果问题是大型横向对比、是有真实风险的决策，或需要跨多个来源做事实核查，改用 deep-research。如果单靠本地仓库就能答，用 grep/glob/read，而不是本 skill。
---

# Research (lightweight)

Fast, honest, sourced answers. One search-and-fetch loop, maybe two. The goal is a correct answer with a real link — not a report. Optimize for latency, but never fabricate a source to be fast.

## When this is the right tool
- A specific fact that may have changed since your training cutoff (latest version, current price, release date, whether an API exists).
- A definition or "how is this normally done" where a current authoritative page settles it.
- A quick sanity check before you act ("is this the right flag/config for the current version?").

If mid-way you realize the question is actually a multi-source decision or needs adversarial fact-checking, STOP and escalate: call the `Skill` tool to load `deep-research`. Don't half-do a deep question here.

## The loop

1. **Search.** Use `WebSearch` with a precise query. Include the current year or "latest" when recency matters. Read the result titles/URLs and pick the 1-3 most authoritative — prefer official docs, the project's own site, vendor pages, and standards bodies over content-farm blogs and SEO listicles.

2. **Fetch.** `WebFetch` the top source(s) with a focused prompt that asks the exact question, e.g. "What is the current stable version of X and its release date? Quote the relevant line." Fetch a second source if the answer is decision-relevant or the first source is weak/undated.

3. **Verify enough.** For any claim the user will act on (a number, a version, a command, a price), confirm it appears on the fetched page — don't paraphrase from the search snippet alone, snippets are often stale or truncated. If the two sources you checked disagree, prefer the more primary/recent one and note the discrepancy in one line.

4. **Answer.** Give the answer directly in the first sentence. Then, if useful, one or two supporting lines. End with the source URL(s). Keep it tight.

## Discipline (HARD rules)
- **No web access → no answer dressed as research.** If `WebSearch`/`WebFetch` are off/unavailable, say you couldn't verify online and offer your best from-memory guess *explicitly labeled as unverified*. Never attach a citation to a memory-based claim.
- **Cite only what you fetched.** Every URL in your answer must be one you actually opened this session. No plausible-looking-but-unvisited links.
- **Fact vs. guess.** If part of the answer is inference and not on the page, label it ("the docs don't say, but likely…").
- **Date-stamp volatile facts.** For prices/versions/specs, note "as of <date on the page>" so the user knows freshness.
- **Don't over-fetch.** Two good sources beat six mediocre ones. This is the lightweight path — if you find yourself opening the fifth tab, you probably want `deep-research`.

## Output shape
> **<direct answer>.** <one or two supporting sentences if needed.> [as of <date>]
> Source: <url> (and <url2> if used)

That's it. No headings, no report structure — that's what `deep-research` is for.
