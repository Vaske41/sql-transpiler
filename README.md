# SQL Query Transpiler

Source-to-source SQL translator between **T-SQL (SQL Server)**, **MySQL**, and
**PostgreSQL** — all 6 directions. Master's thesis project (ETF, University of
Belgrade), built with Java 17 and ANTLR4 over a rule-based pipeline inspired by
Apache Spark's Catalyst.

The design rule is **refuse over guess**: anything the translator cannot render
faithfully in the target dialect raises `UnsupportedFeatureException` (exit 2)
rather than emitting approximate SQL.

## Supported subset

| Area | In scope |
|---|---|
| Queries | `SELECT` with joins, `WHERE`, `GROUP BY`/`HAVING`, `ORDER BY`, subqueries, derived tables, set operations (`UNION`/`EXCEPT`/`INTERSECT`) |
| Row limiting | `TOP`, `LIMIT`/`OFFSET`, `OFFSET … FETCH`, `WITH TIES` where the target can express it |
| CTEs | Non-recursive and `WITH RECURSIVE`, incl. nested-CTE flattening for T-SQL |
| Windows | `OVER (PARTITION BY … ORDER BY …)` with `ROWS`/`RANGE` frames and NULLS-ordering fidelity |
| Aggregates | Ordered aggregates (`STRING_AGG`/`GROUP_CONCAT` ↔ `WITHIN GROUP`), `FILTER (WHERE …)` → `CASE`, `DISTINCT ON` → `ROW_NUMBER()` |
| Joins | `LATERAL` ↔ `CROSS APPLY`/`OUTER APPLY`, `FULL JOIN` emulation for MySQL, `JOIN … USING` expansion for T-SQL |
| Expressions | Casts and type narrowing, string-concatenation resolution, boolean semantics, `EXTRACT`, `AT TIME ZONE`, `INTERVAL` arithmetic, PostgreSQL regex operators (`~`, `~*`) → `REGEXP_LIKE`, JSON accessors, `VALUES` as a table source |
| Set-returning | `generate_series` → recursive CTE on both T-SQL and MySQL |
| DML | `INSERT … VALUES` (multi-row), `INSERT … SELECT`, `UPDATE` (incl. `UPDATE … FROM`), `DELETE`, upsert (`ON CONFLICT` ↔ `ON DUPLICATE KEY`), `RETURNING` ↔ `OUTPUT` |
| DDL | `CREATE TABLE` (types, constraints, auto-increment/`SERIAL`/`IDENTITY`), `CREATE VIEW`, `CREATE INDEX` (incl. `INCLUDE` and partial predicates where the target supports them), `DROP TABLE`/`DROP INDEX`/`DROP VIEW`, `ALTER TABLE ADD`/`DROP COLUMN`, `TRUNCATE` |
| Routines | `CREATE FUNCTION` / `CREATE PROCEDURE` with a `SELECT`-only body |

Not parsed: `MERGE`, `PIVOT`/`UNPIVOT`, the named `WINDOW` clause, MySQL
`RLIKE`/`REGEXP`, T-SQL `#temp` tables, procedural bodies (`DECLARE`, control
flow, `$BODY$` blocks).

## Refusals

Parsed successfully, then refused because the target has no faithful form:

| Refusal | Direction |
|---|---|
| `CLUSTERED` index (`NONCLUSTERED` is accepted and folded away) | T-SQL → any |
| Index method (`USING …`) | MySQL/PostgreSQL → any |
| Index column prefix length (`col(n)`) | MySQL → any |
| `NULLS FIRST`/`NULLS LAST` on index columns | PostgreSQL → any |
| Partial index (`WHERE …`) and `INCLUDE` columns | → MySQL |
| Array types, `ARRAY` literals, `ARRAY_AGG`/`JSON_AGG` | → targets with no array type |
| `GROUPING SETS`/`ROLLUP`/`CUBE` | → MySQL |
| `RANGE` frame with offset bounds | → T-SQL |
| `OFFSET`/`FETCH` without `ORDER BY`; `OFFSET` without `LIMIT` | → T-SQL / → MySQL |
| User variables (`@x`) and their assignment | → targets without them |

### Known limitation — reserved keywords and identifiers

