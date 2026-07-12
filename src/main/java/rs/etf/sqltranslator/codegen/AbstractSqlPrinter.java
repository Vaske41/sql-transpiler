package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.*;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Template Method printer: all statement and expression shapes live here; dialect
 * subclasses override only quoting, string escaping, type names, auto-increment,
 * row-limit shape, and the concat operator. Printers are pure functions of the AST
 * (determinism guarantee); node kinds the Phase 4 rule engine guarantees never
 * reach a given target throw IllegalStateException instead of printing wrong SQL.
 */
public abstract class AbstractSqlPrinter implements AstVisitor<Void> {

    private static final Pattern PLAIN_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    protected final SqlWriter out = new SqlWriter();

    private boolean used;

    public final String print(Script script) {
        claimUse();
        script.accept(this);
        return out.result();
    }

    public final String printExpression(Expression expression) {
        claimUse();
        expression.accept(this);
        return out.result();
    }

    /** Printers are single-use — the writer accumulates across calls. */
    private void claimUse() {
        if (used) {
            throw new IllegalStateException("printers are single-use; create a new one");
        }
        used = true;
    }

    // --- dialect hooks ---

    protected abstract String quoteIdentifier(String value);

    protected abstract String renderStringLiteral(StringLiteral literal);

    protected abstract String concatOperator();

    protected abstract void renderDataType(DataType type);

    protected abstract void renderAutoIncrement();

    // --- identifiers ---

    protected final String identifier(Identifier id) {
        return id.quoted() || !PLAIN_IDENTIFIER.matcher(id.value()).matches()
                ? quoteIdentifier(id.value())
                : id.value();
    }

    protected final String dotted(QualifiedName name) {
        return name.parts().stream().map(this::identifier)
                .collect(Collectors.joining("."));
    }

    @Override
    public Void visitIdentifier(Identifier node) {
        out.token(identifier(node));
        return null;
    }

    @Override
    public Void visitQualifiedName(QualifiedName node) {
        out.token(dotted(node));
        return null;
    }

    @Override
    public Void visitColumnRef(ColumnRef node) {
        out.token(dotted(node.name()));
        return null;
    }

    // --- literals ---

    @Override
    public Void visitNumericLiteral(NumericLiteral node) {
        out.token(node.text());                      // lexical text: determinism (D5)
        return null;
    }

    @Override
    public Void visitStringLiteral(StringLiteral node) {
        out.token(renderStringLiteral(node));
        return null;
    }

    @Override
    public Void visitBooleanLiteral(BooleanLiteral node) {
        out.token(node.value() ? "TRUE" : "FALSE");  // T-SQL overrides with a guard
        return null;
    }

    @Override
    public Void visitNullLiteral(NullLiteral node) {
        out.token("NULL");
        return null;
    }

    // --- operators, precedence-driven minimal parentheses ---

    protected int precedence(Expression e) {
        if (e instanceof BinaryOp op) {
            return switch (op.op()) {
                case OR -> 1;
                case AND -> 2;
                case EQ, NEQ, LT, LTE, GT, GTE -> 4;
                case CONCAT -> concatPrecedence();
                case ADD, SUB -> 6;
                case MUL, DIV, MOD -> 7;
            };
        }
        if (e instanceof UnaryOp op) {
            return op.op() == UnaryOperator.NOT ? 3 : 8;
        }
        if (e instanceof BetweenPredicate || e instanceof LikePredicate
                || e instanceof InListPredicate || e instanceof InSubqueryPredicate
                || e instanceof IsNullPredicate) {
            return 4;
        }
        return 9;                                    // literals, refs, calls, CASE, CAST,
    }                                                // EXISTS, scalar subqueries

    /**
     * CONCAT's level in the target's ladder. Base: 5 (PG's ||, looser than additive).
     * The T-SQL printer returns 6 — CONCAT renders as the same '+' token as ADD there,
     * and keeping distinct levels would drop parentheses that change semantics.
     */
    protected int concatPrecedence() {
        return 5;
    }

