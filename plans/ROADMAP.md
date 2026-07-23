# SQL Dialect Translator — Implementation Roadmap

**Master's Thesis Project** — A source-to-source SQL translator between **SQL Server (T-SQL)**, **MySQL**, and **PostgreSQL** (6 translation directions), covering core **DML** and **DDL**, built in **Java + ANTLR4** with an **Apache Spark Catalyst-inspired** pipeline:

```
SQL text ──▶ Lexer/Parser (ANTLR4) ──▶ Dialect-agnostic AST ──▶ Analysis & Rule-based
             (per source dialect)       (logical plan)           Transformation
                                                                      │
                          CLI ◀── Target SQL text ◀── Code Generator (per target dialect)
```

**Timebox: ~2 weeks** — implementation and results generation only; writing the thesis chapters from those results sits outside this timebox. The scope-control principle throughout: a *narrow but complete vertical slice* beats broad, half-working coverage. Every phase below defines a **minimum scope** (must ship) and an **extension scope** (only if time remains).

---

## Guiding Scope Decision (read first)

To fit 2 weeks, fix the supported SQL subset **up front** and refuse everything else with a clean error:

| Category | In scope (v1) | Out of scope |
|---|---|---|
| DML | `SELECT` (joins, `WHERE`, `GROUP BY`/`HAVING`, `ORDER BY`, `LIMIT`/`TOP`/`FETCH`), `INSERT ... VALUES` (incl. multi-row), `INSERT ... SELECT`, `UPDATE`, `DELETE` | CTEs, window functions, `MERGE` |
| DDL | `CREATE TABLE` (columns, types, `NOT NULL`, `DEFAULT`, `PRIMARY KEY`, `FOREIGN KEY`, `UNIQUE`, auto-increment), `CREATE INDEX` / `CREATE UNIQUE INDEX` (column list + optional `DESC`; dialect-only options refused — see README), `DROP TABLE`, basic `ALTER TABLE ADD/DROP COLUMN` | Partitioning, triggers, procedures; index options beyond the shared shape (`CLUSTERED`, `USING`, partial `WHERE`, index `NULLS`, prefix lengths) |
| Expressions | Literals, identifiers, arithmetic/comparison/logical ops, `CASE`, `CAST`, subqueries in expression position (scalar, `IN (SELECT …)`, `EXISTS`), ~15 common functions (see Phase 4) | Vendor-specific function long tail; derived tables (subqueries in `FROM`) → Extension Queue |

**Explicit refuse-list** (each either rejected with a named `UnsupportedFeatureException` or downgraded to a documented `TranslationReport` warning; this enumeration becomes a thesis table):
- `TOP ... PERCENT` / `TOP ... WITH TIES` (T-SQL)
- `CONVERT(type, expr, style)` with style codes (T-SQL date formats — essentially untranslatable)
- NULL-comparison semantics differences (`= NULL` behavior, `IS DISTINCT FROM`)
- PostgreSQL `NULLS FIRST`/`NULLS LAST` when targeting MySQL/T-SQL — no equivalent clause; dropped with a **warning** that NULL ordering is not preserved (the reverse direction *is* translatable — see the Phase 4 NULL-ordering rule)
- MySQL non-strict `GROUP BY` (non-aggregated select items missing from `GROUP BY`) — Batch-1 validation **warning**: the translated output will be rejected by PG/T-SQL at execution
- Collation / case-sensitivity of string comparisons (translation preserves syntax, not collation behavior — documented limitation)

An explicit "unsupported construct" error is a *feature* (determinism + honest evaluation), not a failure.

---

## Phase 1 — Project Setup & Environment (Day 1)

### Stack
- **Java 17+**, **Maven** (simpler ANTLR integration than Gradle for a solo project)
- `antlr4-maven-plugin` — generates lexer/parser at build time into `target/generated-sources`
- **JUnit 5** + **AssertJ** for testing; **picocli** for the CLI

### Directory structure
```
sql_translator/
├── pom.xml
├── src/main/antlr4/rs/etf/sqltranslator/grammar/   # .g4 grammars (one per dialect)
├── src/main/java/rs/etf/sqltranslator/
│   ├── ast/            # dialect-agnostic AST nodes (statements, expressions, types)
│   ├── parser/         # ANTLR parse-tree → AST builders (one visitor per source dialect)
│   ├── analysis/       # normalization & validation rules
│   ├── transform/      # dialect-pair transformation rules (type mapping, casts, rewrites)
│   ├── codegen/        # AST → SQL printers (one per target dialect)
│   ├── cli/            # picocli entry point
│   └── core/           # Translator facade, Dialect enum, error types
├── src/test/java/...
└── src/test/resources/cases/   # golden-file test cases (see Phase 7)
```

