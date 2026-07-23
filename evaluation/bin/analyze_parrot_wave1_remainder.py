#!/usr/bin/env python3
"""Stderr remainder analysis for Wave 1 PARROT-Diverse remeasure."""
import csv
import collections
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CSV = ROOT / "evaluation/results-local/parrot-wave1-remeasure.csv"
OUT = ROOT / "evaluation/results-local/parrot-wave1-remainder-analysis.md"


def main() -> None:
    rows = [r for r in csv.DictReader(CSV.open(encoding="utf-8")) if r["system"] == "sqltranslate"]
    counts = collections.Counter(r["outcome"] for r in rows)
    sig = collections.Counter()
    for r in rows:
        if r["outcome"] not in ("PARSE", "REFUSED"):
            continue
        notes = r.get("notes") or ""
        m = re.search(r"stderr=([^;]*)", notes)
        frag = (m.group(1).strip() if m else notes.strip())[:180]
        frag = re.sub(r"line \d+", "line N", frag)
        frag = re.sub(r"column \d+", "column N", frag)
        sig[(r["outcome"], frag or "(empty)")] += 1

    buckets = collections.Counter()
    for r in rows:
        oc = r["outcome"]
        if oc == "SUCCESS":
            buckets["SUCCESS"] += 1
            continue
        notes = (r.get("notes") or "").lower()
        if "recursive cte" in notes:
            buckets["REFUSED_recursive_CTE"] += 1
        elif "window frame" in notes:
            buckets["REFUSED_window_frame"] += 1
        elif "mismatched input 'with'" in notes:
            buckets["PARSE_still_WITH"] += 1
        elif "mismatched input 'over'" in notes:
            buckets["PARSE_still_OVER"] += 1
        elif "token recognition error at: ':'" in notes or "token recognition error at: \":\"" in notes:
            buckets["PARSE_still_colon"] += 1
        elif "mismatched input 'fetch'" in notes:
            buckets["PARSE_still_FETCH"] += 1
        elif any(x in notes for x in ("json", "->>", "#>", "jsonb", "array_agg", "unnest")):
            buckets["OTHER_json_array"] += 1
        elif oc == "REFUSED":
            buckets["REFUSED_other"] += 1
        else:
            buckets["PARSE_other"] += 1

    lines = []
    lines.append("# Wave 1 PARROT-Diverse remainder analysis")
    lines.append("")
    lines.append("Coverage SUCCESS = parse→rules→print exit 0. **Not** AccEX / gold_sql / catalog-semantic.")
    lines.append("")
    lines.append("## Headline")
    lines.append("")
    s = counts["SUCCESS"]
    lines.append(f"- sqltranslate: SUCCESS={s}, PARSE={counts['PARSE']}, REFUSED={counts.get('REFUSED', 0)} (n={len(rows)})")
    lines.append(f"- Baseline: SUCCESS=345, PARSE=1057, REFUSED=24")
    lines.append(f"- Delta SUCCESS: **+{s - 345}** ({345} → {s}; {100.0 * s / 1426:.2f}%)")
    lines.append(f"- Wave 1 bar (~65%): need ≥927; **missed** (got {s})")
    lines.append("")
    lines.append("## Coarse remainder buckets (non-SUCCESS)")
    lines.append("")
    lines.append("| Bucket | Count |")
    lines.append("|---|---:|")
    for k, v in buckets.most_common():
        if k == "SUCCESS":
            continue
        lines.append(f"| `{k}` | {v} |")
    lines.append("")
    lines.append("## Top stderr signatures (PARSE/REFUSED)")
    lines.append("")
    lines.append("| Count | Outcome | stderr fragment |")
    lines.append("|---:|---|---|")
    for (oc, fr), c in sig.most_common(40):
        safe = fr.replace("|", "\\|")
        lines.append(f"| {c} | {oc} | `{safe}` |")
    lines.append("")
    lines.append("## Thesis reading")
    lines.append("")
    lines.append(
        "Wave 1 shipped Extension Queue items 3–5 plus PG FETCH/`::`, raising coverage SUCCESS "
        f"by +{s - 345}, but landed at **{100.0 * s / 1426:.1f}%**, below the Option B ~65% bar. "
        "Do **not** silently revise the bar. Remainder mixes honest Wave 1 refusals "
        "(recursive CTE, window frames) with a still-large PARSE long-tail (JSON/routines/DDL/other) "
        "outside Wave 1 scope — publish this as the finding; further coverage needs a scoped Wave 2, "
        "not a lowered Wave 1 target."
    )
    lines.append("")
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {OUT}")
    print("counts", dict(counts))
    print("buckets", dict(buckets))


if __name__ == "__main__":
    main()