    /** Renders a child, parenthesizing when its binding is looser than the parent's. */
    protected final void operand(Expression child, int parentPrecedence, boolean rightSide) {
        int childPrecedence = precedence(child);
        // Level 4 is non-associative: the grammars' predicate rule cannot chain
        // comparisons, so equal precedence needs parens on either side there.
        boolean parens = childPrecedence < parentPrecedence
                || (childPrecedence == parentPrecedence
                        && (rightSide || parentPrecedence == 4));
        if (parens) {
            out.token("(");
            child.accept(this);
            out.raw(")");
        } else {
            child.accept(this);
        }
    }

    private String operatorSymbol(BinaryOperator op) {
        return switch (op) {
            case OR -> "OR"; case AND -> "AND";
            case EQ -> "="; case NEQ -> "<>"; case LT -> "<"; case LTE -> "<=";
            case GT -> ">"; case GTE -> ">=";
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%";
            case CONCAT -> concatOperator();
        };
    }

    @Override
    public Void visitBinaryOp(BinaryOp node) {
        int prec = precedence(node);
        operand(node.left(), prec, false);
        out.token(operatorSymbol(node.op()));
        operand(node.right(), prec, true);
        return null;
    }

    @Override
    public Void visitUnaryOp(UnaryOp node) {
        if (node.op() == UnaryOperator.NOT) {
            out.token("NOT");
            operand(node.operand(), 3, false);
        } else {
            out.token(node.op() == UnaryOperator.NEG ? "-" : "+").fuse();
            if (node.operand() instanceof UnaryOp) {
                // -(-5) must never fuse into "--": that opens a line comment.
                out.token("(");
                node.operand().accept(this);
                out.raw(")");
            } else {
                operand(node.operand(), 8, false);
            }
        }
        return null;
    }

    // --- predicates ---

    @Override
    public Void visitBetweenPredicate(BetweenPredicate node) {
        operand(node.value(), 4, false);
        if (node.negated()) {
            out.token("NOT");
        }
        out.token("BETWEEN");
        operand(node.low(), 5, false);
        out.token("AND");
        operand(node.high(), 5, false);
        return null;
    }

    @Override
    public Void visitLikePredicate(LikePredicate node) {
        operand(node.value(), 4, false);
        if (node.negated()) {
            out.token("NOT");
        }
        out.token("LIKE");
        operand(node.pattern(), 5, false);
        return null;
    }

    @Override
    public Void visitInListPredicate(InListPredicate node) {
        operand(node.value(), 4, false);
        if (node.negated()) {
            out.token("NOT");
        }
        out.token("IN").raw(" (");
        csv(node.items());
        out.raw(")");
        return null;
    }

    @Override
    public Void visitInSubqueryPredicate(InSubqueryPredicate node) {
        operand(node.value(), 4, false);
        if (node.negated()) {
            out.token("NOT");
        }
        out.token("IN");
        subquery(node.subquery());
        return null;
    }

    @Override
    public Void visitIsNullPredicate(IsNullPredicate node) {
        operand(node.value(), 4, false);
        out.token("IS");
        if (node.negated()) {
            out.token("NOT");
        }
        out.token("NULL");
        return null;
    }

    @Override
    public Void visitExistsPredicate(ExistsPredicate node) {
        out.token("EXISTS");
        subquery(node.subquery());
        return null;
    }

    // --- calls, CASE, CAST, subqueries ---

    @Override
    public Void visitFunctionCall(FunctionCall node) {
        out.token(node.name()).raw("(");
        if (node.star()) {
            out.raw("*");
        } else {
            node.quantifier().ifPresent(q -> out.token(q.name()));
            csv(node.args());
        }
        out.raw(")");
        return null;
    }

    @Override
    public Void visitCaseExpression(CaseExpression node) {
        out.token("CASE");
        node.operand().ifPresent(o -> o.accept(this));
        for (WhenClause when : node.whens()) {
            when.accept(this);
        }
        node.elseValue().ifPresent(e -> {
            out.token("ELSE");
            e.accept(this);
        });
        out.token("END");
        return null;
    }