The three grammars share one keyword block, so a token reserved in any dialect is
reserved in all of them. Words such as `INDEX`, `USING`, `KEY`, and `CLUSTERED`
cannot appear as bare identifiers even where the real engine allows it —
`SELECT index FROM t` is legal PostgreSQL but a parse error here. Quote such
identifiers (`"index"`, `` `index` ``, `[index]`).

Identifiers are also ASCII-only (`ID : [A-Za-z_][A-Za-z0-9_$]*`), so non-ASCII
bare identifiers such as CJK column names fail to lex.

## Build

    ./mvnw clean verify      # Linux/macOS
    .\mvnw.cmd clean verify  # Windows

Requires JDK 17+; Maven comes from the committed wrapper. The build produces the
runnable fat jar `target/sqltranslate.jar`, and `verify` smoke-tests
`java -jar target/sqltranslate.jar --help`.

Default `verify`/`test` excludes the Docker-backed groups (`integration`,
`sqlserver-integration`), so the gate needs no Docker and no network.

### Integration profiles (local opt-in, requires Docker)

    ./mvnw -Pintegration test            # MySQL ↔ PostgreSQL semantic equivalence
    ./mvnw -Psqlserver-integration test  # SQL Server scaffold smoke; not run in CI

Without Docker these skip via JUnit assumptions.

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
| (positional SQL) | Inline SQL; omit when using `--in` or stdin |

Translated SQL goes to stdout (or `--out`); errors and `--report` output go to
stderr. All streams are UTF-8. Warnings are silent unless `--report` or `--strict`
is given. Stdin may be piped or redirected; on an interactive TTY with neither
`--in` nor positional SQL the CLI exits 3 rather than hanging.

### Examples

    java -jar target/sqltranslate.jar --from tsql --to postgresql --in query.sql
    java -jar target/sqltranslate.jar --from tsql --to mysql "SELECT N'x' + name FROM t;"
    java -jar target/sqltranslate.jar --from mysql --to tsql "SELECT NOW() FROM t LIMIT 1;"
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

## Evaluation

Benchmark adapters and the offline driver live under
`src/test/java/rs/etf/sqltranslator/evaluation/` and are not shaded into the
product jar. Adapters shell out to `target/sqltranslate.jar` instead of calling
`Translator.translate` in-process, so measurements exercise the shipped artifact.

Failsafe runs after `package`:

- `JarDeterminismIT` — byte-identical stdout on a stratified subset (≥20
  directions, 4 cases each); not a full-corpus determinism claim
- `SqlTranslateJarIT` — jar adapter `SUCCESS` / `REFUSED_OK`
- `BenchmarkDriverOfflineIT` — limited corpus CSV → `target/evaluation/summary/latest.csv`
- `ParrotDiverseBenchmarkIT` — Java-written smoke, no Python

CSV columns: `system, case_id, source, target, outcome, exit_or_status,
syntactic_valid, semantic_equiv, determinism_ok, latency_ms_median, notes`.
Outcomes are `SUCCESS`, `REFUSED_OK`, `REFUSED`, `WRONG_INVENTION`, `PARSE`,
`INTERNAL`, `NO_FIXTURE`.

**What the headline number measures.** The offline outcome is *coverage* — parse
→ rules → print exiting 0 — not semantic accuracy. The offline driver always
writes `syntactic_valid` and `semantic_equiv` as `n/a`, and it does not re-parse
its own output. Semantic evidence comes only from `SemanticEquivalenceTest` under
`-Pintegration` (Testcontainers against real engines); engine validity is never
claimed from this project's own parsers.

Latest measurement on the Hugging Face PARROT-Diverse corpus (`weizhoudb/PARROT`,
split `test`), 10,000 directed pairs, 2026-08-05:

| System | SUCCESS | PARSE | REFUSED | ERROR |
|---|---|---|---|---|
| sqltranslate | 7,907 (79.1%) | 1,415 | 676 | 2 |
| SQLGlot (pinned) | 9,871 (98.7%) | — | — | 129 |

Corpus fetch/materialize scripts, downloaded datasets, LLM fixtures, and local
result CSVs live under `evaluation/`, which is **gitignored** — the harness runs
locally and only its findings are published.

## Further reading

- `EXTENDING.md` — how to add statement N+1: the ordered grammar → AST → builder
  → rule → printer → corpus touch-list, plus the CTE, window, warning, and
  refusal policies.
