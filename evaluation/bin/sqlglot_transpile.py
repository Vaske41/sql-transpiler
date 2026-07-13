#!/usr/bin/env python3
"""Schema-aware SQLGlot transpile helper for Phase 7 evaluation.

Reads SQL from stdin. Dialects: tsql | mysql | postgresql (postgresql → postgres).
Builds a minimal MappingSchema from in-script CREATE TABLE when parseable and
qualifies expressions with that schema before generating target SQL.
"""

from __future__ import annotations

import argparse
import sys

DIALECT_MAP = {
    "tsql": "tsql",
    "mysql": "mysql",
    "postgresql": "postgres",
}


def _cli_to_sqlglot(token: str) -> str:
    key = token.strip().lower()
    if key not in DIALECT_MAP:
        raise SystemExit(f"error: sqlglot: unknown dialect '{token}'")
    return DIALECT_MAP[key]


def _build_schema(sql: str, read_dialect: str):
    import sqlglot
    from sqlglot import exp
    from sqlglot.schema import MappingSchema

    schema = MappingSchema(dialect=read_dialect)
    try:
        statements = sqlglot.parse(sql, read=read_dialect)
    except Exception:
        return schema

    for stmt in statements:
        if not isinstance(stmt, exp.Create):
            continue
        kind = stmt.args.get("kind")
        if kind is not None and str(kind).upper() != "TABLE":
            continue
        schema_expr = stmt.this
        if not isinstance(schema_expr, exp.Schema):
            continue
        table = schema_expr.this
        cols: dict[str, str] = {}
        for col_def in schema_expr.expressions:
            if isinstance(col_def, exp.ColumnDef) and col_def.kind is not None:
                cols[col_def.name] = col_def.kind.sql(dialect=read_dialect)
        if cols:
            try:
                schema.add_table(table, cols)
            except Exception:
                pass
    return schema


def transpile(sql: str, read_cli: str, write_cli: str) -> str:
    import sqlglot
    from sqlglot.optimizer.qualify import qualify

    read_dialect = _cli_to_sqlglot(read_cli)
    write_dialect = _cli_to_sqlglot(write_cli)
    schema = _build_schema(sql, read_dialect)

    try:
        statements = sqlglot.parse(sql, read=read_dialect)
    except Exception as exc:
        raise RuntimeError(str(exc)) from exc

    parts: list[str] = []
    for stmt in statements:
        if stmt is None:
            continue
        expression = stmt
        if not schema.empty:
            try:
                expression = qualify(expression, schema=schema, dialect=read_dialect)
            except Exception:
                expression = stmt
        parts.append(expression.sql(dialect=write_dialect))
    return ";\n".join(parts) + (";\n" if parts else "")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="SQLGlot transpile helper")
    parser.add_argument("--read", required=True, help="source dialect: tsql|mysql|postgresql")
    parser.add_argument("--write", required=True, help="target dialect: tsql|mysql|postgresql")
    args = parser.parse_args(argv)

    sql = sys.stdin.read()
    try:
        import sqlglot  # noqa: F401
    except ImportError as exc:
        print(f"error: sqlglot: {exc}", file=sys.stderr)
        return 1

    try:
        out = transpile(sql, args.read, args.write)
    except SystemExit:
        raise
    except Exception as exc:
        print(f"error: sqlglot: {exc}", file=sys.stderr)
        return 1

    sys.stdout.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
