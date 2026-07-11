/** 子序列模糊匹配打分：-1 不匹配；连续命中 +4、路径段/词首命中 +3、普通命中 +1（越大越好）。 */
export function fuzzyScore(query: string, target: string): number {
  if (!query) return 0;
  const q = query.toLowerCase();
  const t = target.toLowerCase();
  let score = 0;
  let ti = 0;
  let prevHit = -2;
  for (let qi = 0; qi < q.length; qi++) {
    const idx = t.indexOf(q[qi], ti);
    if (idx < 0) return -1;
    const prevChar = idx > 0 ? target[idx - 1] : "/";
    if (idx === prevHit + 1) score += 4;
    else if (prevChar === "/" || prevChar === "." || prevChar === "_" || prevChar === "-") score += 3;
    else score += 1;
    prevHit = idx;
    ti = idx + 1;
  }
  return score;
}
