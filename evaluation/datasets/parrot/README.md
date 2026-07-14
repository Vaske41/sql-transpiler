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
3. **Live Gemini** — `--limit 20` max for **committed** fixtures under
   `evaluation/results/gemini/...`.
4. **Live Composer** — `--limit 5` max for **committed** fixtures
   (`ComposerAdapter` 300s timeouts) under `evaluation/results/composer/...`.
5. **Re-run offline** — score fixtures (`NO_FIXTURE` when missing).
6. **Git hygiene** — do **not** `git add` large `evaluation/results/**` trees;
   commit only a smoke / thesis **allowlist** within the budget below.

```text
pip install -r evaluation/bin/requirements-datasets.txt
python evaluation/bin/fetch_parrot.py
python evaluation/bin/materialize_parrot.py

# after package; test classpath (never CI network / HF / live APIs)
java -cp <test+runtime> rs.etf.sqltranslator.evaluation.EvaluationMain \
  --corpus parrot-diverse --sqlglot

# live fixture regen (local only; keys via evaluation/.env.local + EVAL_LIVE=1)
# hard committed budget: ≤20 Gemini, ≤5 Composer
java -cp <test+runtime> ...EvaluationMain --live-gemini --corpus parrot-diverse --limit 20
java -cp <test+runtime> ...EvaluationMain --live-composer --corpus parrot-diverse --limit 5

# re-score fixtures offline
java -cp <test+runtime> ...EvaluationMain --corpus parrot-diverse --sqlglot
```

CSV default: `target/evaluation/summary/parrot-diverse-latest.csv`.

Live factories use the same single-direction walk as offline (`target.txt` +
`--limit` on `ParrotCorpus.listInputs`). Optional local smoke: `--limit 2`
(Gemini) / `--limit 1` (Composer) — do not commit those smoke trees unless they
fit the allowlist budget.

CI smoke (no Python / HF): Failsafe `ParrotDiverseBenchmarkIT` writes cases under
`target/evaluation/parrot-diverse-smoke/` in Java and runs
`BenchmarkDriver.parrotDiverseOffline`.

Default `./mvnw --batch-mode clean verify` stays Docker-free and API-free.

## Fixture budget (I5)

Hard cap on **committed** PARROT-Diverse fixtures: **≤20** Gemini and **≤5**
Composer unless a later plan revises the budget. Local regen may use a higher
`--limit` for exploration, but only the allowlisted subset may be git-added.
Missing fixture → `NO_FIXTURE`.

## Citation

Zhou et al., *PARROT: A Benchmark for Evaluating SQL Dialect Translation*
(NeurIPS). This repo uses the **PARROT-Diverse** wide dump on Hugging Face for
**offline stress / coverage**, not the curated 598-pair NeurIPS evaluation
protocol or AccEX/AccRES scoring.
