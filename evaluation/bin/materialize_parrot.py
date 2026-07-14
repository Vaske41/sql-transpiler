#!/usr/bin/env python3
"""Materialize PARROT-Diverse JSONL pairs into case directories.

Writes only:
  cases/<case_id>/input.<source>.sql
  cases/<case_id>/target.txt

Does not write expected.* or any gold SQL file (gold stays in JSONL; C2/I3).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

_REPO_EVAL = Path(__file__).resolve().parent.parent
_DEFAULT_PAIRS = _REPO_EVAL / "datasets" / "parrot" / "pairs.jsonl"
_DEFAULT_OUT = _REPO_EVAL / "datasets" / "parrot" / "cases"


def materialize_file(pairs_path: Path, cases_root: Path) -> int:
    count = 0
    cases_root.mkdir(parents=True, exist_ok=True)
    with pairs_path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            case_dir = cases_root / obj["case_id"]
            case_dir.mkdir(parents=True, exist_ok=True)
            (case_dir / f"input.{obj['source']}.sql").write_text(
                obj["source_sql"], encoding="utf-8"
            )
            (case_dir / "target.txt").write_text(obj["target"] + "\n", encoding="utf-8")
            # gold_sql intentionally not written to disk in v1 (C2)
            count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Materialize PARROT-Diverse pairs.jsonl into cases/ (input + target only)."
    )
    parser.add_argument(
        "--pairs",
        type=Path,
        default=_DEFAULT_PAIRS,
        help=f"JSONL pairs path (default: {_DEFAULT_PAIRS})",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=_DEFAULT_OUT,
        help=f"Cases output root (default: {_DEFAULT_OUT})",
    )
    args = parser.parse_args()
    n = materialize_file(args.pairs, args.out)
    print(f"Materialized {n} cases -> {args.out}")


if __name__ == "__main__":
    main()
