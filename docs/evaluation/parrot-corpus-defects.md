# PARROT-Diverse corpus defects (Wave 3 disclosure)

This document discloses known defects in the frozen **1,426-pair**
PARROT-Diverse offline cohort used by Wave 1–3 coverage measurement.

## Poisoned `source_sql` rows (sqlglot error dumps)

**40 of the 1,426 pairs** carry a `source_sql` that is **not SQL**. The cell
contains a **sqlglot** parse/error string (often with ANSI escape sequences
highlighting the bad token), copied verbatim from Hugging Face
[`weizhoudb/PARROT`](https://huggingface.co/datasets/weizhoudb/PARROT) by
`evaluation/bin/fetch_parrot.py`.

Each of these 40 rows still has a clean `gold_sql` on the target dialect.
**No SQL parser can consume the source cell**, so they are permanent
`PARSE` outcomes for every system under test.

They **remain in the headline denominator** (1426). Any secondary
“clean-corpus” rate must be reported **alongside**, never instead of, the
headline figure.

**Achievable ceiling with these 40 left in:** at most
`(1426 − 40) / 1426 = 1386 / 1426 ≈ 97.2%` SUCCESS, even with a perfect
translator over the remaining rows.

### Case IDs (40)

```
20145-BIRD Critic (PostgreSQL)-mysql-to-postgresql
20187-BIRD Critic (PostgreSQL)-mysql-to-postgresql
20198-BIRD Critic (PostgreSQL)-tsql-to-postgresql
20814-BIRD Critic (PostgreSQL)-mysql-to-postgresql
20821-BIRD Critic (PostgreSQL)-tsql-to-postgresql
20999-BIRD Critic (PostgreSQL)-mysql-to-postgresql
21010-BIRD Critic (PostgreSQL)-tsql-to-postgresql
21307-BIRD Critic (PostgreSQL)-tsql-to-postgresql
21645-BIRD Critic (PostgreSQL)-mysql-to-postgresql
21656-BIRD Critic (PostgreSQL)-tsql-to-postgresql
21836-BIRD Critic (PostgreSQL)-mysql-to-postgresql
22021-BIRD Critic (PostgreSQL)-mysql-to-postgresql
22029-BIRD Critic (PostgreSQL)-tsql-to-postgresql
22110-BIRD Critic (PostgreSQL)-tsql-to-postgresql
22609-BIRD Critic (PostgreSQL)-mysql-to-postgresql
22668-BIRD Critic (PostgreSQL)-mysql-to-postgresql
22679-BIRD Critic (PostgreSQL)-tsql-to-postgresql
22732-BIRD Critic (PostgreSQL)-mysql-to-postgresql
22741-BIRD Critic (PostgreSQL)-tsql-to-postgresql
22751-BIRD Critic (PostgreSQL)-mysql-to-postgresql
22762-BIRD Critic (PostgreSQL)-tsql-to-postgresql
22873-BIRD Critic (PostgreSQL)-mysql-to-postgresql
22884-BIRD Critic (PostgreSQL)-tsql-to-postgresql
23364-BIRD Critic (PostgreSQL)-tsql-to-postgresql
23474-BIRD Critic (PostgreSQL)-mysql-to-postgresql
23633-BIRD Critic (PostgreSQL)-mysql-to-postgresql
23728-BIRD Critic (PostgreSQL)-mysql-to-postgresql
23738-BIRD Critic (PostgreSQL)-tsql-to-postgresql
24226-BIRD Critic (PostgreSQL)-tsql-to-postgresql
25569-BIRD Critic (SQLServer)-mysql-to-tsql
25752-BIRD Critic (SQLServer)-mysql-to-tsql
25854-BIRD Critic (SQLServer)-mysql-to-tsql
25855-BIRD Critic (SQLServer)-postgresql-to-tsql
26010-BIRD Critic (SQLServer)-mysql-to-tsql
26091-BIRD Critic (SQLServer)-mysql-to-tsql
26174-BIRD Critic (SQLServer)-mysql-to-tsql
26176-BIRD Critic (SQLServer)-postgresql-to-tsql
26302-BIRD Critic (SQLServer)-mysql-to-tsql
26548-BIRD Critic (SQLServer)-mysql-to-tsql
26638-BIRD Critic (SQLServer)-mysql-to-tsql
```

Typical prefixes: `Invalid expression / Unexpected token`, `Expecting )`,
`Required keyword: … missing for <class 'sqlglot.expressions.…'>`.

## How to reproduce the list

Scan `evaluation/datasets/parrot/cases/*/input.*.sql` for cells whose leading
text matches those sqlglot error prefixes (or embeds ANSI CSI sequences in the
first ~200 characters). Do **not** drop these rows from the frozen cohort
without an explicit corpus-version bump and dual reporting.
