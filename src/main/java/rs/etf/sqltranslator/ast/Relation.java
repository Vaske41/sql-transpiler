package rs.etf.sqltranslator.ast;

/** A FROM/JOIN operand: a named table, derived subquery, or VALUES table. */
public sealed interface Relation extends AstNode permits TableRef, DerivedTable, ValuesTable {
}
