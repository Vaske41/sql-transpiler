#!/usr/bin/env python3
"""One-shot Hugging Face PARROT-Diverse fetch → filtered JSONL + manifest.

Downloads weizhoudb/PARROT split `test`, emits ordered dialect pairs among
non-empty mysql|postgres|tsql cells on the same hf_row, and writes:
  evaluation/datasets/parrot/pairs.jsonl   (gitignored)
  evaluation/datasets/parrot/manifest.json (committed)

No network in unit tests; live fetch is local/manual only.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from datetime import datetime, timezone
from itertools import permutations
from pathlib import Path
from typing import Any

HF_DATASET = "weizhoudb/PARROT"
HF_SPLIT = "test"
OUR_COLUMNS = ("mysql", "postgres", "tsql")
FILTER_VERSION = "diverse-dialect-v1"

_COLUMN_TO_CLI = {
    "mysql": "mysql",
    "postgres": "postgresql",
    "tsql": "tsql",
}

_FILTER_RULES = [
    "HF wide row; non-empty cells among mysql|postgres|tsql on same hf_row",
    "emit all ordered pairs among those cells (parallel-rendering contract)",
    "no v1 grammar allowlist",
    "not the NeurIPS 598-pair curated core",
    "Phase 7 outcomes only; gold_sql not scored in v1",
]

_REPO_EVAL = Path(__file__).resolve().parent.parent
_OUT_DIR = _REPO_EVAL / "datasets" / "parrot"
_REQUIREMENTS = Path(__file__).resolve().parent / "requirements-datasets.txt"


def normalize_dialect(column_or_name: str) -> str | None:
    """Map HF column / dialect token to CLI name, or None if unsupported."""
    key = (column_or_name or "").strip().lower()
    if key in _COLUMN_TO_CLI:
        return _COLUMN_TO_CLI[key]
    if key == "postgresql":
        return "postgresql"
    return None


def _cell_nonempty(value: Any) -> bool:
    if value is None:
        return False
    text = str(value).strip()
    return bool(text)


def iter_pairs(row: dict[str, Any], row_index: int) -> list[dict[str, Any]]:
    """Emit all ordered pairs among non-empty OUR dialect columns on one HF row."""
    present: list[tuple[str, str]] = []
    for col in OUR_COLUMNS:
        raw = row.get(col)
        if not _cell_nonempty(raw):
            continue
        cli = normalize_dialect(col)
        if cli is None:
            continue
        present.append((cli, str(raw).strip()))

    hf_id = str(row.get("id") or "unknown")
    pairs: list[dict[str, Any]] = []
    for (src_cli, src_sql), (tgt_cli, tgt_sql) in permutations(present, 2):
        case_id = f"{row_index:05d}-{hf_id}-{src_cli}-to-{tgt_cli}"
        pairs.append(
            {
                "case_id": case_id,
                "hf_row": row_index,
                "hf_id": hf_id,
                "source": src_cli,
                "target": tgt_cli,
                "source_sql": src_sql,
                "gold_sql": tgt_sql,
            }
        )
    return pairs


def _read_datasets_pin() -> str:
    text = _REQUIREMENTS.read_text(encoding="utf-8")
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"datasets\s*==\s*(\S+)", line)
        if m:
            return f"datasets=={m.group(1)}"
        if line.startswith("datasets"):
            return line
    raise RuntimeError(f"datasets pin not found in {_REQUIREMENTS}")


def build_manifest(
    *,
    hf_rows: int,
    pair_count: int,
    pair_count_by_hf_id: dict[str, int],
    fetched_utc: str,
    datasets_package: str,
) -> dict[str, Any]:
    return {
        "dataset": HF_DATASET,
        "split": HF_SPLIT,
        "variantLabel": "PARROT-Diverse-filtered-mysql-postgresql-tsql",
        "filterVersion": FILTER_VERSION,
        "filterRules": list(_FILTER_RULES),
        "datasetsPackage": datasets_package,
        "fetchedUtc": fetched_utc,
        "hfRows": hf_rows,
        "pairCount": pair_count,
        "pairCountByHfId": dict(sorted(pair_count_by_hf_id.items())),
        "dialects": ["mysql", "postgresql", "tsql"],
    }


def fetch_and_write(out_dir: Path | None = None) -> dict[str, Any]:
    """Download HF split and write pairs.jsonl + manifest.json. Requires network."""
    from datasets import load_dataset

    target = out_dir or _OUT_DIR
    target.mkdir(parents=True, exist_ok=True)
    pairs_path = target / "pairs.jsonl"
    manifest_path = target / "manifest.json"

    ds = load_dataset(HF_DATASET, split=HF_SPLIT)
    datasets_package = _read_datasets_pin()
    fetched_utc = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    pair_count_by_hf_id: Counter[str] = Counter()
    pair_count = 0

    with pairs_path.open("w", encoding="utf-8", newline="\n") as fh:
        for row_index, row in enumerate(ds):
            row_dict = dict(row)
            for pair in iter_pairs(row_dict, row_index):
                fh.write(json.dumps(pair, ensure_ascii=False) + "\n")
                pair_count += 1
                pair_count_by_hf_id[pair["hf_id"]] += 1

    manifest = build_manifest(
        hf_rows=len(ds),
        pair_count=pair_count,
        pair_count_by_hf_id=dict(pair_count_by_hf_id),
        fetched_utc=fetched_utc,
        datasets_package=datasets_package,
    )
    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> None:
    manifest = fetch_and_write()
    print(
        f"Wrote {manifest['pairCount']} pairs from {manifest['hfRows']} HF rows "
        f"({manifest['filterVersion']}) -> {_OUT_DIR / 'pairs.jsonl'}"
    )
    print(f"Manifest -> {_OUT_DIR / 'manifest.json'}")


if __name__ == "__main__":
    main()
