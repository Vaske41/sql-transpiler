# Task 6 Report: PostgreSQL SRF → T-SQL `OPENJSON` / `STRING_SPLIT`

**Status:** DONE  
**Commit:** `4ea2cb0` — `feat(from): render PostgreSQL SRFs as T-SQL OPENJSON/STRING_SPLIT`  
**Branch:** `feat/parrot-wave3-85pct`  
**Base:** `3697117`

## What changed

- **Rule:** `RenderSrfForTsqlRule` (`render-srf-tsql`) after `RenderSrfForMysqlRule`:
  - `json(b)_array_elements[_text]` → `OPENJSON(x)` (default `value` is unquoted text — faithful for `*_text`; non-`_text` still mapped per brief)
  - `string_to_table(s,d)` / `string_to_array` as TVF → `STRING_SPLIT(s,d)`
  - `unnest(string_to_array(s,d))` → `STRING_SPLIT(s,d)`
  - bare `unnest(array_col)` / unknown SRFs → honest refuse `(no T-SQL form)`
  - Pass-through for native `OPENJSON` / `STRING_SPLIT` (T-SQL → T-SQL)
- **Correlated comma / non-lateral CROSS:** when rewritten TVF args contain `ColumnRef`, set `Join.lateral = true` so `TSqlPrinter` emits `CROSS APPLY` (not invalid comma-joined OPENJSON).
- **Validator:** removed T-SQL `visitTableFunction` hard refuse so this rule can run.
- **Printer:** no `TSqlPrinter` changes — OPENJSON/STRING_SPLIT reuse `visitTableFunction`.
- **Goldens:** no `src/test/resources` diff; `EXPECTED_REFUSALS` unchanged.

## Tests

| Step | Result |
|------|--------|
| RED `RenderSrfForTsqlRuleTest` | FAIL — `table function (no SRF-in-FROM in target)` |
| GREEN focused | PASS — 6 T-SQL + 8 MySQL |
| `mvnw verify` | BUILD SUCCESS — 3510 unit + 5 IT |
| Golden update | PASS — 497, no resource churn |

## Concerns

- Brief used `new Translator()` (private); tests use `Translator.translate(...)`.
- Non-`_text` `json_array_elements` → OPENJSON keeps the known quoting semantic gap (OPENJSON value is unquoted).
- `STRING_TO_ARRAY` as a bare table-function name is mapped if it appears; primary split path is `string_to_table` / `unnest(string_to_array)`.

## Review fix: correlated INNER JOIN SRF → APPLY

**Finding:** Correlated `INNER JOIN json_array_elements(...)` (no `LATERAL`) emitted `INNER JOIN OPENJSON(...)` — invalid T-SQL when args reference the left table. Only CROSS was upgraded to APPLY.

**Fix commit:** `fix(from): correlated INNER JOIN SRF becomes APPLY toward T-SQL`

**Change:** `RenderSrfForTsqlRule.visitJoin` — when rewritten TVF args contain `ColumnRef`:
- `CROSS` → `CROSS APPLY` (`lateral=true`)
- `INNER` + trivial ON (`TRUE` / `1` / empty) → fold to `CROSS` + `lateral` (CROSS APPLY)
- `LEFT` + trivial ON → `OUTER APPLY` (`lateral=true`)
- otherwise → honest refuse (`correlated table function join cannot fold to APPLY`)

**Tests:**
| Step | Result |
|------|--------|
| RED `correlatedInnerJoinSrfBecomesCrossApply` | FAIL — `INNER JOIN OPENJSON(... ) ON 1` (no CROSS APPLY) |
| GREEN `RenderSrfForTsqlRuleTest` | PASS — 7 tests |
| `mvnw -Dtest=RenderSrfForTsqlRuleTest verify` | BUILD SUCCESS |
