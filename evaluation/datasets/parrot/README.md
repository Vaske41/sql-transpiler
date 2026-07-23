# PARROT-Diverse offline stress corpus

## Variant

This directory holds the **PARROT-Diverse** offline stress corpus — Hugging Face
[`weizhoudb/PARROT`](https://huggingface.co/datasets/weizhoudb/PARROT) split
`test` (~28 003 wide rows), filtered to dialects `mysql` / `postgresql` /
`tsql` (HF column `postgres` → CLI `postgresql`).

It is **not** the NeurIPS **598-pair** curated PARROT core. Do not treat
filtered Diverse rates as leaderboard or AccEX/AccRES results.

## Metrics (honesty)

Primary offline metrics are **Phase 7 outcome classes** only: coverage,
refusal profiles, and stress behavior across sqltranslate / SQLGlot / Gemini /
Composer fixtures.

**Forbidden captions / wording:** “PARROT accuracy,” AccEX, AccRES, leaderboard
parity, or implying SUCCESS% equals translation accuracy vs PARROT gold.

### Wave 1 reporting caption

Wave 1 remeasures the **frozen 1,426-task** PARROT-Diverse cohort after shipping
non-recursive CTEs, derived tables, frameless windows, and PostgreSQL
`FETCH`/`::` (Extension Queue items 3–5 + PG quick wins).

```text
python evaluation/bin/remeasure_parrot_wave1.py
```

Gate args are exactly `EvaluationMain --corpus parrot-diverse` (**sqltranslate
only**; no `--sqlglot`). Artifact:
`evaluation/results-local/parrot-wave1-remeasure.csv` (gitignored).

| | SUCCESS | PARSE | REFUSED | SUCCESS rate |
|---|---:|---:|---:|---:|
| Pre–Wave 1 baseline | 345 | 1057 | 24 | 24.2% |
| Post–Wave 1 (remeasure) | **654** | 666 | 106 | **45.9%** |
| Δ | **+309** | −391 | +82 | +21.7 pp |
| Wave 1 bar (~65%) | ≥ ~927 | — | — | **not met** |

Caption CSV `SUCCESS` as **parse→rules→print coverage** (process exit 0), **not**
AccEX, gold_sql match, or catalog-semantic translation quality.

**Bar honesty:** the Option B ~65% forecast assumed exclusive WITH/OVER/DERIVED
primary slices convert when those constructs ship. Post-remeasure, Wave 1 token
residuals among remaining PARSE are tiny (strict primary: WITH≈5, OVER≈8,
FETCH≈1), while the bulk of remaining PARSE is out-of-scope long-tail
(DDL/routines, vendor functions/intervals, JSON, vars/brackets, other syntax).
+82 REFUSED are mostly honest Wave 1 refusals (`recursive CTE`, `window frame`)
plus type long-tail. **Do not silently lower the bar** — publish this remainder
as the thesis finding; any bar revision needs this evidence table.

Stratify thesis reporting by `hf_id` (benchmark family), not a single
undifferentiated SUCCESS%. `case_id` embeds it as
`{hf_row:05d}-{hf_id}-{source}-to-{target}` (see root README Python/Excel
one-liner — pivot outcomes by the `hf_id` capture group).

## Gold SQL

`gold_sql` is stored in JSONL for a **future** compare / AccEX-style plan.
It is **not** scored today and must **not** be materialized as
`expected.*.sql`. Direction comes from `target.txt` after materialization.

## Catalog / semantic rules

PARROT-Diverse cases are typically **query-only** stress. They do **not**
exercise catalog-aware golden / `semantic` rules. Keep these outcome rates in
**separate** thesis tables from golden/`cases/semantic` metrics.

## Pair contract

On Diverse wide rows, non-empty dialect cells on the **same `hf_row`** are
parallel renderings of one logical query. Every ordered pair among
`{mysql, postgresql, tsql}` on that row is a legitimate stress task.

Case keys use **row index** (`hf_row`). HF `id` alone is not unique across the
~28k dump.

Filter version (when present in `manifest.json`): `diverse-dialect-v1` =
split `test`, our dialects only, cartesian ordered pairs on the same row, no
v1-grammar allowlist.

## Layout

| Path | Role |
|------|------|
| `pairs.smoke.jsonl` | 3 committed smoke pairs (unit / offline smoke) |
| `pairs.jsonl` | Full filtered pairs (**gitignored**; regenerate) |
| `manifest.json` | Fetch metadata — **commit** after a real fetch |
| `cases/` | Materialized inputs (**gitignored**) |

JSONL fields: `case_id`, `hf_row`, `hf_id`, `source`, `target`, `source_sql`,
`gold_sql`.

## Thesis workflow (budget-capped)

Recommended local sequence for thesis tables. Never run HF / live APIs from CI.

1. **Fetch + materialize once** — commit `manifest.json` (not bulk `pairs.jsonl` / `cases/`).
2. **Offline jar + SQLGlot** — full corpus or `--limit N` (Phase 7 outcomes only).
3. **Live Gemini** — `--limit 20` max for **local** fixtures under
   `evaluation/results/gemini/...` (gitignored).
4. **Live Composer** — `--limit 5` max for **local** fixtures
   (`ComposerAdapter` 300s timeouts) under `evaluation/results/composer/...`.
5. **Re-run offline** — score fixtures (`NO_FIXTURE` when missing).
6. **Git hygiene** — `evaluation/results/**` is gitignored; keep fixtures local only.

```text
pip install -r evaluation/bin/requirements-datasets.txt
python evaluation/bin/fetch_parrot.py
python evaluation/bin/materialize_parrot.py

# after package; test classpath (never CI network / HF / live APIs)
java -cp <test+runtime> rs.etf.sqltranslator.evaluation.EvaluationMain \
  --corpus parrot-diverse --sqlglot

# live fixture regen (local only; keys via evaluation/.env.local + EVAL_LIVE=1)
# local budget (warn-only): ≤20 Gemini, ≤5 Composer — results/ is gitignored
java -cp <test+runtime> ...EvaluationMain --live-gemini --corpus parrot-diverse --limit 20
java -cp <test+runtime> ...EvaluationMain --live-composer --corpus parrot-diverse --limit 5

# re-score fixtures offline
java -cp <test+runtime> ...EvaluationMain --corpus parrot-diverse --sqlglot
```

CSV default: `target/evaluation/summary/parrot-diverse-latest.csv`.

Live factories use the same single-direction walk as offline (`target.txt` +
`--limit` on `ParrotCorpus.listInputs`). Optional local smoke: `--limit 2`
(Gemini) / `--limit 1` (Composer). Fixtures stay under gitignored
`evaluation/results/**`.

CI smoke (no Python / HF): Failsafe `ParrotDiverseBenchmarkIT` writes cases under
`target/evaluation/parrot-diverse-smoke/` in Java and runs
`BenchmarkDriver.parrotDiverseOffline`.

Default `./mvnw --batch-mode clean verify` stays Docker-free and API-free.

## Fixture budget (I5)

**Local thesis budget** (warn-only at runtime): **≤20** Gemini and **≤5**
Composer unless a later plan revises the budget. `EvaluationMain` warns on
stderr if `--limit` exceeds the budget; it does not hard-exit. Local regen
may use a higher `--limit` for exploration. Fixtures under
`evaluation/results/` are **gitignored** and must not be committed.
Missing fixture → `NO_FIXTURE`.

## Citation

Zhou et al., *PARROT: A Benchmark for Evaluating SQL Dialect Translation*
(NeurIPS). This repo uses the **PARROT-Diverse** wide dump on Hugging Face for
**offline stress / coverage**, not the curated 598-pair NeurIPS evaluation
protocol or AccEX/AccRES scoring.
