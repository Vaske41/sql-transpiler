# SQL Query Transpiler

Source-to-source SQL translator between **T-SQL (SQL Server)**, **MySQL**, and
**PostgreSQL** — 6 translation directions over core DML and DDL. Master's thesis
project (ETF, University of Belgrade), built with Java 17 and ANTLR4 using an
Apache Spark Catalyst-inspired rule-based pipeline.

## Build

    ./mvnw clean verify   # Linux/macOS
    .\mvnw.cmd clean verify  # Windows

Requires JDK 17+. Maven is provided by the committed wrapper.

After `package` / `verify`, the runnable fat jar is `target/sqltranslate.jar`.
`verify` also smoke-tests `java -jar target/sqltranslate.jar --help`.

Default `verify` / `test` excludes Docker-backed groups (`integration`,
`sqlserver-integration`) so the gate stays Docker-free.

### Integration profiles (local opt-in)

MySQL ↔ PostgreSQL semantic equivalence (requires Docker):

    ./mvnw -Pintegration test
    .\mvnw.cmd -Pintegration test

SQL Server scaffold smoke (`SELECT 1`; requires Docker). **Not run in CI** day one:

    ./mvnw -Psqlserver-integration test
    .\mvnw.cmd -Psqlserver-integration test

Without Docker, the profile may skip via JUnit assumptions — that is OK.

## CLI

    java -jar target/sqltranslate.jar --from <dialect> --to <dialect> [options] [SQL]

Dialects: `tsql`, `mysql`, `postgresql` (case-insensitive).

| Option | Meaning |
|--------|---------|
| `--from` / `-f` | Source dialect (required) |
| `--to` / `-t` | Target dialect (required) |
| `--in FILE` | Read SQL from file (UTF-8) |
| `--out FILE` | Write SQL to file (UTF-8); stdout stays empty |
| `--strict` | Warnings become errors (exit 4; no SQL written) |
| `--report` | Print warnings to stderr |
| (positional SQL) | Inline SQL; omit with `--in` or pipe stdin |

**Streams:** translated SQL → stdout (or `--out`); errors and `--report` → stderr.
All streams are UTF-8. Warnings are silent unless `--report` or `--strict`.

**Stdin:** pipe or redirect is fine. On an interactive TTY with no `--in` and no
positional SQL, the CLI exits 3 immediately (does not hang waiting for EOF).

### Examples (six directions)

    java -jar target/sqltranslate.jar --from tsql --to postgresql --in query.sql
    java -jar target/sqltranslate.jar --from tsql --to mysql "SELECT N'x' + name FROM t;"
    java -jar target/sqltranslate.jar --from mysql --to postgresql --in query.sql
    java -jar target/sqltranslate.jar --from mysql --to tsql "SELECT NOW() FROM t LIMIT 1;"
    java -jar target/sqltranslate.jar --from postgresql --to mysql --in query.sql
    java -jar target/sqltranslate.jar --from postgresql --to tsql "SELECT id FROM t WHERE active;"

    cat query.sql | java -jar target/sqltranslate.jar --from postgresql --to mysql
    java -jar target/sqltranslate.jar --from mysql --to tsql --in q.sql --out out.sql --report

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Parse / syntax error (`error: parse:`) |
| 2 | Unsupported feature (`error: unsupported:`) |
| 3 | Usage or I/O error (`error: usage:` / `error: io:`) |
| 4 | `--strict` with warnings (`error: strict:`) |
| 5 | Internal translator failure (`error: internal:`) |

## Evaluation baselines (Phase 7)

Offline benchmark adapters live under `src/test/java/.../evaluation/` (not shaded into
the product jar). Default `verify` stays Docker-free and never calls LLM APIs.

### Profiles and Failsafe

