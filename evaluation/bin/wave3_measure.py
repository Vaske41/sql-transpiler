#!/usr/bin/env python3
"""Wave-3 coverage gate: remeasure PARROT-Diverse against the pinned Wave-2 baseline."""
from __future__ import annotations
import argparse, collections, csv, math, os, subprocess, sys, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = {"SUCCESS": 966, "PARSE": 309, "REFUSED": 151}
COHORT = 1426
TARGET = math.ceil(0.85 * COHORT)          # 1213
JAR = ROOT / "target" / "sqltranslate.jar"
CASES = ROOT / "evaluation" / "datasets" / "parrot" / "cases"
PREV = ROOT / "evaluation" / "results-local" / "parrot-wave2-latest.csv"
OUT = ROOT / "evaluation" / "results-local" / "parrot-wave3-latest.csv"
REMAINDER = ROOT / "evaluation" / "results-local" / "parrot-wave3-remainder.md"
FIELDS = ["system", "case_id", "source", "target", "outcome", "exit_or_status",
          "syntactic_valid", "semantic_equiv", "determinism_ok", "latency_ms_median", "notes"]


def classify(rc: int, stderr: str) -> str:
    # §1 defines the outcomes by exit code; use it. Sniffing stderr miscounts a PARSE
    # error whose message happens to contain "not supported", and the PARSE/REFUSED
    # split is exactly what Task 25 publishes.
    if rc == 0:
        return "SUCCESS"
    if rc == 2:
        return "REFUSED"
    if rc == 1:
        return "PARSE"
    raise RuntimeError(f"unexpected exit {rc}: {(stderr or '')[:200]}")


def run_one(row: dict) -> dict:
    inp = CASES / row["case_id"] / f"input.{row['source']}.sql"
    started = time.time()
    proc = subprocess.run(
        ["java", "-jar", str(JAR), "-f", row["source"], "-t", row["target"], "--in", str(inp)],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    return {"system": "sqltranslate", "case_id": row["case_id"], "source": row["source"],
            "target": row["target"], "outcome": classify(proc.returncode, proc.stderr),
            "exit_or_status": str(proc.returncode), "syntactic_valid": "n/a",
            "semantic_equiv": "n/a", "determinism_ok": "n/a",
            "latency_ms_median": str(int((time.time() - started) * 1000)),
            "notes": " ".join((proc.stderr or "").split())[:180]}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fast", action="store_true",
                    help="replay only prior non-SUCCESS rows; keep prior SUCCESS rows")
    args = ap.parse_args(argv)

    prev = [r for r in csv.DictReader(PREV.open(encoding="utf-8-sig"))
            if r["system"] == "sqltranslate"]
    if args.fast:
        kept = [r for r in prev if r["outcome"] == "SUCCESS"]
        redo = [r for r in prev if r["outcome"] != "SUCCESS"]
    else:
        kept, redo = [], prev

    with ThreadPoolExecutor(max_workers=8) as pool:
        fresh = list(pool.map(run_one, redo))
    rows = kept + fresh

    counts = collections.Counter(r["outcome"] for r in rows)
    with OUT.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS)
        writer.writeheader()
        for r in rows:
            writer.writerow({k: r.get(k, "") for k in FIELDS})

    success = counts["SUCCESS"]
    mode = "fast (non-SUCCESS replay)" if args.fast else "full sweep"
    REMAINDER.write_text(
        f"# Wave 3 PARROT-Diverse remainder ({mode})\n\n"
        f"Coverage SUCCESS = parse -> rules -> print exit 0. **Not** AccEX.\n\n"
        f"- sqltranslate: SUCCESS={success}, PARSE={counts['PARSE']}, "
        f"REFUSED={counts['REFUSED']} (n={len(rows)})\n"
        f"- Wave-2 pinned baseline: SUCCESS={BASELINE['SUCCESS']}\n"
        f"- Delta: {success - BASELINE['SUCCESS']:+d}\n"
        f"- 85% bar: >={TARGET}; {'MET' if success >= TARGET else f'need {TARGET - success} more'}\n",
        encoding="utf-8")

    print(f"SUCCESS={success} PARSE={counts['PARSE']} REFUSED={counts['REFUSED']} "
          f"n={len(rows)} ({success / max(len(rows), 1):.2%}) delta={success - BASELINE['SUCCESS']:+d}")
    if len(rows) != COHORT:
        print(f"error: measured {len(rows)} rows != cohort {COHORT}", file=sys.stderr)
        return 2
    if success < BASELINE["SUCCESS"]:
        print(f"error: REGRESSION {success} < baseline {BASELINE['SUCCESS']}", file=sys.stderr)
        return 3
    return 0 if success >= TARGET else 1


if __name__ == "__main__":
    raise SystemExit(main())
