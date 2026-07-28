# Task 9 Report: Computed interval values (C3)

**Status:** DONE  
**Base HEAD:** `96b895a`  
**Commit:** `7cf5923` — `feat(interval): allow computed interval values across dialects`

## What landed

- **AST:** `IntervalLiteral` is now `(Expression value, Optional<String> unit, SourcePosition pos)` — `raw()` → `value()`.
- **Builder:** `intervalFromExpression` accepts any expression (no non-literal refuse). Compound / simple string paths wrap content in `StringLiteral` / `NumericLiteral`.
- **Rewrite:** `RewriteIntervalArithmeticRule`
  - `dateAdd` passes `interval.value()` through; negates non-numeric amounts via `BinaryOp(MUL, value, -1)`.
  - `DATE_ADD`/`ADDDATE`/`DATE_SUB`/`SUBDATE` → T-SQL `DATEADD`; toward PG → `date ± interval` (valid SQL, not MySQL `DATE_ADD` passthrough).
- **Printers:** PG literal `INTERVAL 'n unit'` / computed `(value || ' unit')::interval`; MySQL `INTERVAL value UNIT`; T-SQL still contract-guards residual `IntervalLiteral`.
- **Tests:** extended `IntervalLiteralTest` (+2 computed cases); existing `RewriteIntervalArithmeticRuleTest` still green.

## Sample output

| Direction | Shape |
|---|---|
| MySQL `DATE_ADD(d, INTERVAL (TIMESTAMPDIFF(...)) YEAR)` → PG | `d + (TIMESTAMPDIFF(...) \|\| ' year')::interval` |
| MySQL `DATE_ADD(d, INTERVAL (n) DAY)` → T-SQL | `DATEADD(day, n, d)` |

## Goldens / EXPECTED_REFUSALS

- Existing interval goldens unchanged (still valid).
- Left standalone `interval/*|TSQL` and `interval-compound/*` in `EXPECTED_REFUSALS` (still refused).

## Verification

```
.\mvnw.cmd "-Dtest=IntervalLiteralTest,RewriteIntervalArithmeticRuleTest" test  → PASS (15)
.\mvnw.cmd verify  → BUILD SUCCESS
  Surefire: 3516 / Failsafe: 5 — 0 failures
```

## Concerns

1. MySQL→PG still leaves `TIMESTAMPDIFF` as passthrough (not this task’s mapping table); interval shape is valid PG.
2. ~~Unknown interval units still normalize via passthrough (`default -> u`); DATEADD may be invalid for exotic units — brief did not require a refuse list.~~ **Fixed (review Important):** `RewriteIntervalArithmeticRule.dateAdd` allow-lists portable DATEADD units (`year`/`quarter`/`month`/`day`/`week`/`hour`/`minute`/`second`/`millisecond`) and throws `UnsupportedFeatureException` for unknowns (e.g. `fortnight`). Parse-time `normalizeIntervalUnit` still passthroughs for native PG/MySQL render.
3. No wave-3 re-measure here (Task 10 gate).

## Review fix follow-up

- **Commit:** `fix(interval): refuse unknown INTERVAL units toward DATEADD`
- **Test:** `unknownIntervalUnitRefusedTowardTsql` in `RewriteIntervalArithmeticRuleTest`
- **Verify:** `.\mvnw.cmd "-Dtest=IntervalLiteralTest,RewriteIntervalArithmeticRuleTest" test` → PASS (16)
