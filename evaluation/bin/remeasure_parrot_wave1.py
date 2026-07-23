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
LOCAL = ROOT / "evaluation/results-local/parrot-wave1-remeasure.csv"
COHORT = 1426
BAR = 0.65 * COHORT


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
    # Outcome sweep: EVAL_FAST=1 → single local latency run (coverage gate, not latency thesis).
    env.setdefault("EVAL_FAST", "1")
    # Wave 1 gate: sqltranslate only (omit --sqlglot). Optional SQLGlot compare is a separate run.
    cmd = [
        "java", "-cp", cp,
        "rs.etf.sqltranslator.evaluation.EvaluationMain",
        "--corpus", "parrot-diverse",
    ]
    # Prefer writing the gate CSV directly when EvaluationMain supports --csv.
    LOCAL.parent.mkdir(parents=True, exist_ok=True)
    cmd.extend(["--csv", str(LOCAL)])
    subprocess.check_call(cmd, cwd=ROOT, env=env)
    src = LOCAL if LOCAL.exists() else SUMMARY
    if not src.exists():
        print(f"missing summary CSV at {LOCAL} and {SUMMARY}", file=sys.stderr)
        return 2
    if src != LOCAL:
        LOCAL.write_bytes(src.read_bytes())
    if src != SUMMARY:
        SUMMARY.parent.mkdir(parents=True, exist_ok=True)
        SUMMARY.write_bytes(src.read_bytes())
    rows = [r for r in csv.DictReader(LOCAL.open(encoding="utf-8"))
            if r["system"] == "sqltranslate"]
    counts = collections.Counter(r["outcome"] for r in rows)
    n = sum(counts.values())
    success = counts["SUCCESS"]
    rate = 100.0 * success / COHORT
    print("sqltranslate outcomes:", dict(counts))
    print(f"cohort rows: {n} (expected {COHORT})")
    print("baseline:", BASELINE)
    print("delta SUCCESS:", success - BASELINE["SUCCESS"])
    print("delta PARSE:", counts["PARSE"] - BASELINE["PARSE"])
    print("delta REFUSED:", counts.get("REFUSED", 0) - BASELINE["REFUSED"])
    print(f"Wave 1 bar (~65%): need SUCCESS >= {BAR:.0f}; got {success} ({rate:.2f}%)")
    print(f"Wrote {LOCAL}")
    # Caption: parse→rules→print coverage SUCCESS (exit 0), not AccEX / gold_sql / catalog-semantic.
    return 0 if success >= BAR else 1


if __name__ == "__main__":
    raise SystemExit(main())
