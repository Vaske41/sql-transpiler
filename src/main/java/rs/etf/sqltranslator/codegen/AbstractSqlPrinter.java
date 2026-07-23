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
        } else if (isGroupConcatWithSeparator(node)) {
            node.quantifier().ifPresent(q -> out.token(q.name()));
            node.args().get(0).accept(this);
            if (!node.orderBy().isEmpty()) {
                out.token("ORDER").token("BY");
                csv(node.orderBy());
            }
            out.token("SEPARATOR");
            node.args().get(1).accept(this);
        } else {
            node.quantifier().ifPresent(q -> out.token(q.name()));
            csv(node.args());
            if (!node.orderBy().isEmpty()) {
                out.token("ORDER").token("BY");
                csv(node.orderBy());
            }
        }
        out.raw(")");
        node.window().ifPresent(w -> {
            out.token("OVER").raw("(");
            w.accept(this);
            out.raw(")");
        });
        return null;
    }

    /** MySQL {@code GROUP_CONCAT(expr [ORDER BY …] SEPARATOR sep)} — sep is the 2nd arg. */
    private static boolean isGroupConcatWithSeparator(FunctionCall node) {
        return node.name().equals("GROUP_CONCAT") && node.args().size() == 2 && !node.star();
    }

    @Override
    public Void visitWindowSpec(WindowSpec node) {
        if (!node.partitionBy().isEmpty()) {
            out.token("PARTITION").token("BY");
            csv(node.partitionBy());
        }
        if (!node.orderBy().isEmpty()) {
            out.token("ORDER").token("BY");
            csv(node.orderBy());
        }
        if (node.frame().isPresent()) {
            throw new IllegalStateException(
                    "window frames must be refused before print");
        }
        return null;
    }

    @Override
    public Void visitWindowFrame(WindowFrame node) {
        throw new IllegalStateException("window frames must be refused before print");
    }

    @Override
    public Void visitFrameBound(FrameBound node) {
        throw new IllegalStateException("window frames must be refused before print");
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

    // --- script and query shape ---

    @Override
    public Void visitScript(Script node) {
        for (Statement statement : node.statements()) {
            statement.accept(this);
            out.raw(";\n");
        }
        return null;
    }

    @Override
    public Void visitSelectStatement(SelectStatement node) {
        node.query().accept(this);
        return null;
    }

    @Override
    public Void visitQuery(Query node) {
        if (!node.ctes().isEmpty()) {
            out.token("WITH");
            boolean first = true;
            for (Cte cte : node.ctes()) {
                if (!first) {
                    out.raw(",");
                }
                first = false;
                cte.accept(this);
            }
        }
        renderSpec(node.first(), node);
        for (UnionArm arm : node.unionArms()) {
            arm.accept(this);
        }
        if (!node.orderBy().isEmpty()) {
            out.token("ORDER BY");
            csv(node.orderBy());
        }
        renderRowLimit(node);
        return null;
    }

    @Override
    public Void visitCte(Cte node) {
        out.token(identifier(node.name()));
        node.columns().ifPresent(cols -> {
            out.raw("(");
            csv(cols);
            out.raw(")");
        });
        out.token("AS");
        subquery(node.query());
        return null;
    }

    @Override
    public Void visitUnionArm(UnionArm node) {
        out.token("UNION");
        if (node.all()) {
            out.token("ALL");
        }
        renderSpec(node.spec(), null);
        return null;
    }

    @Override
    public Void visitQuerySpecification(QuerySpecification node) {
        renderSpec(node, null);
        return null;
    }

    /** One SELECT block. {@code owner} is non-null only for a query's first spec. */
    protected void renderSpec(QuerySpecification spec, Query owner) {
        out.token("SELECT");
        spec.quantifier().ifPresent(q -> out.token(q.name()));
        selectModifiers(spec, owner);
        csv(spec.items());
        spec.from().ifPresent(from -> {
            out.token("FROM");
            from.accept(this);
        });
        spec.where().ifPresent(where -> {
            out.token("WHERE");
            where.accept(this);
        });
        if (!spec.groupBy().isEmpty()) {
            out.token("GROUP BY");
            csv(spec.groupBy());
        }
        spec.having().ifPresent(having -> {
            out.token("HAVING");
            having.accept(this);
        });
    }

    /** Hook between SELECT [DISTINCT] and the item list. T-SQL emits TOP here. */
    protected void selectModifiers(QuerySpecification spec, Query owner) {
    }

    /** Trailing row limit. Base shape: LIMIT n [OFFSET m] / bare OFFSET (PG). */
    protected void renderRowLimit(Query query) {
        query.limit().ifPresent(limit -> {
            limit.count().ifPresent(count -> {
                out.token("LIMIT");
                count.accept(this);
            });
            limit.offset().ifPresent(offset -> {
                out.token("OFFSET");
                offset.accept(this);
            });
        });
    }

    @Override
    public Void visitRowLimit(RowLimit node) {
        throw new IllegalStateException("row limits render via renderRowLimit");
    }

    @Override
    public Void visitOrderItem(OrderItem node) {
        node.expr().accept(this);
        if (node.direction() == SortDirection.DESC) {
            out.token("DESC");
        }
        node.nulls().ifPresent(this::renderNullsOrder);
        return null;
    }

    /**
     * Base: PostgreSQL shape. Targets without the clause (MySQL, T-SQL) override
     * with a contract guard — DropNullsOrderingRule must have removed it.
     */
    protected void renderNullsOrder(NullsOrder nulls) {
        out.token("NULLS").token(nulls.name());
    }

    @Override
    public Void visitSelectStar(SelectStar node) {
        out.token(node.qualifier().map(q -> dotted(q) + ".*").orElse("*"));
        return null;
    }

    @Override
    public Void visitSelectExpr(SelectExpr node) {
        node.expr().accept(this);
        node.alias().ifPresent(alias -> out.token("AS").token(identifier(alias)));
        return null;
    }

    @Override
    public Void visitTableSource(TableSource node) {
        node.first().accept(this);
        for (Join join : node.joins()) {
            join.accept(this);
        }
        return null;
    }

    @Override
    public Void visitTableRef(TableRef node) {
        out.token(dotted(node.table()));
        node.alias().ifPresent(alias -> out.token("AS").token(identifier(alias)));
        return null;
    }

    @Override
    public Void visitDerivedTable(DerivedTable node) {
        subquery(node.query());
        out.token("AS").token(identifier(node.alias()));
        node.columnAliases().ifPresent(cols -> {
            out.raw("(");
            csv(cols);
            out.raw(")");
        });
        return null;
    }

    @Override
    public Void visitJoin(Join node) {
        out.token(switch (node.kind()) {
            case INNER -> "INNER JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL JOIN";
            case CROSS -> "CROSS JOIN";
        });
        node.table().accept(this);
        node.on().ifPresent(on -> {
            out.token("ON");
            on.accept(this);
        });
        return null;
    }

    // --- DML ---

    @Override
    public Void visitInsertStatement(InsertStatement node) {
        out.token("INSERT INTO").token(dotted(node.table()));
        if (!node.columns().isEmpty()) {
            out.token("(");
            csv(node.columns());
            out.raw(")");
        }
        if (node.query().isPresent()) {
            node.query().get().accept(this);
            return null;
        }
        out.token("VALUES");
        for (int i = 0; i < node.rows().size(); i++) {
            if (i > 0) {
                out.raw(",");
            }
            out.token("(");
            csv(node.rows().get(i));
            out.raw(")");
        }
        return null;
    }

    @Override
    public Void visitUpdateStatement(UpdateStatement node) {
        out.token("UPDATE").token(dotted(node.table())).token("SET");
        csv(node.assignments());
        node.where().ifPresent(where -> {
            out.token("WHERE");
            where.accept(this);
        });
        return null;
    }

    @Override
    public Void visitAssignment(Assignment node) {
        out.token(identifier(node.column())).token("=");
        node.value().accept(this);
        return null;
    }

    @Override
    public Void visitDeleteStatement(DeleteStatement node) {
        out.token("DELETE FROM").token(dotted(node.table()));
        node.where().ifPresent(where -> {
            out.token("WHERE");
            where.accept(this);
        });
        return null;
    }

    // --- DDL ---

    @Override
    public Void visitCreateTableStatement(CreateTableStatement node) {
        out.token("CREATE TABLE").token(dotted(node.table())).token("(");
        csv(node.columns());
        if (!node.constraints().isEmpty()) {
            out.raw(",");
            csv(node.constraints());
        }
        out.raw(")");
        return null;
    }

    @Override
    public Void visitColumnDefinition(ColumnDefinition node) {
        out.token(identifier(node.name()));
        renderDataType(node.type());
        if (node.autoIncrement()) {
            renderAutoIncrement();
        }
        node.nullable().ifPresent(nullable -> out.token(nullable ? "NULL" : "NOT NULL"));
        node.defaultValue().ifPresent(value -> {
            out.token("DEFAULT");
            value.accept(this);
        });
        if (node.primaryKey()) {
            out.token("PRIMARY KEY");
        }
        if (node.unique()) {
            out.token("UNIQUE");
        }
        node.references().ifPresent(ref -> ref.accept(this));
        return null;
    }

    @Override
    public Void visitForeignKeyRef(ForeignKeyRef node) {
        out.token("REFERENCES").token(dotted(node.table()));
        node.column().ifPresent(column ->
                out.token("(").token(identifier(column)).raw(")"));
        return null;
    }

    @Override
    public Void visitPrimaryKeyConstraint(PrimaryKeyConstraint node) {
        constraintName(node.name());
        out.token("PRIMARY KEY").token("(");
        csv(node.columns());
        out.raw(")");
        return null;
    }

    @Override
    public Void visitUniqueConstraint(UniqueConstraint node) {
        constraintName(node.name());
        out.token("UNIQUE").token("(");
        csv(node.columns());
        out.raw(")");
        return null;
    }

    @Override
    public Void visitForeignKeyConstraint(ForeignKeyConstraint node) {
        constraintName(node.name());
        out.token("FOREIGN KEY").token("(");
        csv(node.columns());
        out.raw(")").token("REFERENCES").token(dotted(node.refTable()));
        if (!node.refColumns().isEmpty()) {
            out.token("(");
            csv(node.refColumns());
            out.raw(")");
        }
        return null;
    }

    private void constraintName(java.util.Optional<Identifier> name) {
        name.ifPresent(n -> out.token("CONSTRAINT").token(identifier(n)));
    }

    @Override
    public Void visitDropTableStatement(DropTableStatement node) {
        out.token("DROP TABLE");
        if (node.ifExists()) {
            out.token("IF EXISTS");
        }
        out.token(dotted(node.table()));
        return null;
    }

    @Override
    public Void visitAlterTableStatement(AlterTableStatement node) {
        out.token("ALTER TABLE").token(dotted(node.table()));
        node.action().accept(this);
        return null;
    }

    @Override
    public Void visitAddColumn(AddColumn node) {
        out.token(addColumnClause());
        node.column().accept(this);
        return null;
    }

    @Override
    public Void visitDropColumn(DropColumn node) {
        out.token("DROP COLUMN").token(identifier(node.column()));
        return null;
    }

    @Override
    public Void visitCreateIndexStatement(CreateIndexStatement node) {
        out.token("CREATE");
        if (node.unique()) {
            out.token("UNIQUE");
        }
        out.token("INDEX").token(identifier(node.name()))
                .token("ON").token(dotted(node.table())).token("(");
        csv(node.columns());
        out.raw(")");
        return null;
    }

    @Override
    public Void visitIndexColumn(IndexColumn node) {
        out.token(identifier(node.column()));
        if (node.direction() == SortDirection.DESC) {
            out.token("DESC");
        }
        return null;
    }

    /** T-SQL rejects the COLUMN keyword in ALTER TABLE ... ADD. */
    protected String addColumnClause() {
        return "ADD COLUMN";
    }
}
