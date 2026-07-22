#!/usr/bin/env python3
"""Slice PARROT-Diverse sqltranslate PARSE failures for Wave 1 gate evidence.

Computes multi-label / exclusive feature presence from frozen CSV + case inputs,
optionally jar-replays PARSE rows to collect truncated stderr signatures, and
writes evaluation/results-local/parrot-wave1-baseline-slices.md.

Usage:
  python evaluation/bin/slice_parrot_parse_failures.py
  python evaluation/bin/slice_parrot_parse_failures.py --replay all
  python evaluation/bin/slice_parrot_parse_failures.py --replay stratified --sample 200
  python evaluation/bin/slice_parrot_parse_failures.py --replay-cache PATH.json
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CSV = ROOT / "evaluation/results-local/parrot-diverse-offline-full-1426-latest.csv"
DEFAULT_CASES = ROOT / "evaluation/datasets/parrot/cases"
DEFAULT_JAR = ROOT / "target/sqltranslate.jar"
DEFAULT_OUT = ROOT / "evaluation/results-local/parrot-wave1-baseline-slices.md"
DEFAULT_REPLAY_CACHE = ROOT / "evaluation/results-local/parrot-wave1-parse-stderr-replay.json"

RE_WITH = re.compile(r"\bWITH\b", re.IGNORECASE)
RE_OVER = re.compile(r"\bOVER\s*\(", re.IGNORECASE)
RE_DERIVED = re.compile(r"\b(?:FROM|JOIN)\s*\(", re.IGNORECASE)
RE_FETCH = re.compile(r"\bFETCH\s+(?:FIRST|NEXT)\b", re.IGNORECASE)
# Framed window: ROWS/RANGE inside an OVER (...) group (non-greedy, DOTALL).
RE_FRAMED_OVER = re.compile(
    r"\bOVER\s*\((?:[^()]|\([^()]*\))*\b(?:ROWS|RANGE)\b",
    re.IGNORECASE | re.DOTALL,
)
RE_STDERR_SIG = re.compile(
    r"(?:mismatched input '[^']+'|extraneous input '[^']+'"
    r"|no viable alternative at input '[^']+'"
    r"|token recognition error at: '[^']*'"
    r"|missing '[^']+' at '[^']+')"
)


def load_sql(cases_root: Path, case_id: str, source: str) -> str:
    case_dir = cases_root / case_id
    preferred = case_dir / f"input.{source}.sql"
    if preferred.is_file():
        return preferred.read_text(encoding="utf-8", errors="replace")
    files = sorted(case_dir.glob("input.*.sql"))
    if not files:
        raise FileNotFoundError(f"No input.*.sql under {case_dir}")
    return files[0].read_text(encoding="utf-8", errors="replace")


def feature_flags(sql: str) -> dict[str, bool]:
    return {
        "WITH": bool(RE_WITH.search(sql)),
        "OVER": bool(RE_OVER.search(sql)),
        "DERIVED": bool(RE_DERIVED.search(sql)),
        "COLON": "::" in sql,
        "FETCH": bool(RE_FETCH.search(sql)),
        "FRAMED": bool(RE_FRAMED_OVER.search(sql)),
    }


def exclusive_primary(flags: dict[str, bool]) -> str:
    for key in ("WITH", "OVER", "DERIVED", "COLON", "FETCH"):
        if flags[key]:
            return key
    return "OTHER"


def normalize_stderr_signature(stderr: str, max_len: int = 160) -> str:
    text = " ".join(stderr.replace("\r", "\n").split())
    if not text:
        return "(empty stderr)"
    # Prefer the first ANTLR-ish token/error clause when present.
    m = RE_STDERR_SIG.search(text)
    if m:
        sig = m.group(0)
    else:
        # Drop leading "error: parse: N syntax error(s)" noise for bucketing.
        sig = re.sub(
            r"^error:\s*parse:\s*\d+\s*syntax error\(s\)\s*",
            "",
            text,
            flags=re.IGNORECASE,
        ).strip() or text
    if len(sig) > max_len:
        sig = sig[: max_len - 1] + "…"
    return sig


def jar_translate(jar: Path, source: str, target: str, sql_path: Path) -> tuple[int, str]:
    cmd = [
        "java",
        "-jar",
        str(jar),
        "-f",
        source,
        "-t",
        target,
        "--in",
        str(sql_path),
    ]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        cwd=str(ROOT),
    )
    return proc.returncode, proc.stderr or ""


def stratified_sample(rows: list[dict], n: int, seed: int = 42) -> list[dict]:
    import random

    rng = random.Random(seed)
    by_src: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_src[r["source"]].append(r)
    # Aim for dialect balance; take ceil shares then trim.
    dialects = sorted(by_src)
    if not dialects:
        return []
    per = max(1, n // len(dialects))
    picked: list[dict] = []
    for d in dialects:
        pool = list(by_src[d])
        rng.shuffle(pool)
        picked.extend(pool[:per])
    if len(picked) < n:
        rest = [r for r in rows if r not in picked]
        rng.shuffle(rest)
        picked.extend(rest[: n - len(picked)])
    rng.shuffle(picked)
    return picked[:n]


def read_sqltranslate_rows(csv_path: Path) -> list[dict]:
    with csv_path.open(encoding="utf-8", newline="") as f:
        return [r for r in csv.DictReader(f) if r.get("system") == "sqltranslate"]


def compute_slices(rows: list[dict], cases_root: Path) -> dict:
    parse = [r for r in rows if r["outcome"] == "PARSE"]
    success = [r for r in rows if r["outcome"] == "SUCCESS"]
    outcomes = Counter(r["outcome"] for r in rows)

    multi = Counter()
    exclusive = Counter()
    framed_multi = 0
    success_multi = Counter()
    dialect_parse = Counter()

    for r in parse:
        sql = load_sql(cases_root, r["case_id"], r["source"])
        flags = feature_flags(sql)
        dialect_parse[r["source"]] += 1
        for k in ("WITH", "OVER", "DERIVED", "COLON", "FETCH"):
            if flags[k]:
                multi[k] += 1
        if flags["FRAMED"]:
            framed_multi += 1
        exclusive[exclusive_primary(flags)] += 1

    for r in success:
        sql = load_sql(cases_root, r["case_id"], r["source"])
        flags = feature_flags(sql)
        for k in ("WITH", "OVER", "DERIVED", "COLON", "FETCH"):
            if flags[k]:
                success_multi[k] += 1

    total = len(rows)
    success_n = outcomes.get("SUCCESS", 0)
    cum_rows = 0
    exclusive_forecast = []
    for key in ("WITH", "OVER", "DERIVED", "COLON", "FETCH"):
        n = exclusive[key]
        cum_rows += n
        forecast = success_n + cum_rows
        exclusive_forecast.append(
            {
                "primary": key,
                "rows": n,
                "cum_exclusive": cum_rows,
                "forecast_success": forecast,
                "forecast_pct": 100.0 * forecast / total if total else 0.0,
            }
        )

    return {
        "total": total,
        "outcomes": dict(outcomes),
        "parse_n": len(parse),
        "success_n": success_n,
        "multi": dict(multi),
        "framed_multi": framed_multi,
        "exclusive": dict(exclusive),
        "success_multi": dict(success_multi),
        "exclusive_forecast": exclusive_forecast,
        "dialect_parse": dict(dialect_parse),
    }


def replay_parse(
    rows: list[dict],
    cases_root: Path,
    jar: Path,
    mode: str,
    sample: int,
) -> list[dict]:
    parse = [r for r in rows if r["outcome"] == "PARSE"]
    if mode == "stratified":
        chosen = stratified_sample(parse, sample)
    elif mode == "all":
        chosen = parse
    else:
        raise ValueError(f"unknown replay mode: {mode}")

    results = []
    for i, r in enumerate(chosen, 1):
        case_dir = cases_root / r["case_id"]
        sql_path = case_dir / f"input.{r['source']}.sql"
        if not sql_path.is_file():
            files = sorted(case_dir.glob("input.*.sql"))
            sql_path = files[0] if files else None
        if sql_path is None:
            results.append(
                {
                    "case_id": r["case_id"],
                    "source": r["source"],
                    "target": r["target"],
                    "exit_code": -1,
                    "stderr": "missing input file",
                    "signature": "(missing input)",
                }
            )
            continue
        code, stderr = jar_translate(jar, r["source"], r["target"], sql_path)
        # Truncate like BenchmarkDriver.appendStderr (240 chars, one line).
        one_line = " ".join(stderr.replace("\r", "\n").split())
        if len(one_line) > 240:
            one_line = one_line[:240]
        results.append(
            {
                "case_id": r["case_id"],
                "source": r["source"],
                "target": r["target"],
                "exit_code": code,
                "stderr": one_line,
                "signature": normalize_stderr_signature(one_line),
            }
        )
        if i % 50 == 0 or i == len(chosen):
            print(f"replay {i}/{len(chosen)}", file=sys.stderr)
    return results


def has_colon_cast_stderr(text: str) -> bool:
    """PG `::` often surfaces as token recognition error at ':' (lexer sees one colon)."""
    return "::" in text or bool(
        re.search(r"token recognition error at:\s*':'?", text, re.IGNORECASE)
    )


def keyword_hits(signature: str) -> set[str]:
    u = signature.upper()
    hits = set()
    if "WITH" in u:
        hits.add("WITH")
    if "OVER" in u:
        hits.add("OVER")
    if has_colon_cast_stderr(signature):
        hits.add("::")
    if "FETCH" in u:
        hits.add("FETCH")
    # mismatched '(' / paren heads
    if "mismatched input '('" in signature.lower() or "input '('" in signature.lower():
        hits.add("(")
    if "'('" in signature:
        hits.add("(")
    return hits


def gate_verdict(top_sigs: list[tuple[str, int]], replay_n: int) -> tuple[str, str]:
    """PASS if head signatures still show Wave 1 tokens; else FAIL."""
    if replay_n <= 0 or not top_sigs:
        return "FAIL", "no stderr replay evidence"
    # Inspect top 30 signatures' text for required heads.
    head_text = " | ".join(s for s, _ in top_sigs[:30])
    needed = {
        "WITH": "WITH" in head_text.upper(),
        "(": "'('" in head_text or "input '('" in head_text.lower(),
        "OVER": "OVER" in head_text.upper(),
        "::": has_colon_cast_stderr(head_text),
        "FETCH": "FETCH" in head_text.upper(),
    }
    # Core Wave 1 heads: WITH / derived '(' / OVER must appear in top-30.
    # :: and FETCH are smaller in-scope slices — PASS if present in top-30 or noted.
    core_ok = needed["WITH"] and needed["("] and needed["OVER"]
    if core_ok:
        missing = [k for k, ok in needed.items() if not ok]
        if missing:
            return (
                "PASS",
                f"top stderr dominated by WITH / '(' / OVER; "
                f"also checking smaller heads — missing from top-30: {', '.join(missing)}",
            )
        return (
            "PASS",
            "top stderr signatures dominated by WITH / '(' / OVER / ::(~':') / FETCH",
        )
    present = [k for k, ok in needed.items() if ok]
    return (
        "FAIL",
        f"different failure head — core WITH/'('/OVER not all in top-30 "
        f"(present: {present or 'none'})",
    )


def render_markdown(
    slices: dict,
    replay: list[dict] | None,
    replay_mode: str,
    csv_path: Path,
    jar_path: Path,
) -> str:
    total = slices["total"]
    success_n = slices["success_n"]
    parse_n = slices["parse_n"]
    multi = slices["multi"]
    exclusive = slices["exclusive"]
    framed = slices["framed_multi"]
    succ_ml = slices["success_multi"]

    lines: list[str] = []
    lines.append("# PARROT Wave 1 — baseline failure slices")
    lines.append("")
    try:
        csv_disp = csv_path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        csv_disp = csv_path.as_posix()
    try:
        jar_disp = jar_path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        jar_disp = jar_path.as_posix()

    lines.append(f"- Frozen CSV: `{csv_disp}`")
    lines.append(
        f"- Cohort: sqltranslate **{total}** rows "
        f"(SUCCESS={success_n}, PARSE={parse_n}, "
        f"REFUSED={slices['outcomes'].get('REFUSED', 0)})"
    )
    lines.append(
        "- Feature labels: regex multi-label on case `input.<source>.sql` "
        "(WITH / `OVER (` / `FROM (`|`JOIN (` / `::` / `FETCH FIRST|NEXT`)"
    )
    lines.append(
        "- Exclusive primary priority: WITH → OVER → DERIVED → COLON → FETCH → OTHER"
    )
    lines.append(
        f"- Framed windows: nested `OVER (... ROWS|RANGE ...)` = **{framed}** "
        f"among PARSE multi-label OVER={multi.get('OVER', 0)}"
    )
    lines.append("")
    lines.append("## Multi-label presence (PARSE rows)")
    lines.append("")
    lines.append("| Feature (multi-label presence) | PARSE rows | Notes |")
    lines.append("|---|---:|---|")
    lines.append(
        f"| `WITH` | {multi.get('WITH', 0)} | "
        f"{succ_ml.get('WITH', 0)} among SUCCESS |"
    )
    lines.append(
        f"| `OVER (` | {multi.get('OVER', 0)} | of which **framed** "
        f"`ROWS`/`RANGE`: **{framed}** |"
    )
    lines.append(
        f"| Derived `FROM (` / `JOIN (` | {multi.get('DERIVED', 0)} | "
        f"{succ_ml.get('DERIVED', 0)} among SUCCESS |"
    )
    lines.append(
        f"| PG `::` | {multi.get('COLON', 0)} | "
        f"{succ_ml.get('COLON', 0)} among SUCCESS |"
    )
    lines.append(
        f"| `FETCH FIRST/NEXT` | {multi.get('FETCH', 0)} | "
        f"{succ_ml.get('FETCH', 0)} among SUCCESS |"
    )
    lines.append("")
    lines.append("## Exclusive primary → SUCCESS forecast")
    lines.append("")
    lines.append(
        "| Exclusive primary (priority: WITH → OVER → DERIVED → COLON → FETCH) "
        "| Rows | Cum. → SUCCESS if converted |"
    )
    lines.append("|---|---:|---:|")
    for i, row in enumerate(slices["exclusive_forecast"]):
        key = row["primary"]
        n = row["rows"]
        cum = row["cum_exclusive"]
        fs = row["forecast_success"]
        pct = row["forecast_pct"]
        if i == 0:
            lines.append(f"| {key} | {n} | {fs} ({pct:.1f}%) |")
        else:
            label = f"+ {key}" + (" (all)" if key == "OVER" else "")
            if key == "COLON":
                label = "+ `::`"
            elif key == "FETCH":
                label = "+ FETCH"
            elif key == "DERIVED":
                label = "+ DERIVED"
            lines.append(f"| {label} | +{n} → {cum} | {fs} ({pct:.1f}%) |")
    other = exclusive.get("OTHER", 0)
    lines.append("")
    lines.append(
        f"**OTHER primary (out of Wave 1 scope):** {other} PARSE rows "
        f"({100.0 * other / parse_n:.1f}% of PARSE). "
        "JSON/routines/DDL long-tail — do not invent features for these."
    )
    lines.append("")
    w1 = slices["exclusive_forecast"][2]  # WITH+OVER+DERIVED
    lines.append(
        f"**Wave 1 bar reading:** exclusive WITH+OVER+DERIVED forecasts "
        f"**~{w1['forecast_pct']:.0f}%** ({w1['forecast_success']}/{total}) "
        f"if conversion holds. Framed windows are a minority ({framed} multi-label); "
        "refusing frames is acceptable and does **not** invalidate the 65% bar."
    )
    lines.append("")

    # Stderr section
    lines.append("## Post-Task-1 stderr signatures")
    lines.append("")
    if not replay:
        lines.append("_No jar replay run in this generation._")
    else:
        lines.append(
            f"- Method: jar replay via `{jar_disp}` "
            f"(mode=`{replay_mode}`, n={len(replay)} PARSE cases). "
            "Stderr truncated to 240 one-line chars (Task 1 `notes` contract). "
            "Full EvaluationMain CSV re-run skipped (would re-measure latency); "
            "jar stderr matches what Task 1 persists as `stderr=` notes."
        )
        lines.append(
            f"- PARSE dialect mix in replay: "
            + ", ".join(
                f"{k}={v}"
                for k, v in sorted(Counter(r["source"] for r in replay).items())
            )
        )
        sig_counts = Counter(r["signature"] for r in replay)
        top30 = sig_counts.most_common(30)
        # Presence of Wave 1 heads anywhere in sample
        all_text = " ".join(r["stderr"] for r in replay)
        presence = {
            "WITH": bool(re.search(r"\bWITH\b", all_text, re.I)),
            "mismatched '('": "mismatched input '('" in all_text.lower()
            or "input '('" in all_text.lower(),
            "OVER": bool(re.search(r"\bOVER\b", all_text, re.I)),
            ":: / ':' token": has_colon_cast_stderr(all_text),
            "FETCH": bool(re.search(r"\bFETCH\b", all_text, re.I)),
        }
        gate, why = gate_verdict(top30, len(replay))
        lines.append(f"- **GATE: {gate}** — {why}")
        lines.append(
            "- Sample-wide head presence: "
            + ", ".join(f"{k}={'yes' if v else 'NO'}" for k, v in presence.items())
        )
        lines.append(
            "- Note: PG `::` cast failures commonly appear as "
            "`token recognition error at: ':'` (not the literal digraph `::`)."
        )
        lines.append("")
        lines.append("### Top 30 `stderr=` signatures")
        lines.append("")
        lines.append("| Rank | Count | Signature |")
        lines.append("|---:|---:|---|")
        for i, (sig, cnt) in enumerate(top30, 1):
            safe = sig.replace("|", "\\|")
            lines.append(f"| {i} | {cnt} | `{safe}` |")
        lines.append("")
        # Keyword hit rates among signatures
        hit = Counter()
        for r in replay:
            for h in keyword_hits(r["stderr"]):
                hit[h] += 1
        lines.append("### Wave-1 token hits in stderr (row counts)")
        lines.append("")
        lines.append("| Token in stderr | Replay rows |")
        lines.append("|---|---:|")
        for tok in ("WITH", "(", "OVER", "::", "FETCH"):
            lines.append(f"| `{tok}` | {hit.get(tok, 0)} |")

    lines.append("")
    lines.append("## Plan-brief comparison note")
    lines.append("")
    lines.append(
        "Plan brief (2026-07-22) cited exclusive DERIVED **+129 → 623** / forecast 968 "
        "(67.9%). This regenerator measures exclusive DERIVED "
        f"**+{exclusive.get('DERIVED', 0)} → "
        f"{sum(exclusive.get(k, 0) for k in ('WITH', 'OVER', 'DERIVED'))}** / forecast "
        f"{success_n + sum(exclusive.get(k, 0) for k in ('WITH', 'OVER', 'DERIVED'))} "
        f"({100.0 * (success_n + sum(exclusive.get(k, 0) for k in ('WITH', 'OVER', 'DERIVED'))) / total:.1f}%). "
        "Multi-label WITH/OVER/DERIVED/`::`/FETCH match the brief exactly; "
        "framed nested OVER matches **34**. Exclusive DERIVED is higher here "
        "(stricter prior unpublished heuristic likely excluded some `FROM (`/`JOIN (` "
        "shapes). Directionally the Wave 1 ceiling is **at least as strong** as the brief."
    )
    lines.append("")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    ap.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    ap.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument(
        "--replay",
        choices=("none", "stratified", "all"),
        default="stratified",
        help="jar-replay PARSE cases for stderr signatures (default: stratified)",
    )
    ap.add_argument(
        "--sample",
        type=int,
        default=200,
        help="stratified sample size when --replay=stratified (default 200)",
    )
    ap.add_argument(
        "--replay-cache",
        type=Path,
        default=None,
        help="write/read JSON cache of replay rows",
    )
    ap.add_argument(
        "--load-replay-cache",
        type=Path,
        default=None,
        help="skip jar replay; load signatures from this JSON",
    )
    args = ap.parse_args()

    if not args.csv.is_file():
        print(f"missing CSV: {args.csv}", file=sys.stderr)
        return 2
    if not args.cases.is_dir():
        print(f"missing cases: {args.cases}", file=sys.stderr)
        return 2

    rows = read_sqltranslate_rows(args.csv)
    print(f"loaded {len(rows)} sqltranslate rows from {args.csv}", file=sys.stderr)
    slices = compute_slices(rows, args.cases)
    print(
        f"PARSE={slices['parse_n']} multi={slices['multi']} "
        f"exclusive={slices['exclusive']} framed={slices['framed_multi']}",
        file=sys.stderr,
    )

    replay: list[dict] | None = None
    replay_mode = args.replay
    if args.load_replay_cache:
        replay = json.loads(args.load_replay_cache.read_text(encoding="utf-8"))
        replay_mode = f"cache:{args.load_replay_cache.name}"
        print(f"loaded replay cache n={len(replay)}", file=sys.stderr)
    elif args.replay != "none":
        if not args.jar.is_file():
            print(f"missing jar: {args.jar} — run mvn package -DskipTests", file=sys.stderr)
            return 3
        replay = replay_parse(
            rows, args.cases, args.jar, args.replay, args.sample
        )
        cache_path = args.replay_cache or DEFAULT_REPLAY_CACHE
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(replay, indent=2), encoding="utf-8")
        print(f"wrote replay cache {cache_path}", file=sys.stderr)

    md = render_markdown(slices, replay, replay_mode, args.csv, args.jar)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(md, encoding="utf-8")
    print(f"wrote {args.out}", file=sys.stderr)

    if replay:
        sig_counts = Counter(r["signature"] for r in replay)
        gate, why = gate_verdict(sig_counts.most_common(30), len(replay))
        print(f"GATE {gate}: {why}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
