package rs.etf.sqltranslator.ast;

/** A scalar expression or predicate. */
public sealed interface Expression extends AstNode
        permits Literal, ColumnRef, BinaryOp, UnaryOp, BetweenPredicate, LikePredicate,
                InListPredicate, InSubqueryPredicate, IsNullPredicate, IsBoolPredicate,
                ExistsPredicate, FunctionCall, CaseExpression, CastExpression,
                ExtractExpression, SubqueryExpression {
}
