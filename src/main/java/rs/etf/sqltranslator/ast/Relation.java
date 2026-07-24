package rs.etf.sqltranslator.ast;

/** A FROM/JOIN operand: a named table, derived subquery, VALUES table, or table function. */
public sealed interface Relation extends AstNode
        permits TableRef, DerivedTable, ValuesTable, TableFunction {
}
