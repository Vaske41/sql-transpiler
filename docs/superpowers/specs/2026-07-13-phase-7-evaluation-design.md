# Phase 7 — Testing & Evaluation Framework Design

**Date:** 2026-07-13  
**Status:** Amended after dual review (`docs/superpowers/reviews/2026-07-13-phase-7-evaluation-review.md` + chat review)  
**Depends on:** Phase 6 CLI on `main` (`9718360`) — **implementation is source of truth**  
**Prior art:** Phase 5 `GoldenFileTest`, `CaseFiles`, ~60-case corpus under `src/test/resources/cases/`

## Goal

Produce thesis-ready correctness and comparative-evaluation evidence: extend the golden corpus, add MySQL/PostgreSQL Testcontainers semantic equivalence, generate a determinism report, and ship a Java offline-first benchmark comparing **sqltranslate · SQLGlot · Gemini · Composer 2.5**.

## Decisions locked in design review

| Decision | Choice |
|---|---|
| Architecture | Single Maven module, layered harness |
| Baselines | sqltranslate, SQLGlot, Gemini, **Composer 2.5** (Cursor Agent SDK) — **no** Anthropic Claude / OpenAI; agent ≠ completion (see §7d + Composer SoT) |
| Eval code location | **`src/test/java/.../evaluation/`** (+ `evaluation/` resources). **Not** shaded into `sqltranslate.jar` |
| LLM mode | Fixture-first; live only with keys + explicit flag; **never in CI** |
| MySQL/PG integration | `@Tag("integration")`; Maven profile `-Pintegration`; non-required CI job |
| SQL Server | `@Tag("sqlserver-integration")` + profile `-Psqlserver-integration`; opt-in; **not day one** |
| Semantic cases | **Dedicated** `cases/semantic/**` scripts only — never SELECT-only goldens |
| Row compare | **Ordered equality** after mandatory `ORDER BY` on a non-null key |
| SQLGlot fairness | Day-one minimal schema extraction for in-script DDL; exact pin in `evaluation/bin/requirements.txt` |
| Primary offline metric | Per-system outcome class (below) — not “non-empty stdout” |
| Phase 6 | On `main`; harness binds to real CLI contract |

## Phase 6 CLI contract (source of truth = `main`)

Artifact: `target/sqltranslate.jar` (`finalName` + shade Main-Class `SqlTranslateMain`).

```text
java -jar target/sqltranslate.jar --from <dialect> --to <dialect> [--in FILE] [--out FILE]
                                 [--strict] [--report] [SQL]
```

- Dialects: exactly `tsql` \| `mysql` \| `postgresql` via `Dialect.fromCliName`.
- Exit codes (`CliExitCode`): `0` success, `1` parse, `2` unsupported, `3` usage/io, `4` strict, `5` internal.
- Stderr: `error: {kind}: {message}\n`; warnings: `warning CODE at L:C: message\n`.
- Default eval: `--in`, capture stdout; **no** `--strict`. Optional `--report` for warning metrics.
- Benchmark **shells the jar**. Goldens / semantic setup may use in-process `Translator.translate`.

## Architecture

```text
  cases/**            → GoldenFileTest (extend)              default verify
  cases/semantic/**   → @Tag("integration") Testcontainers  -Pintegration (CI non-required)
  corpus × jar        → Determinism report (jar path)         target/evaluation/...
  corpus × systems    → BenchmarkDriver (test classpath)    local eval / exec:java
                          SqlTranslateJarAdapter (ProcessBuilder)
                          SqlGlotAdapter (pinned helper + schema)
                          GeminiAdapter / ComposerAdapter (fixture | live)
                          Scorer → CSV under target/ or evaluation/results/
```

## 7a — Correctness / golden corpus

Keep existing layout:

```text
src/test/resources/cases/<category>/<case>/
    input.{tsql|mysql|postgresql}.sql
    expected.{source}.{target}.sql
```

- Bootstrap: `-DupdateGolden=true`, then hand-review (thesis methodology disclosure).
- **Gap-fill:** catalog DDL+DML cast + boolean flagships; standalone unresolved-cast warning path (`CAST_UNRESOLVED` assertion is the primary gate).
- `unsupported/**`: AST refusal already tested; jar adapter must classify exit `2` as **correct refusal**.

## 7b — Determinism

- **Thesis claim path:** N≥3 ProcessBuilder runs of `sqltranslate.jar` per (case × direction); byte-identical stdout; drop first run as warmup for latency companion stats if measured.
- Label clearly: “fat-jar CLI determinism,” not merely in-process `Translator`.
- Emit under **`target/evaluation/determinism/`** (never dirty the git tree from default `mvn test`). Optional checked-in sample refreshed deliberately for the thesis appendix.
- In-process double-translate remains a fast regression in existing `TranslationRoundTripCorpusTest` — do not duplicate it as a tree-writing unit test.

## 7c — Semantic equivalence (Testcontainers)

### Case authoring rules (hard)

Each `cases/semantic/<id>/` script **must**:

