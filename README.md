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
the product jar).

### SQLGlot helper

Pinned dependency:

    pip install -r evaluation/bin/requirements.txt

Helper: `evaluation/bin/sqlglot_transpile.py` (`--read` / `--write` with
`tsql` | `mysql` | `postgresql`; stdin SQL → stdout SQL). When the script contains
`CREATE TABLE`, a minimal schema is extracted and passed into SQLGlot so DDL-backed
cases stay fair versus sqltranslate.
