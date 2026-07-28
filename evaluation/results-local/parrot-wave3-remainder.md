# Wave 3 PARROT-Diverse remainder (full sweep)

Coverage SUCCESS = parse → rules → print exit 0. **Not** AccEX / gold_sql / catalog-semantic.

## Headline

- sqltranslate: SUCCESS=**1074**, PARSE=243, REFUSED=109 (n=1426)
- Wave-2 pinned baseline: SUCCESS=966
- Delta: **+108** (966 → 1074; **75.32%**)
- 85% bar: ≥1213; **not met (need 139 more)**
- Clean-corpus secondary (exclude 40 poison sources): 1074 / 1386 = **77.49%**
  (reported **alongside** the headline, never instead — see `docs/evaluation/parrot-corpus-defects.md`)

## Outcome counts

| Outcome | Count |
|---|---:|
| `SUCCESS` | 1074 |
| `PARSE` | 243 |
| `REFUSED` | 109 |

## Intentional honesty lifts (Wave-2 SUCCESS → Wave-3 REFUSED)

Three prior SUCCESS rows now refuse because Wave 3 stopped emitting invalid T-SQL:

| case_id | reason |
|---|---|
| `20710-…-postgresql-to-tsql` | `REGEXP_REPLACE` — no faithful T-SQL form |
| `24063-…-postgresql-to-tsql` | `REGEXP_REPLACE` — no faithful T-SQL form |
| `21349-…-postgresql-to-tsql` | `JSONB_OBJECT_AGG` — no faithful T-SQL form |

These are **not** netted against gains; they are disclosed refuse-over-guess corrections.
All other Wave-2 SUCCESS rows still SUCCESS after the full sweep (SETS-as-identifier and
MySQL `JSON_OBJECTAGG` fixes applied).

## Non-SUCCESS taxonomy (352 rows)

| Bucket | Count | Notes |
|---|---:|---|
| Corrupt corpus (`source_sql` = sqlglot error dump) | **40** | Permanent PARSE; listed in `parrot-corpus-defects.md` |
| Other PARSE (grammar / long-tail) | **201** | DML-in-CTE, richer DDL, SRF hybrids, vendor syntax, … |
| Procedural DDL PARSE | **2** | Routines/triggers beyond single-statement shells |
| REFUSED — JSON / builders | **19** | e.g. `JSONB_PRETTY`, containment→T-SQL, object_agg→T-SQL |
| REFUSED — array type / aggs | **16** | No array type on MySQL/T-SQL for remaining shapes |
| REFUSED — geo / vector types | **10** | `GEOGRAPHY` / `GEOMETRY` / `TSVECTOR` — keep refuse |
| REFUSED — RETURNING/OUTPUT edges | **6** | MySQL refuse; exotic OUTPUT shapes |
| REFUSED — upsert | **5** | DO NOTHING, upsert→T-SQL, ON DUPLICATE→PG without target |
| REFUSED — regex | **5** | `~` / `REGEXP_REPLACE` toward T-SQL |
| REFUSED — other | **40** | Mixed (FULL JOIN edges, DISTINCT ON*, interval, session vars, routines, …) |
| REFUSED — routine shells | **2** | Procedural / multi-statement bodies |
| REFUSED — FULL JOIN / DISTINCT ON / interval / session | **6** | Faithful-rewrite leftovers |

\*DISTINCT ON + `SELECT *` and similar stay refused.

## Refusal families (why no faithful form)

| Family | Targets | Why refuse |
|---|---|---|
| Array type / `ARRAY_AGG`→T-SQL | MySQL/T-SQL | No native array type; MySQL uses `JSON_ARRAYAGG` where mapped |
| Geo / `TSVECTOR` | MySQL/T-SQL | No portable type mapping |
| `@>` containment | T-SQL | No `JSON_CONTAINS` equivalent (MySQL mapped) |
| `JSONB_PRETTY` / object_agg→T-SQL | MySQL/T-SQL | No faithful printer form |
| Regex (`~`, `REGEXP_REPLACE`) | T-SQL | No portable regex operator / replace |
| Upsert DO NOTHING / upsert→T-SQL | MySQL/T-SQL | No MERGE without schema keys; MySQL lacks DO NOTHING |
| RETURNING→MySQL | MySQL | No `RETURNING` / `OUTPUT` |
| Procedural routine bodies | all | IF/NEW/OLD/multi-statement — not a statement transpiler |
| Session `@vars` off-MySQL | PG/T-SQL | No faithful user-variable model |

## Gate history (fast unless noted)

| Gate | SUCCESS | Δ vs 966 |
|---|---:|---:|
| C1 | 994 | +28 |
| C2 | 1011 | +45 |
| C3 | 1028 | +62 |
| C4 | 1037 | +71 (below ~1090 re-forecast; Task 17 required) |
| C5 | 1043 | +77 |
| C6 | 1075 | +109 |
| **Final full sweep** | **1074** | **+108** |

C4 under-forecast and the 85% miss are **honest ceilings**, not a silent bar lower.
Geo/XML/percentile/WITH TIES keep-refusals were never counted toward the +247 model.

## Reproduce

```text
.\mvnw.cmd -q -DskipTests package
python evaluation/bin/wave3_measure.py          # full sweep — published figure
# optional: python evaluation/bin/wave3_measure.py --fast
```

Artifacts: `evaluation/results-local/parrot-wave3-latest.csv`, this file,
`docs/evaluation/parrot-corpus-defects.md`.