    @Override
    public Void visitWhenClause(WhenClause node) {
        out.token("WHEN");
        node.condition().accept(this);
        out.token("THEN");
        node.result().accept(this);
        return null;
    }

    @Override
    public Void visitCastExpression(CastExpression node) {
        out.token("CAST").raw("(");
        node.operand().accept(this);
        out.token("AS");
        renderDataType(node.targetType());
        out.raw(")");
        return null;
    }

    @Override
    public Void visitSubqueryExpression(SubqueryExpression node) {
        subquery(node.query());
        return null;
    }

    protected final void subquery(Query query) {
        out.token("(");
        query.accept(this);
        out.raw(")");
    }

    protected final void csv(List<? extends AstNode> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                out.raw(",");
            }
            nodes.get(i).accept(this);
        }
    }

    // --- types (shared argument rendering; names are dialect hooks) ---

    protected final void renderTypeArgs(DataType type) {
        type.length().ifPresent(length -> {
            out.raw("(");
            if (length instanceof FixedLength fixed) {
                out.raw(String.valueOf(fixed.value()));
                type.scale().ifPresent(s -> out.raw(",").raw(String.valueOf(s)));
            } else {
                out.raw("MAX");
            }
            out.raw(")");
        });
    }

    @Override
    public Void visitDataType(DataType node) {
        renderDataType(node);
        return null;
    }

    @Override
    public Void visitFixedLength(FixedLength node) {
        throw new IllegalStateException("TypeLength renders via renderTypeArgs");
    }

    @Override
    public Void visitMaxLength(MaxLength node) {
        throw new IllegalStateException("TypeLength renders via renderTypeArgs");
    }

    // --- statement/query shapes: implemented in Tasks 3-4 ---

    @Override public Void visitScript(Script node) { throw todo(); }
    @Override public Void visitSelectStatement(SelectStatement node) { throw todo(); }
    @Override public Void visitQuery(Query node) { throw todo(); }
    @Override public Void visitUnionArm(UnionArm node) { throw todo(); }
    @Override public Void visitQuerySpecification(QuerySpecification node) { throw todo(); }
    @Override public Void visitRowLimit(RowLimit node) { throw todo(); }
    @Override public Void visitOrderItem(OrderItem node) { throw todo(); }
    @Override public Void visitSelectStar(SelectStar node) { throw todo(); }
    @Override public Void visitSelectExpr(SelectExpr node) { throw todo(); }
    @Override public Void visitTableSource(TableSource node) { throw todo(); }
    @Override public Void visitTableRef(TableRef node) { throw todo(); }
    @Override public Void visitJoin(Join node) { throw todo(); }
    @Override public Void visitInsertStatement(InsertStatement node) { throw todo(); }
    @Override public Void visitUpdateStatement(UpdateStatement node) { throw todo(); }
    @Override public Void visitAssignment(Assignment node) { throw todo(); }
    @Override public Void visitDeleteStatement(DeleteStatement node) { throw todo(); }
    @Override public Void visitCreateTableStatement(CreateTableStatement node) { throw todo(); }
    @Override public Void visitColumnDefinition(ColumnDefinition node) { throw todo(); }
    @Override public Void visitForeignKeyRef(ForeignKeyRef node) { throw todo(); }
    @Override public Void visitPrimaryKeyConstraint(PrimaryKeyConstraint node) { throw todo(); }
    @Override public Void visitUniqueConstraint(UniqueConstraint node) { throw todo(); }
    @Override public Void visitForeignKeyConstraint(ForeignKeyConstraint node) { throw todo(); }
    @Override public Void visitDropTableStatement(DropTableStatement node) { throw todo(); }
    @Override public Void visitAlterTableStatement(AlterTableStatement node) { throw todo(); }
    @Override public Void visitAddColumn(AddColumn node) { throw todo(); }
    @Override public Void visitDropColumn(DropColumn node) { throw todo(); }

    private static UnsupportedOperationException todo() {
        return new UnsupportedOperationException("implemented in Task 3/4");
    }
}
