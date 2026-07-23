#!/usr/bin/env python3
"""Compare Wave 1 sqltranslate outcomes to the frozen baseline counts."""
import csv
import collections
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = {"SUCCESS": 345, "PARSE": 1057, "REFUSED": 24}
# README default: target/evaluation/summary/parrot-diverse-latest.csv
SUMMARY = ROOT / "target/evaluation/summary/parrot-diverse-latest.csv"
COHORT = 1426


def main() -> int:
    cp_file = ROOT / "target/eval-cp.txt"
    subprocess.check_call(
        [str(ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")),
         "-q", "-DskipTests", "package", "test-compile",
         "dependency:build-classpath",
         f"-Dmdep.outputFile={cp_file}"],
        cwd=ROOT,
    )
    cp = f"{ROOT / 'target/test-classes'};{ROOT / 'target/classes'};{cp_file.read_text(encoding='utf-8').strip()}"
    if os.name != "nt":
        cp = cp.replace(";", ":")
    env = os.environ.copy()
    # Bulk coverage sweep: one jar run per case (not latency-median protocol).
    env.setdefault("EVAL_FAST", "1")
    # Wave 1 gate: sqltranslate only (omit --sqlglot). Optional SQLGlot compare is a separate run.
    subprocess.check_call(
        ["java", "-cp", cp,
         "rs.etf.sqltranslator.evaluation.EvaluationMain",
         "--corpus", "parrot-diverse"],
        cwd=ROOT,
        env=env,
    )
    rows = [r for r in csv.DictReader(SUMMARY.open(encoding="utf-8"))
            if r["system"] == "sqltranslate"]
    counts = collections.Counter(r["outcome"] for r in rows)
    success = counts["SUCCESS"]
    target = 0.65 * COHORT
    rate = 100.0 * success / COHORT
    print("sqltranslate outcomes:", dict(counts))
    print(f"cohort rows: {sum(counts.values())} (expected {COHORT})")
    print("delta SUCCESS:", success - BASELINE["SUCCESS"])
    print("delta PARSE:", counts["PARSE"] - BASELINE["PARSE"])
    print("delta REFUSED:", counts.get("REFUSED", 0) - BASELINE["REFUSED"])
    print(f"Wave 1 bar (~65%): need SUCCESS >= {target:.0f}; got {success} ({rate:.2f}%)")
    local = ROOT / "evaluation/results-local/parrot-wave1-remeasure.csv"
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_bytes(SUMMARY.read_bytes())
    print(f"Wrote {local}")
    # Caption: parse→rules→print coverage SUCCESS (exit 0), not AccEX.
    return 0 if success >= target else 1


if __name__ == "__main__":
    raise SystemExit(main())