### Design patterns
- **Facade** — a single `Translator.translate(sql, sourceDialect, targetDialect)` entry point hiding the pipeline.
- **Strategy** — `Dialect` enum wiring each dialect to its parser-builder and printer.

### Challenges
- ANTLR plugin package/namespace mismatches (grammar `@header` vs. Maven config) — settle this on day 1 with a hello-world grammar compiled end-to-end.

### Deliverables checklist
- [ ] Maven project builds with `mvn clean verify`
- [ ] ANTLR plugin generates parser from a trivial grammar; a smoke test parses `SELECT 1`
- [ ] Package skeleton + `Dialect` enum (`TSQL`, `MYSQL`, `POSTGRESQL`)
- [ ] Git repo with `.gitignore` (target/, generated sources)
- [ ] GitHub Actions workflow running `mvn clean verify` on push — cheap, and it becomes reproducibility evidence for the determinism claim in the thesis

---

## Phase 2 — Grammar & Parsing (Days 2–4)

### Strategy: one trimmed grammar per dialect, derived from grammars-v4
Adapt the community grammars from [antlr/grammars-v4](https://github.com/antlr/grammars-v4) (`tsql`, `mysql`, `postgresql`) — but **do not import them wholesale**. They are huge (the full T-SQL grammar alone is >10k lines) and will blow the timebox. Instead:

1. Start each dialect grammar **from scratch, small**, using grammars-v4 as a *reference* for tricky lexer rules (quoted identifiers, string escapes, comments).
2. Cover exactly the Phase-0 subset. A focused ~400–600 line grammar per dialect is realistic and *far* easier to debug.
3. Share structure (not files) across the three grammars: same rule names (`selectStatement`, `columnDefinition`, …) so the three AST-builder visitors stay near-identical.

**Why three grammars, not one union grammar (conscious decision):** a translator's parser only has to *accept* valid SQL, never reject invalid SQL — so a single permissive union grammar with a dialect flag (lexer modes for the quoting conflicts) was considered, and would cut grammar work roughly in half. Three grammars are chosen deliberately: they match the thesis definition (per-dialect front-ends), keep each grammar unambiguous and debuggable (union grammars breed cross-dialect ambiguity rabbit holes), and make a cleaner architecture story. The duplication cost is contained by the shared rule names above and the `AbstractAstBuilder` (Phase 3). This trade-off is thesis material — write it up.

Key per-dialect lexer differences to encode:
| | T-SQL | MySQL | PostgreSQL |
|---|---|---|---|
| Quoted identifier | `[name]` or `"name"` | `` `name` `` | `"name"` |
| String literal | `'x'`, `N'x'` | `'x'`, `"x"` (!) | `'x'`, `E'x'` |
| Case sensitivity | insensitive | insensitive keywords | keywords insensitive, unquoted ids folded to lower |

### Syntax error handling
- Replace ANTLR's default console listener with a custom `BaseErrorListener` that collects `SyntaxError{line, col, message}` and throws a single `ParseException` after parsing — no partial translation of broken input.
- Use `parser.setErrorHandler(new BailErrorStrategy())` wrapped to re-parse with the default strategy only for error reporting (fast happy path, good diagnostics on failure). *(Extension — plain default strategy is fine for v1.)*

### Design patterns
- **Builder** — grammars produce ANTLR parse trees; conversion to AST happens in Phase 3 visitors.
- **Chain of Responsibility** (implicit in ANTLR error listeners).

### Challenges
- MySQL's `"double-quoted strings"` vs. everyone else's double-quoted *identifiers* — must be a lexer-mode decision per dialect.
- **MySQL `||` is logical OR** (without `PIPES_AS_CONCAT`), not string concat — the MySQL grammar/builder must parse `a || b` as `OR`, while PostgreSQL's parses it as concat. This is a parse-side decision, not just a Phase-4 rewrite (see the `||` rule there).
- T-SQL `TOP n` lives in the select clause; MySQL/PostgreSQL `LIMIT` at the end — keep both shapes parseable, unify in the AST (Phase 3).
- Keyword-vs-identifier ambiguity (e.g., `ORDER` as a column name) — keep a small reserved-word list; don't chase full non-reserved-keyword support.

**Fallback if a grammar slips past day 4:** immediately cut `ALTER TABLE` and shrink expression coverage — **never cut a dialect** (that guts the 6-direction claim of the thesis).

### Deliverables checklist
- [ ] `TSql.g4`, `MySql.g4`, `PostgreSql.g4` covering the v1 subset
- [ ] Multi-statement top rule per grammar: `script : statement (';' statement)* ';'? EOF` — load-bearing for the catalog (Phase 4), not just the CLI
- [ ] Custom error listener + `ParseException` with line/column info
- [ ] Parse-only unit tests: ~15 valid + 5 invalid statements per dialect — **written as files under `src/test/resources/cases/` from day 2**, so every parse test doubles as a future golden-case input (see Phase 7a)

---

## Phase 3 — AST Construction (Days 4–6, overlaps Phase 2)

### Design: one generalized AST ("logical plan"), three front-end builders
The AST is the **single dialect-agnostic representation** — the analog of Catalyst's logical plan. Parse trees are throwaway; each dialect's `ParseTreeVisitor` immediately builds generalized AST nodes.

Node hierarchy (Java 17 `sealed` interfaces + `record` classes — immutable, pattern-matchable):

```java
sealed interface Statement permits SelectStatement, InsertStatement, UpdateStatement,
                                   DeleteStatement, CreateTableStatement, DropTableStatement,
                                   AlterTableStatement {}

sealed interface Expression permits Literal, ColumnRef, BinaryOp, UnaryOp,
                                    FunctionCall, CaseExpression, CastExpression, SubqueryExpression {}

// Generalization examples:
record SelectStatement(List<SelectItem> items, Optional<TableSource> from,
                       Optional<Expression> where, List<Expression> groupBy,
                       Optional<Expression> having, List<OrderItem> orderBy,
                       Optional<RowLimit> limit) implements Statement {}

record RowLimit(Optional<Expression> count, Optional<Expression> offset) {}  // unifies TOP/LIMIT/FETCH
record DataType(GenericType type, Optional<Integer> length, Optional<Integer> scale) {}
```

Generalization decisions that pay off later:
- **`RowLimit`** unifies `TOP n` / `LIMIT n OFFSET m` / `FETCH FIRST n ROWS ONLY`.
- **`GenericType` enum** (`INTEGER`, `BIGINT`, `VARCHAR`, `TEXT`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIMESTAMP`, `BLOB`, …) — dialect types map *into* it on parse and *out of* it on codegen. This is the heart of type translation.
- **`AutoIncrement` as a column property** flag, not syntax — `IDENTITY(1,1)` / `AUTO_INCREMENT` / `GENERATED ... AS IDENTITY`/`SERIAL` all fold into it.
- Every node keeps a `SourcePosition` for error messages that point at the *original* SQL.

**`Catalog` — lightweight schema context.** A `Map<tableName, Map<columnName, DataType>>` populated from the `CREATE TABLE` statements of a multi-statement script before DML is transformed. This is the analog of Catalyst's catalog (the analyzer resolves column types against it) and is what makes type-dependent rewrites in Phase 4 (cast insertion, `+`-vs-concat, boolean semantics) *decidable* instead of guesswork. Statements referencing tables with no preceding DDL simply have no catalog entry — Phase 4 rules must degrade to a warning in that case, never a blind rewrite.

**Shared base builder.** Because the three grammars deliberately share rule names, the three `ParseTree → AST` visitors must not be three near-identical copies: write an `AbstractAstBuilder` with the common structural mapping and dialect hooks for the few divergent shapes (`TOP` vs `LIMIT`, quoting, auto-increment syntax) — the same Template Method move the Phase 5 printers use. One bug fix lands once, not three times.

### Design patterns
- **Composite** — the AST itself.
- **Visitor** — a generic `AstVisitor<R>` interface with a `visit` method per node type; used by analysis, transforms, and codegen. With sealed interfaces, `switch` pattern matching is a lightweight alternative — pick **one** style and use it everywhere (recommendation: classic Visitor interface, since the thesis text references the pattern explicitly).
- **Builder** for the bulkier nodes (`SelectStatement`).

### Challenges
- Resisting AST bloat: model only what the v1 subset needs. Add nodes when a grammar rule demands it, not speculatively.
- Deciding normalization split: keep parse-time builders *dumb* (structural mapping only); all semantic normalization belongs in Phase 4 rules so it's testable in isolation.

### Deliverables checklist
- [ ] AST node hierarchy (~25–35 record classes) with `SourcePosition`
- [ ] `AstVisitor<R>` interface
- [ ] `Catalog` record + population from `CREATE TABLE` AST nodes
- [ ] `AbstractAstBuilder` + three thin dialect subclasses (`ParseTree → AST`)
- [ ] `EXTENDING.md` — the "how to add a statement" recipe (see **Extension Queue** section), written now while the pattern is fresh
- [ ] Round-trip smoke tests: parse → AST → `toString` debug dump matches expected structure — inputs stored as golden-case files (Phase 7a)

---

## Phase 4 — Analysis & Transformation, Catalyst-style (Days 6–10)

### Architecture: rule-based tree rewriting
Mirror Catalyst's core idea — a **sequence of small rules, each an AST → AST function**, applied by a driver:

```java
interface Rule { Statement apply(Statement node, TranslationContext ctx); }

final class RuleEngine {
    // Batches, like Catalyst: [Validate] -> [Normalize] -> [TargetRewrite]
    Statement run(Statement ast, TranslationContext ctx) { ... }
}
```

`TranslationContext` carries source dialect, target dialect, the `Catalog` (Phase 3), and a `TranslationReport` (warnings, e.g., "precision loss: DATETIME → TIMESTAMP(3)").

Rules run **once, fixed order** (skip Catalyst's fixed-point iteration — unnecessary at this scale, and single-pass guarantees the determinism requirement). This is a **deterministic linear rewrite pipeline** inspired by Catalyst's rule idea — not Catalyst's analyzer annotation / batch scheduling. Pipeline stages depend on prior rewrites; do not treat the list as independent batches.

### Rule batches

**Batch 1 — Validation (dialect-independent):** unsupported-construct check → structured `UnsupportedFeatureException` naming the construct and position. Also: MySQL loose-`GROUP BY` detection (syntactically detectable: bare column in the select list, absent from the `GROUP BY` list, query has a `GROUP BY`) → **warning** that PG/T-SQL will reject the output at execution.

**Batch 2 — Normalization (dialect-independent):** identifier-quoting canonicalization; fold dialect synonyms into canonical AST forms (should mostly already happen in builders — this batch is the safety net).

**Batch 2.5 — Catalog build & analysis (dialect-independent):** walk the script's `CREATE TABLE` statements and populate the `Catalog`; annotate/resolve column references in subsequent DML where possible. This mirrors Catalyst's analyzer-resolves-against-catalog step and is what turns the type-dependent rules in Batch 3 from guesses into decisions. Columns not resolvable (no preceding DDL in the input) stay untyped — downstream rules must then warn instead of rewrite.

**Batch 3 — Target rewrite (per direction):** the thesis core. Organize as a **rule table**, not per-pair classes — most rules are *target-conditional*, so 6 directions ≈ 3 target-rule-sets + a few source-conditional rules:

| Concern | Example rules |
|---|---|
| **Type mapping** | Phase 4: structural narrowing (`NVARCHAR`→`VARCHAR`/`TEXT`, `TEXT`→`NVARCHAR(MAX)`, `TINYINT`→`SMALLINT` for PG). Phase 5 printers: lexical names (`BOOLEAN`→`BIT`, `DOUBLE`→`FLOAT`, …). MySQL `TINYINT(1)` folds to `BOOLEAN` at AST build. |
| **Auto-increment** | flag → `IDENTITY(1,1)` / `AUTO_INCREMENT` / `GENERATED BY DEFAULT AS IDENTITY` (insertable form in all directions — PG `ALWAYS` is not preserved; AST carries only a boolean, see Phase 5 Challenges) |
| **Row limiting** | `RowLimit` → `TOP` (T-SQL, refuse OFFSET without `ORDER BY`) / `LIMIT` (others) |
| **Functions** | mapping table for ~15 functions: `GETDATE()`↔`NOW()`, `ISNULL`↔`IFNULL`↔`COALESCE`, `LEN`↔`CHAR_LENGTH`, `SUBSTRING` arg conventions. String concat `+`(T-SQL)↔`CONCAT`(MySQL)↔`\|\|`(PG): rewritable when operand types are known — string literal operands, or column types resolved via the `Catalog`; `col1 + col2` with unresolved types could be arithmetic → **warning**, no rewrite |
| **Casting & implicit conversion** | Where the source dialect converts implicitly but the target doesn't, insert explicit `CAST`. Flagship example: MySQL allows `WHERE varchar_col = 5`; PostgreSQL errors on cross-type comparison → rule looks up `varchar_col` in the `Catalog` and wraps the literal side in `CAST`. Decidable cases = literal-vs-literal, plus literal-vs-column *when the column resolves in the catalog* (DDL preceding DML in the script). Unresolved columns → **`CAST_UNRESOLVED` warning**, no rewrite — an honest, documented boundary instead of a silent guess. No general type-inference engine (out of scope). |
| **Boolean semantics** | Bare boolean in predicate position: PG → MySQL/T-SQL `WHERE active` → `active <> 0`. Reverse MySQL → PG uses catalog (`TINYINT(1)`→`BOOLEAN` at build → bare / simplify `= 1`). `TRUE`/`FALSE` literals → `1`/`0` for T-SQL. |
| **NULL ordering (`ORDER BY`)** | PG sorts NULLs last on ASC; MySQL/T-SQL sort them first. Targeting PG from MySQL/T-SQL: emit `NULLS FIRST` on ASC / `NULLS LAST` on DESC. PG → MySQL/T-SQL: drop `NULLS …` → **warning** |
| **`\|\|`: concat vs OR** | PG `a \|\| b` (concat) → MySQL `CONCAT(a, b)`, T-SQL per the concat mapping above; MySQL `a \|\| b` parses as `OR` (Phase 2 parse-side decision) → PG/T-SQL `a OR b` |

### Design patterns
- **Strategy** — each `Rule`.
- **Visitor** (or transforming fold) — a generic `AstTransformer` base that rebuilds records bottom-up so individual rules override only the nodes they care about.
- **Registry/lookup tables** — type map and function map as `EnumMap`/static maps, *data not code* → easy to present as thesis tables.

### Challenges
- The implicit-conversion problem is unbounded — **scope it to catalog-resolved and literal cases** and document the boundary in the thesis (this is a legitimate research finding, not a cop-out).
- Function arity/argument-order differences (`SUBSTRING`, `CONVERT` vs `CAST`) need per-function adapters, not just name mapping.
- Keeping rules independent: no rule should depend on another's output except across batch boundaries.

### Deliverables checklist
- [x] `RuleEngine` + `TranslationContext` (incl. `Catalog`) + `TranslationReport` (warnings)
- [x] Catalog resolve vs unresolved: resolved → rewrite; unresolved cast shapes → `CAST_UNRESOLVED` warning
- [x] Structural type narrowing (Phase 4); lexical `GenericType`×3 name table deferred to Phase 5 printers
- [x] Function mapping (~15 core) + adapters; unknown → `FUNCTION_PASSTHROUGH`
- [x] Cast-insertion + boolean semantics (MySQL `TINYINT(1)` folds to `BOOLEAN` at build)
- [x] NULL-ordering both directions (inject for →PG; drop+warn for PG→others); loose-`GROUP BY` warning for MySQL→PG/T-SQL
- [x] `UnsupportedFeatureException` path tested
- [x] Per-rule SQL-in tests + `RuleEngineRewriteEvidenceTest` (AST deltas); SQL-out goldens arrive with Phase 5 printers

---

## Phase 5 — Code Generation (Days 10–11)

### Design: one printer per target dialect
Three `AstVisitor<Void>`-based printers writing into a `StringBuilder` via a small `SqlWriter` helper (keywords, separators, parenthesization, indentation):

- `AbstractSqlPrinter` — ~85% shared logic (SELECT shape, expressions, **precedence-driven minimal parentheses** — see Challenges).
- `TSqlPrinter`, `MySqlPrinter`, `PostgreSqlPrinter` — override only: identifier quoting (`[x]` / `` `x` `` / `"x"`), string-literal escaping, `RowLimit` rendering, type-name rendering, auto-increment clause.

**Determinism guarantee** (thesis requirement): printers are pure functions of the AST — no randomness, no environment reads, stable iteration orders (`List` everywhere, never `HashMap` iteration in output paths). Same input ⇒ byte-identical output, asserted by the golden-file tests.

**Correct escaping is non-negotiable:** quoting rules per dialect (`''` doubling, MySQL backslash escapes, `]]` in T-SQL brackets) live in exactly one method per printer, unit-tested with hostile inputs (`O'Brien`, `col"name`).

