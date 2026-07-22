package rs.etf.sqltranslator.ast;

/** A FROM/JOIN operand: a named table or a derived subquery. */
public sealed interface Relation extends AstNode permits TableRef, DerivedTable {
}