1. Be self-contained on an empty engine (CREATE + seed DML + **one** final `SELECT … ORDER BY <non-null key>`).
2. Use only INT / DECIMAL / VARCHAR / well-behaved temporal types — **no FLOAT/DOUBLE** in the subset.
3. Avoid `;` inside string literals/comments (v1 splitter is semicolon-based); keep scripts simple.
4. Provide `input.mysql.sql` and/or `input.postgresql.sql` for directions under test (MySQL↔PG day one).

Do **not** reuse SELECT-only golden cases (`joins/inner-join`, `select-columns-where`, …).

### Execution & compare protocol

1. Split script into setup stmts + final SELECT (last statement).
2. On source engine: run setup; query final SELECT → normalized rows.
3. Translate **full script** (so catalog rules fire).
4. On target engine: run translated setup; query translated final SELECT → normalized rows.
5. Assert **ordered** equality (`containsExactly` / list equality) — not multiset.
6. Columns compared **by position** (engines differ on labels); same select-list order required in cases.

### Normalization policy (honest)

| Value class | Rule |
|---|---|
| NULL | `NULL` |
| Integral / DECIMAL | Canonical decimal string via `BigDecimal.stripTrailingZeros().toPlainString()` |
| Boolean / BIT | Map to `0` / `1` |
| Timestamps / dates | ISO local forms |
| Strings | **Do not trim** — preserve padding/trailing spaces (translator bugs must remain visible) |

### CI / tags

- `@Tag("integration")`. Default Surefire: `excludedGroups=integration,sqlserver-integration`.
- Run via Maven profile **`-Pintegration`** which sets `groups=integration` and **overrides** `excludedGroups` to empty (`combine.self="override"`). Never rely on `-Dgroups=integration` alone.
- CI job `integration`: `./mvnw --batch-mode -Pintegration test` — **not** a required status check.
- `@Tag("sqlserver-integration")` + profile `-Psqlserver-integration`: scaffold only day one; MSSQL deps added with the scaffold, not in Task 1.

## 7d — Benchmark systems

| System | Adapter | Notes |
|---|---|---|
| sqltranslate | ProcessBuilder → fat jar | Classify by `CliExitCode` first |
| SQLGlot | `evaluation/bin/sqlglot_transpile.py` | Exact pin in `requirements.txt`; schema dict when script has DDL |
| Gemini | JDK `HttpClient` + `GEMINI_API_KEY` | Model `gemini-2.5-flash`; `temperature=0`; **chat completion** |
| **Composer 2.5** | `ComposerAdapter` → `evaluation/bin/composer_transpile.py` + `CURSOR_API_KEY` | Cursor Agent SDK (`Agent.prompt`); **coding agent — not ≡ Gemini**. Details SoT: [`2026-07-14-composer-2.5-baseline-design.md`](2026-07-14-composer-2.5-baseline-design.md) |

**Removed:** Claude Sonnet 4.6 / `ANTHROPIC_API_KEY` / `ClaudeAdapter`.

Prompt: `evaluation/prompts/v1.txt` (versioned). Multi-statement LLM/agent rows are systematically weak — **scoring subset = single-statement cases** unless a later `v2` script prompt is pinned. sqltranslate + SQLGlot still see the full corpus (incl. scripts).

### Fixture-first LLM / agent contract

- Offline: `evaluation/results/{system}/{caseKey}/{src}-to-{tgt}.sql` (+ `.meta.json`: model, prompt version, UTC timestamp, latency; Composer may add `cursorSdk`).
- Live Gemini: `-Deval.live=true` or `EVAL_LIVE=1` **and** `GEMINI_API_KEY` → call API → write fixtures.
- Live Composer: same live flag **and** `CURSOR_API_KEY` via `EvaluationMain --live-composer` (see Composer SoT).
- Missing fixture → `NO_FIXTURE` (not success).
- **Never** call LLMs or agents from GitHub Actions.
- Smoke may commit one fixture; **thesis tables require live regeneration** with pinned `.meta.json`.
- Fairness: Composer is an **agent** (tools, cold start, no temp=0). Disclose in thesis/README; do not imply Composer ≡ Gemini.

### Scoring semantics (primary metrics)

| Situation | sqltranslate | SQLGlot | LLM |
|---|---|---|---|
| Normal case, good SQL out | success | success | success if fixture/live SQL present |
| `unsupported/**` or expected refusal | exit `2` = **correct refusal** (`outcome=REFUSED_OK`) | if invents SQL → `WRONG_INVENTION`; if errors → `REFUSED` | non-empty invented SQL → `WRONG_INVENTION` |
| Parse/tool crash | `PARSE` / `INTERNAL` / non-zero | failure | `NO_FIXTURE` / HTTP error |
| Golden match (optional column) | compare to `expected.*.*.sql` when present | n/a | n/a |

Offline CSV may leave `syntactic_valid` / `semantic_equiv` as `n/a` except for the semantic subset when `-Pintegration` engines are used. **Do not** claim engine validity via this project’s own parsers.