| Command | What runs |
|---------|-----------|
| `./mvnw clean verify` | Surefire (excl. Docker tags) + Failsafe jar ITs + CLI `--help` smoke |
| `./mvnw -Pintegration test` | MySQL↔PostgreSQL semantic equivalence (`@Tag("integration")`; Docker) |
| `./mvnw -Psqlserver-integration test` | SQL Server scaffold (`@Tag("sqlserver-integration")`; Docker; **not in CI**) |

Failsafe (after `package`) runs only:

- `JarDeterminismIT` — fat-jar stdout byte-identity on a **stratified subset**
  (≥20 directions, `PER_DIRECTION=4`); not a full-corpus determinism claim →
  `target/evaluation/determinism/`
- `SqlTranslateJarIT` — jar adapter SUCCESS / `REFUSED_OK`
- `BenchmarkDriverOfflineIT` — limited corpus CSV → `target/evaluation/summary/latest.csv`
- `ParrotDiverseBenchmarkIT` — Java-written smoke under
  `target/evaluation/parrot-diverse-smoke/` (no Python in CI) →
  `parrot-diverse-smoke.csv`

### SQLGlot helper

Pinned dependency:

    pip install -r evaluation/bin/requirements.txt

Helper: `evaluation/bin/sqlglot_transpile.py` (`--read` / `--write` with
`tsql` | `mysql` | `postgresql`; stdin SQL → stdout SQL). When the script contains
`CREATE TABLE`, a minimal schema is extracted and passed into SQLGlot so DDL-backed
cases stay fair versus sqltranslate.

### LLM / agent fixtures (Gemini / Composer 2.5)

Baselines: **sqltranslate** (fat jar), **SQLGlot** (pinned), **Gemini**
(`gemini-2.5-flash`, chat completion), **Composer 2.5** (Cursor Agent SDK —
**not** a completion API). Claude / Anthropic is **not** a baseline.

Fixture-first under `evaluation/results/{system}/{caseKey}/{src}-to-{tgt}.sql`
(+ `.meta.json`). Missing fixture → outcome `NO_FIXTURE` (not success).
Never call LLMs or agents from GitHub Actions. Prompt: `evaluation/prompts/v1.txt`.

| Dimension | Gemini | Composer 2.5 |
|-----------|--------|--------------|
| API shape | Chat completion HTTP | Cursor Agent SDK (`Agent.prompt`) |
| Tools | None | Local agent tools may run |
| Determinism | `temperature=0` | Agent loop; no temp=0 analogue |
| Latency | Network completion | Agent cold start dominates |
| SQL fixtures | Single-shot pin | Same — not multi-sample consensus |

Empty temp `cwd` for Composer prevents repo self-edits; it does **not** equalize
the systems. Thesis wording: prefer **“LLM / agent baselines”** — do not imply
Composer ≡ Gemini.

**Live keys (local only):** put `GEMINI_API_KEY` / `CURSOR_API_KEY` in
`evaluation/.env.local` (gitignored; see `evaluation/.env.example`). Set
`EVAL_LIVE=1` (or `-Deval.live=true`). `EvaluationMain` overlays file values when
process env is unset — no PowerShell `Get-Content` export dance required.
Composer injects `CURSOR_API_KEY` into the child ProcessBuilder. Never CI.

Pin: `cursor-sdk==0.1.9` beside `sqlglot==30.12.0` in
`evaluation/bin/requirements.txt`.

LLM / agent scoring uses the **single-statement** subset only (multi-statement
scripts stay jar / SQLGlot).

### PARROT-Diverse offline stress corpus

Uses Hugging Face **PARROT-Diverse** (`weizhoudb/PARROT` split `test`), not the
NeurIPS 598-pair core. Primary metrics are Phase 7 **outcome classes** / coverage /
refusal — **not** “PARROT accuracy,” AccEX, AccRES, or leaderboard parity.
Query-only stress: keep rates in **separate** thesis tables from golden /
`cases/semantic`. Committed fixture budget: **≤20** Gemini, **≤5** Composer.

