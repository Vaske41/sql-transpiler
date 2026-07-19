#!/usr/bin/env python3
"""Materialize PARROT-Diverse JSONL pairs into case directories.

Writes only:
  cases/<case_id>/input.<source>.sql
  cases/<case_id>/target.txt

Does not write expected.* or any gold SQL file (gold stays in JSONL; C2/I3).

Always replaces the output tree so rematerialize cannot leave orphan cases
that would diverge from manifest.pairCount.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

_REPO_EVAL = Path(__file__).resolve().parent.parent
_DEFAULT_PAIRS = _REPO_EVAL / "datasets" / "parrot" / "pairs.jsonl"
_DEFAULT_OUT = _REPO_EVAL / "datasets" / "parrot" / "cases"


def materialize_file(pairs_path: Path, cases_root: Path) -> int:
    """Replace cases_root entirely, then write input + target.txt per JSONL row."""
    if cases_root.exists():
        shutil.rmtree(cases_root)
    cases_root.mkdir(parents=True, exist_ok=True)
    count = 0
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


def check_against_manifest(pairs_path: Path, count: int, manifest_path: Path | None) -> int:
    """Return 0 ok, 2 on mismatch. Skip non-pairs.jsonl (e.g. smoke)."""
    if pairs_path.name != "pairs.jsonl":
        return 0
    path = manifest_path if manifest_path is not None else pairs_path.parent / "manifest.json"
    if not path.is_file():
        return 0
    try:
        expected = json.loads(path.read_text(encoding="utf-8"))["pairCount"]
    except (OSError, KeyError, json.JSONDecodeError, TypeError) as e:
        sys.stderr.write(f"error: cannot read pairCount from {path}: {e}\n")
        return 2
    if count != expected:
        sys.stderr.write(
            f"error: materialized {count} cases != manifest pairCount {expected} ({path})\n"
        )
        return 2
    return 0


def main(argv: list[str] | None = None) -> int:
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
    parser.add_argument(
        "--manifest",
        type=Path,
        default=None,
        help="Optional manifest.json for pairCount check (default: sibling of --pairs)",
    )
    args = parser.parse_args(argv)
    if not args.pairs.is_file():
        sys.stderr.write(f"error: missing {args.pairs} — run fetch_parrot.py first\n")
        return 1
    n = materialize_file(args.pairs, args.out)
    print(f"Materialized {n} cases -> {args.out}")
    return check_against_manifest(args.pairs, n, args.manifest)


if __name__ == "__main__":
    raise SystemExit(main())