### Design patterns
- **Visitor** — printers.
- **Template Method** — `AbstractSqlPrinter` defines statement shape; subclasses fill dialect hooks.
- **Facade** — `Translator` in `core/` is the single parse→rules→print entry point (Phase 6 CLI calls only this). In this single-module layout, `core` is the shared kernel *and* the facade module.

### Challenges
- Operator precedence differs *across dialects* (T-SQL `+` as both ADD and CONCAT is the load-bearing case) — ship **precedence-driven minimal parentheses** with an explicit ladder in `AbstractSqlPrinter` (OR=1 … primary=9; level 4 non-associative; T-SQL CONCAT shares ADD's level). **Parenthesize-everything is the rollback** if the ladder proves incomplete — not the default.
- Auto-increment mode is AST-boolean only: printers always emit the *insertable* spelling (`IDENTITY(1,1)` / `AUTO_INCREMENT` / `GENERATED BY DEFAULT AS IDENTITY`). PG `GENERATED ALWAYS` is deliberately not preserved (including PG→PG). Documented thesis limitation; reopening the AST for ALWAYS vs BY DEFAULT is extension scope.
- Lexical type-name tables (BOOLEAN→BIT/`TINYINT(1)`, TIMESTAMP→DATETIME/`DATETIME2`, FLOAT/DOUBLE spelling swaps, BLOB→VARBINARY(MAX)/BYTEA) are **mandatory normalizations**, not warnings — structural narrowing stays in Phase 4 with report entries; printer renames are the thesis type table.
- Formatting choices (keyword case, newlines) — pick one canonical style, document it, keep it stable for golden files.

### Deliverables checklist
- [x] `AbstractSqlPrinter` + 3 dialect printers
- [x] Escaping unit tests per dialect
- [x] End-to-end tests: all 6 directions on a shared statement set — outputs reviewed and checked in as the first golden expected-files (Phase 7a)
- [x] Determinism test: translate each case twice, assert byte equality

---

## Phase 6 — CLI & Integration (Day 11, second half)

A picocli facade over `Translator.translate()` is a half-day job — day 11's first half belongs to Phase 5 codegen wrap-up.

### picocli interface
```
sqltranslate --from tsql --to postgresql --in query.sql        # file → stdout
sqltranslate --from mysql --to tsql "SELECT ..."               # inline → stdout
cat q.sql | sqltranslate --from postgresql --to mysql          # stdin → stdout
  Options: --out FILE, --strict (warnings→errors), --report (print TranslationReport)
```

- Exit codes: `0` success, `1` parse error, `2` unsupported feature, `3` usage/I/O error, `4` `--strict` with warnings, `5` internal failure — makes the evaluation harness (Phase 7) scriptable without scraping stderr for the common buckets.
- Multi-statement input: already supported by the grammars (Phase 2 deliverable) and the pipeline (catalog builds from the script's DDL) — the CLI just reports per-statement errors with positions.
- Package as a fat jar (`maven-shade-plugin`): `java -jar sqltranslate.jar …`.

### Design patterns
- **Facade** — CLI calls only `Translator.translate(...)`; zero pipeline logic in the CLI layer.

### Deliverables checklist
- [x] picocli command with the flags above + `--help`
- [x] Fat jar build
- [x] Exit-code integration tests
- [x] README with usage examples for all 6 directions

---

## Phase 7 — Testing & Evaluation Framework (Days 12–14)

### 7a. Correctness: golden-file harness
```
src/test/resources/cases/<category>/<case>/
    input.tsql.sql            # any one (or more) source dialects
    expected.mysql.sql        # expected output per applicable target
    expected.postgresql.sql
```
A JUnit 5 `@TestFactory` walks the tree and generates one dynamic test per (case × direction). Categories mirror thesis chapters: `select-basic`, `joins`, `insert-update-delete`, `create-table-types`, `constraints`, `functions`, `limits`, `casts`, `unsupported` (expects failure + specific error).

**Corpus-design constraint (load-bearing):** catalog-dependent rules — cast insertion, boolean semantics — only fire when DDL precedes DML *in the same script*. The `casts` and boolean cases must therefore be authored as **DDL+DML scripts**, or the thesis's flagship mechanism contributes nothing measurable to the evaluation. Keep standalone-query variants of the same cases in the corpus too, to demonstrate the warning-not-guess path.

Target: **~40 cases ⇒ ~200+ generated test executions.** Add cases as bugs are found — the suite becomes the thesis's correctness evidence.

**Authoring workflow (be honest about it):** ~40 cases × up to 3 expected outputs ≈ up to ~120 SQL files — hand-writing and verifying all of them is days of hidden work. Instead: case *inputs* accumulate continuously from Phase 2 onward (every parse/AST/e2e test input is a case); expected files are **bootstrapped from the tool's own output, then hand-reviewed** (snapshot testing), with Testcontainers execution below as the *independent* correctness check. Disclose this methodology in the thesis — snapshot + execution-verification is a defensible combination; silently hand-blessed files are not.

**Semantic spot-check via Testcontainers — minimum scope for MySQL + PostgreSQL:** run translated DDL+DML against real engines — execute source SQL on the source engine, translated SQL on the target engine, compare result sets for a subset of ~10 cases. MySQL and PostgreSQL images are light and start in seconds; this is what makes the evaluation chapter credible, so it must not be cut. The **SQL Server image stays extension** (heavy, licensing) — T-SQL *output* without a container is reported honestly as syntax-checked-only. Note: validating output with this project's *own* grammars proves self-consistency, not correctness — never present that as validity evidence.

### 7b. Determinism validation
- Repeat-run byte-equality (already in Phase 5 golden tests) — in-process printers.
- Fat-jar CLI determinism (`JarDeterminismIT`): **stratified representative subset**
  (≥20 directions, capped per source→target pair), 3 JVM ProcessBuilder runs, byte-identical
  stdout → `target/evaluation/determinism/`. **Not** a full-corpus jar claim; disclose subset
  size in the thesis (optional full-corpus opt-in later for an appendix).

### 7c. Benchmark methodology vs. SQLGlot, Gemini 2.5 Flash, Composer 2.5
Baselines: **sqltranslate** fat jar, **SQLGlot** (pinned), **Gemini** (`gemini-2.5-flash`, chat completion), **Composer 2.5** (Cursor Agent SDK — not ≡ Gemini). Fixed evaluation corpus = the golden-file inputs (public, versioned in-repo). For each system × case × direction, record:

| Metric | How measured |
|---|---|
| **Parse/translate success** | tool produced output without error |
| **Syntactic validity** | output parses on a real target engine: `EXPLAIN`/prepare via Testcontainers for MySQL/PG (minimum scope); T-SQL best-effort (SQL Server container if available, else reported as unverified — *not* via this project's own parsers, which would be circular) |
| **Semantic equivalence** | executed result-set comparison on the Testcontainers subset (MySQL/PG minimum scope) |
| **Determinism** | 3 runs per case; % identical outputs (Gemini at temperature 0 still may vary; Composer is an agent loop with no temp=0 analogue — a key thesis point) |
| **Latency** | wall-clock per statement (Gemini / Composer latency reported separately; never ranked vs local tools) |

Harness: a Java (or thin Python) driver that shells out to `sqltranslate` and `sqlglot`, calls Gemini via HTTP, and invokes Composer via `evaluation/bin/composer_transpile.py` (Cursor SDK) with a **fixed, versioned prompt** (`evaluation/prompts/v1.txt`). Store every raw output in `evaluation/results/` for reproducibility. Score with a small comparator script → CSV → thesis tables/charts.

Fairness notes to state in the thesis: identical corpus for all systems; model / SDK versions and prompt pinned and reported; **Composer is a coding agent, not a chat-completion baseline** (see README fairness table); this tool's *known limitations* corpus included so failure modes are shown honestly for all systems; **SQLGlot receives equivalent schema context** (it accepts schema dicts for qualification/type annotation) on every case where this tool benefits from in-script DDL — anything else biases the comparison in our favor.

### Deliverables checklist
- [x] Golden-file harness + ~40 cases across all categories, incl. DDL+DML script cases that exercise the catalog (inputs accumulated since Phase 2; expecteds snapshot-bootstrapped + reviewed)
- [x] Testcontainers semantic-equivalence suite **implemented** (`cases/semantic/**`, `-Pintegration`) — **pending Docker proof** on this machine/CI (suite skips when Docker unavailable; not verified green-on-engines yet)
- [x] Determinism report generation (`JarDeterminismIT` stratified subset ≥20 directions → `target/evaluation/determinism/`)
- [x] Benchmark driver (sqltranslate jar + SQLGlot + Gemini + Composer 2.5) with pinned prompts/versions (`evaluation/prompts/v1.txt`)
- [x] Results CSV under `target/evaluation/summary/` (`REFUSED_OK` for sqltranslate exit 2; LLM/agent empty unsupported → `REFUSED`; `WRONG_INVENTION` for invention)
- [x] (Scaffold) SQL Server Testcontainers via `-Psqlserver-integration` / `@Tag("sqlserver-integration")` — smoke only day one; not in CI
- [ ] Thesis evaluation chapter tables from live-regenerated Gemini / Composer fixtures + full offline CSV

---

## Extension Queue & How to Add a Statement

Extensibility is a deliverable, not an accident: if the schedule holds, statements are added from the ranked queue below — decided here, not ad hoc on day 12. The queue is also the **first thing sacrificed** when behind: v1 scope never grows before the day-14 milestones are green.

### The recipe (lands in `EXTENDING.md` — Phase 3 deliverable)
Adding statement N+1 is this ordered touch-list — a ~30-minute checklist, not archaeology:
1. Grammar rule in each of the 3 `.g4` files (shared rule-name convention)
2. AST record + `sealed` `permits` entry — the compiler then enumerates every visitor that must handle it
3. `AstVisitor<R>` method
4. `AbstractAstBuilder` mapping + dialect hooks only where shapes diverge
5. Rewrite-rule **table rows** (the type/function registries are data, not code — usually no new rule classes)
6. Printer method in `AbstractSqlPrinter` + dialect overrides only where needed
7. Golden cases: 1 input × applicable directions, snapshot-bootstrapped + reviewed

### The ranked queue (value-per-hour order)
1. ~~`INSERT ... SELECT`~~ — shipped (2026-07) — nearly free: one grammar alternative + one AST field reusing `Query`
2. ~~`CREATE INDEX`~~ — shipped (2026-07) — small grammar surface, high practical relevance
3. **CTEs (`WITH`)** — near-identical syntax in all 3 dialects; mostly plumbing
4. **Derived tables in `FROM`** — unlocks the subquery scope line drawn in the scope table
5. **Window functions** — large expression-grammar surface; last

**Wave 1 PG quick wins (PARSE coverage):** PostgreSQL `FETCH FIRST`/`NEXT … ROWS ONLY` folds into existing `RowLimit`; postfix `expr::type` folds into `CastExpression`.

---

## Two-Week Schedule at a Glance

| Days | Phase | Milestone |
|---|---|---|
| 1 | 1 Setup | `mvn verify` green with ANTLR smoke test; CI workflow green |
| 2–4 | 2 Grammars | 3 grammars parse the v1 subset (incl. multi-statement scripts); test inputs stored as golden-case files |
| 4–6 | 3 AST | All parsed input reaches generalized AST; `Catalog` populated from DDL |
| 6–10 | 4 Transformation | Type/function/cast rules for all 3 targets, catalog-aware — the thesis core gets the extra day, funded by parenthesize-everything codegen |
| 10–11 | 5 Codegen | First full 6-direction end-to-end translation; outputs seed golden expecteds |
| 11 (second half) | 6 CLI | Fat jar usable from the shell |
| 12–14 | 7 Evaluation | Harness + Testcontainers (MySQL/PG) + benchmark — case *authoring* is continuous from day 2, not crammed here |

**Slack strategy:** the Extension Queue goes first — v1 scope never grows before the day-14 milestones are green. If still behind by day 8, cut `ALTER TABLE` and shrink the function table to 8 entries — never cut the evaluation phase (it produces the thesis's results chapter) and never cut a dialect (it guts the 6-direction claim).

---

## Risk Register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Grammar scope creep (grammars-v4 rabbit hole) | High | Write small grammars from scratch; reference, don't import |
| Implicit-conversion rules unbounded | High | Catalog-resolved + literal cases only, warnings otherwise; documented as a finding |
| SQL Server Testcontainers image heavy/licensing on dev machine | Medium | MySQL/PG containers are minimum scope (light images); only the SQL Server image is optional (`@Tag("integration")`) — T-SQL outputs reported as unverified rather than self-validated |
| Golden-case authoring time underestimated | High | Inputs accumulate from Phase 2; expecteds snapshot-bootstrapped then reviewed; execution check via Testcontainers |
| LLM API cost/variability in benchmark | Medium | Small fixed corpus (~40 stmts), cached raw responses, 3 runs each |
| AST redesign mid-project | Medium | Freeze node set at end of Phase 3; new needs → new *rules*, not new nodes, where possible |
| Result-set comparison rabbit holes (row order, driver type rendering, float/date formats) | Medium | Equivalence-subset cases get a deterministic `ORDER BY` on a non-null key; comparator normalizes values (numeric widening, canonical date strings); multiset comparison where `ORDER BY` is absent |