```text
pip install -r evaluation/bin/requirements-datasets.txt
python evaluation/bin/fetch_parrot.py
python evaluation/bin/materialize_parrot.py
# keys in evaluation/.env.local (gitignored)
mvn -q -DskipTests package
java -cp <test+runtime> ...EvaluationMain --corpus parrot-diverse --sqlglot
$env:EVAL_LIVE=1
java -cp ... EvaluationMain --live-gemini --corpus parrot-diverse --limit 20
java -cp ... EvaluationMain --live-composer --corpus parrot-diverse --limit 5
```

CSV: `target/evaluation/summary/parrot-diverse-latest.csv`.
`--corpus parrot` is rejected (use `parrot-diverse`). Default `--corpus golden`
is the existing golden offline path → `latest.csv`.

**Thesis stratification (I1):** do not quote a single undifferentiated SUCCESS%.
Pivot / count Phase 7 outcomes by `hf_id` embedded in `case_id`
(`{hf_row:05d}-{hf_id}-{source}-to-{target}`; `hf_id` may contain spaces).
Excel: add a helper column with the regex capture below, then PivotTable.
Python one-liner over the CSV:

```text
python -c "import csv,re,collections as C; p=re.compile(r'^\d{5}-(.+)-(mysql|postgresql|tsql)-to-(mysql|postgresql|tsql)$'); c=C.Counter();
[c.update({(p.match(r['case_id']).group(1), r['outcome']):1}) for r in csv.DictReader(open('target/evaluation/summary/parrot-diverse-latest.csv',encoding='utf-8')) if r['system']=='sqltranslate' and p.match(r['case_id'])];
print(*sorted(f'{k[0]}\t{k[1]}\t{v}' for k,v in c.items()), sep='\n')"
```

A dedicated summary tool is optional YAGNI.

### Offline driver and scoring

Smoke / unit: `BenchmarkDriverOfflineTest` (Surefire). Full limited CSV after package:
`BenchmarkDriverOfflineIT` (Failsafe) or:

    # after package; optional full corpus via EvaluationMain on the test classpath
    ./mvnw -Dtest=BenchmarkDriverOfflineTest test

CSV columns:
`system, case_id, source, target, outcome, exit_or_status, syntactic_valid,
semantic_equiv, determinism_ok, latency_ms_median, notes`

Primary outcomes:

| Situation | sqltranslate | SQLGlot | LLM / agent |
|-----------|--------------|---------|-------------|
| Normal, good SQL | `SUCCESS` | `SUCCESS` | `SUCCESS` if fixture/live SQL |
| Unsupported / expected refusal | exit 2 → `REFUSED_OK` | invent → `WRONG_INVENTION`; error → `REFUSED` | invent → `WRONG_INVENTION`; empty / non-SQL → `REFUSED` (not `REFUSED_OK`) |
| Parse / crash | `PARSE` / `INTERNAL` | failure | `NO_FIXTURE` / `ERROR` |

Local latency: N≥3 runs, drop warmup, report median `latency_ms`. Gemini /
Composer latency is reported separately (fixture/live meta; agent class for
Composer) — never ranked against local tools.

`BenchmarkDriver` always writes `syntactic_valid` / `semantic_equiv` as `n/a`
(engine columns reserved for a future driver wiring; not filled under
`-Pintegration`). Semantic evidence is only from `SemanticEquivalenceTest` under
`-Pintegration` (Testcontainers), not from the offline CSV. Do **not** claim
engine validity via this project's own parsers.

### Semantic authoring rules (summary)

- Catalog-dependent rewrites need **DDL+DML in the same script**.
- Semantic equivalence corpus: `cases/semantic/**` only; ordered final `SELECT`;
  MySQL↔PostgreSQL via Testcontainers (`-Pintegration`).
- Standalone unresolved casts keep the warning-not-guess path.

### Phase 6 jar contract

CLI exit codes, streams, and `--from`/`--to`/`--in` flags above are the stable
ProcessBuilder contract for evaluation — adapters shell `target/sqltranslate.jar`
and never call `Translator.translate` in-process for thesis measurements.

