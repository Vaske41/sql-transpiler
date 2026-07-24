#!/usr/bin/env python3
"""Wave-2 coverage gate: remeasure PARROT-Diverse and compare to the pinned baseline.

Exit 0 iff sqltranslate SUCCESS >= TARGET (70% of COHORT). Every cluster boundary
re-measure step calls this script.
"""
from __future__ import annotations

import collections
import csv
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = {"SUCCESS": 655, "PARSE": 665, "REFUSED": 106}
COHORT = 1426
TARGET = 998  # ceil(0.70 * 1426)
SUMMARY = ROOT / "target/evaluation/summary/parrot-diverse-latest.csv"
LOCAL_CSV = ROOT / "evaluation/results-local/parrot-wave2-latest.csv"
REMAINDER_MD = ROOT / "evaluation/results-local/parrot-wave2-remainder.md"


def _build_classpath() -> str:
    cp_file = ROOT / "target/eval-cp.txt"
    subprocess.check_call(
        [
            str(ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")),
            "-q",
            "-DskipTests",
            "package",
            "test-compile",
            "dependency:build-classpath",
            f"-Dmdep.outputFile={cp_file}",
        ],
        cwd=ROOT,
    )
    cp = (
        f"{ROOT / 'target/test-classes'};"
        f"{ROOT / 'target/classes'};"
        f"{cp_file.read_text(encoding='utf-8').strip()}"
    )
    if os.name != "nt":
        cp = cp.replace(";", ":")
    return cp


def _run_eval(cp: str) -> None:
    env = os.environ.copy()
    env.setdefault("EVAL_FAST", "1")
    subprocess.check_call(
        [
            "java",
            "-cp",
            cp,
            "rs.etf.sqltranslator.evaluation.EvaluationMain",
            "--corpus",
            "parrot-diverse",
        ],
        cwd=ROOT,
        env=env,
    )


def _sqltranslate_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as f:
        return [r for r in csv.DictReader(f) if r["system"] == "sqltranslate"]


def _bucket_remainder(rows: list[dict[str, str]]) -> collections.Counter:
    """Reuse Wave-1 remainder bucketing so every wave boundary sees what is left."""
    buckets: collections.Counter = collections.Counter()
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
        elif "token recognition error at: ':'" in notes or 'token recognition error at: ":"' in notes:
            buckets["PARSE_still_colon"] += 1
        elif "mismatched input 'fetch'" in notes:
            buckets["PARSE_still_FETCH"] += 1
        elif any(x in notes for x in ("json", "->>", "#>", "jsonb", "array_agg", "unnest")):
            buckets["OTHER_json_array"] += 1
        elif oc == "REFUSED":
            buckets["REFUSED_other"] += 1
        else:
            buckets["PARSE_other"] += 1
    return buckets


def _stderr_signatures(rows: list[dict[str, str]], top: int = 25) -> list[tuple[tuple[str, str], int]]:
    sig: collections.Counter = collections.Counter()
    for r in rows:
        if r["outcome"] not in ("PARSE", "REFUSED"):
            continue
        notes = r.get("notes") or ""
        m = re.search(r"stderr=([^;]*)", notes)
        frag = (m.group(1).strip() if m else notes.strip())[:180]
        frag = re.sub(r"line \d+", "line N", frag)
        frag = re.sub(r"column \d+", "column N", frag)
        sig[(r["outcome"], frag or "(empty)")] += 1
    return sig.most_common(top)


def _write_remainder(rows: list[dict[str, str]], counts: collections.Counter) -> None:
    buckets = _bucket_remainder(rows)
    success = counts["SUCCESS"]
    parse = counts.get("PARSE", 0)
    refused = counts.get("REFUSED", 0)
    rate = 100.0 * success / COHORT
    need = max(0, TARGET - success)
    lines = [
        "# Wave 2 PARROT-Diverse remainder analysis",
        "",
        "Coverage SUCCESS = parse→rules→print exit 0. **Not** AccEX / gold_sql / catalog-semantic.",
        "",
        "## Headline",
        "",
        f"- sqltranslate: SUCCESS={success}, PARSE={parse}, REFUSED={refused} (n={len(rows)})",
        f"- Pinned Wave-1 baseline: SUCCESS={BASELINE['SUCCESS']}, PARSE={BASELINE['PARSE']}, "
        f"REFUSED={BASELINE['REFUSED']}",
        f"- Delta SUCCESS: **{success - BASELINE['SUCCESS']:+d}** "
        f"({BASELINE['SUCCESS']} → {success}; {rate:.2f}%)",
        f"- Wave 2 bar (70%): need ≥{TARGET}; "
        + ("**met**" if success >= TARGET else f"**need {need} more**"),
        "",
        "## Coarse remainder buckets (non-SUCCESS)",
        "",
        "| Bucket | Count |",
        "|---|---:|",
    ]
    for k, v in buckets.most_common():
        if k == "SUCCESS":
            continue
        lines.append(f"| `{k}` | {v} |")
    lines.extend(
        [
            "",
            "## Top stderr signatures (PARSE/REFUSED)",
            "",
            "| Count | Outcome | stderr fragment |",
            "|---:|---|---|",
        ]
    )
    for (oc, fr), c in _stderr_signatures(rows):
        safe = fr.replace("|", "\\|")
        lines.append(f"| {c} | {oc} | `{safe}` |")
    lines.append("")
    REMAINDER_MD.parent.mkdir(parents=True, exist_ok=True)
    REMAINDER_MD.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {REMAINDER_MD}")
    print("remainder buckets:", {k: v for k, v in buckets.items() if k != "SUCCESS"})


def main() -> int:
    cp = _build_classpath()
    _run_eval(cp)
    if not SUMMARY.is_file():
        print(f"missing summary CSV: {SUMMARY}", file=sys.stderr)
        return 2

    rows = _sqltranslate_rows(SUMMARY)
    counts = collections.Counter(r["outcome"] for r in rows)
    success = counts["SUCCESS"]
    parse = counts.get("PARSE", 0)
    refused = counts.get("REFUSED", 0)
    rate = 100.0 * success / COHORT
    need = max(0, TARGET - success)

    print("sqltranslate outcomes:", dict(counts))
    print(f"cohort rows: {sum(counts.values())} (expected {COHORT})")
    print("delta SUCCESS:", success - BASELINE["SUCCESS"])
    print("delta PARSE:", parse - BASELINE["PARSE"])
    print("delta REFUSED:", refused - BASELINE["REFUSED"])
    print(f"rate: {rate:.2f}%  need {need}  (target SUCCESS >= {TARGET})")

    if success < BASELINE["SUCCESS"]:
        print(
            f"REGRESSION: SUCCESS {success} < baseline {BASELINE['SUCCESS']} — "
            "stop and investigate before further Wave-2 work",
            file=sys.stderr,
        )

    LOCAL_CSV.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SUMMARY, LOCAL_CSV)
    print(f"Wrote {LOCAL_CSV}")
    _write_remainder(rows, counts)

    return 0 if success >= TARGET else 1


if __name__ == "__main__":
    raise SystemExit(main())