### Latency protocol

- Local tools (jar, SQLGlot): N≥3 runs, **drop first** (warmup), report **median** `latency_ms`.
- LLMs / agents: report separately; never rank Gemini or Composer wall time against local tools in one leaderboard. Composer latency is agent-class (cold start).
- Thesis: two tables — local latency vs LLM/agent latency.

### CSV columns

`system, case_id, source, target, outcome, exit_or_status, syntactic_valid, semantic_equiv, determinism_ok, latency_ms_median, notes`

Where `outcome` ∈ {`SUCCESS`, `REFUSED_OK`, `REFUSED`, `WRONG_INVENTION`, `PARSE`, `INTERNAL`, `NO_FIXTURE`, `ERROR`, …}.

## Package / file layout

| Path | Responsibility |
|---|---|
| `src/test/java/.../eval/` | Testcontainers, normalizer, semantic ITs, determinism (jar) |
| `src/test/java/.../evaluation/` | BenchmarkDriver, adapters, scorer, CSV — **test scope only** |
| `evaluation/prompts/v1.txt` | Pinned LLM prompt |
| `evaluation/bin/sqlglot_transpile.py` | SQLGlot helper (schema-aware) |
| `evaluation/bin/composer_transpile.py` | Composer 2.5 Cursor SDK helper |
| `evaluation/bin/requirements.txt` | Exact `sqlglot==x.y.z` + `cursor-sdk==x.y.z` |
| `evaluation/results/` | Optional committed Gemini / Composer fixtures + `.meta.json` (no secrets) |
| `target/evaluation/` | Determinism + CSV outputs from test/eval runs |
| `pom.xml` | testcontainers (MySQL/PG day one); Surefire exclusions; profiles; Failsafe for jar ITs; shade unchanged (no eval classes) |
| `.github/workflows/ci.yml` | `build` + non-required `integration` (`-Pintegration`) |

## Maven / lifecycle (locked)

| Concern | Mechanism |
|---|---|
| Default verify | Docker-free, API-free; excludes integration tags |
| Integration tests | `-Pintegration` |
| SQL Server scaffold | `-Psqlserver-integration` (+ system property if needed) |
| Fat-jar adapter tests | **Failsafe** after `package` (`*IT` / `*JarIT`), not Surefire `@EnabledIf(jarExists)` |
| Eval driver | `exec-maven-plugin` with `classpathScope=test`, or `java` on test classpath |

## Global constraints

- Default `./mvnw --batch-mode clean verify` stays Docker-free and API-free.
- Evaluation must not be on the shaded runtime classpath.
- No secrets in git.
- Conventional commits; `docs/superpowers/**` untracked unless asked.
- Update `plans/ROADMAP.md` Phase 7 checklist when shipping (path **does** exist).

## Non-goals (day one)

- Anthropic Claude dual-track / OpenAI as a fourth LLM baseline  
- Claiming Composer ≡ chat-completion baselines  
- Cloud Cursor agents / live agents in CI  
- SQL Server in default integration CI  
- Live LLMs / agents in CI  
- Renaming goldens to old `expected.{target}.sql`  
- Multiset row compare for the semantic suite  
- Trimming string values in the normalizer  

## Success criteria

- [ ] Catalog gap-fill goldens + `CAST_UNRESOLVED` gate  
- [ ] ≥8 dedicated `cases/semantic/**` scripts; ordered MySQL↔PG equivalence green under `-Pintegration`  
- [ ] CI `build` green without Docker; `integration` job uses `-Pintegration` and is non-required  
- [ ] `sqlserver-integration` scaffold only  
- [ ] Jar determinism report under `target/evaluation/determinism/`  
- [ ] Failsafe jar-adapter tests green on `verify`  
- [ ] Offline BenchmarkDriver CSV with refusal/invention outcomes; SQLGlot exact pin + schema path  
- [ ] README: profiles, keys, pins, scoring, Phase 6 jar contract; Composer fairness + `--live-composer`  

## Amendment log (post-review)

| Item | Action |
|---|---|
| Surefire groups ∩ excludedGroups | Profile override |
| Semantic SELECT-only reuse | Dedicated `cases/semantic/**` |
| Jar tests in Surefire | Failsafe post-`package` |
| Eval in `src/main` / shade | Move to `src/test`; keep product jar clean |
| Ordered vs multiset | Ordered equality locked |
| Normalizer trim / floats | No trim; no FLOAT in semantic subset |
| Determinism path | `target/`; measure jar for thesis claim |
| Scoring / unsupported | `REFUSED_OK` vs `WRONG_INVENTION` |
| SQLGlot schema + pin | Day-one required |
| Latency | Warmup + median; split local vs LLM |
| Dual “7c” headings | Renamed 7c / 7d |
| “ROADMAP missing” | Rejected — `plans/ROADMAP.md` present |
| Claude → Composer 2.5 | §7d + baselines; SoT [`2026-07-14-composer-2.5-baseline-design.md`](2026-07-14-composer-2.5-baseline-design.md) |
