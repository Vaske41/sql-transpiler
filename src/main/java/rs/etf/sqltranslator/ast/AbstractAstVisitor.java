package rs.etf.sqltranslator.ast;

/**
 * Walk-only visitor base with {@code null}/no-op defaults for every node type.
 * Analysis scanners (e.g. {@code CatalogBuilder}) subclass this and override only
 * the nodes they observe — without the identity-rebuild cost of {@link AstTransformer}.
 *
 * <p>Phase 4 AST→AST rules must subclass {@link AstTransformer}, not this class.
 */
public abstract class AbstractAstVisitor<R> implements AstVisitor<R> {

    /** Default result for un-overridden visits. */
    protected R defaultResult() {
        return null;
    }

    @Override
    public R visitScript(Script node) {
        for (Statement statement : node.statements()) {
            statement.accept(this);
        }
        return defaultResult();
    }

    @Override
    public R visitSelectStatement(SelectStatement node) {
        node.query().accept(this);
        return defaultResult();
    }

    @Override
    public R visitQuery(Query node) {
        for (Cte cte : node.ctes()) {
            cte.accept(this);
        }
        node.first().accept(this);
        for (UnionArm arm : node.unionArms()) {
            arm.accept(this);
        }
        for (OrderItem item : node.orderBy()) {
            item.accept(this);
        }
        node.limit().ifPresent(limit -> limit.accept(this));
        return defaultResult();
    }

    @Override
    public R visitCte(Cte node) {
        node.name().accept(this);
        node.columns().ifPresent(cols -> {
            for (Identifier col : cols) {
                col.accept(this);
            }
        });
        node.query().accept(this);
        return defaultResult();
    }

    @Override
    public R visitUnionArm(UnionArm node) {
        node.spec().accept(this);
        return defaultResult();
    }

    @Override
    public R visitQuerySpecification(QuerySpecification node) {
        for (Expression on : node.distinctOn()) {
            on.accept(this);
        }
        for (SelectItem item : node.items()) {
            item.accept(this);
        }
        node.from().ifPresent(from -> from.accept(this));
        node.where().ifPresent(where -> where.accept(this));
        for (Expression group : node.groupBy()) {
            group.accept(this);
        }
        node.having().ifPresent(having -> having.accept(this));
        return defaultResult();
    }

    @Override
    public R visitRowLimit(RowLimit node) {
        node.count().ifPresent(count -> count.accept(this));
        node.offset().ifPresent(offset -> offset.accept(this));
        return defaultResult();
    }

    @Override
    public R visitOrderItem(OrderItem node) {
        node.expr().accept(this);
        return defaultResult();
    }

    @Override
    public R visitSelectStar(SelectStar node) {
        node.qualifier().ifPresent(q -> q.accept(this));
        return defaultResult();
    }

    @Override
    public R visitSelectExpr(SelectExpr node) {
        node.expr().accept(this);
        node.alias().ifPresent(alias -> alias.accept(this));
        return defaultResult();
    }

    @Override
    public R visitTableSource(TableSource node) {
        node.first().accept(this);
        for (Join join : node.joins()) {
            join.accept(this);
        }
        return defaultResult();
    }

    @Override
    public R visitTableRef(TableRef node) {
        node.table().accept(this);
        node.alias().ifPresent(alias -> alias.accept(this));
        return defaultResult();
    }

    @Override
    public R visitDerivedTable(DerivedTable node) {
        node.query().accept(this);
        node.alias().accept(this);
        node.columnAliases().ifPresent(cols -> {
            for (Identifier col : cols) {
                col.accept(this);
            }
        });
        return defaultResult();
    }

    @Override
    public R visitValuesTable(ValuesTable node) {
        for (RowValue row : node.rows()) {
            row.accept(this);
        }
        node.alias().accept(this);
        for (Identifier col : node.columns()) {
            col.accept(this);
        }
        return defaultResult();
    }

    @Override
    public R visitRowValue(RowValue node) {
        for (Expression value : node.values()) {
            value.accept(this);
        }
        return defaultResult();
    }

    @Override
    public R visitJoin(Join node) {
        node.table().accept(this);
        node.on().ifPresent(on -> on.accept(this));
        return defaultResult();
    }

    @Override
    public R visitInsertStatement(InsertStatement node) {
        return defaultResult();
    }

    @Override
    public R visitUpdateStatement(UpdateStatement node) {
        return defaultResult();
    }

    @Override
    public R visitAssignment(Assignment node) {
        return defaultResult();
    }

    @Override
    public R visitDeleteStatement(DeleteStatement node) {
        return defaultResult();
    }

    @Override
    public R visitCreateTableStatement(CreateTableStatement node) {
        return defaultResult();
    }

    @Override
    public R visitColumnDefinition(ColumnDefinition node) {
        return defaultResult();
    }

    @Override
    public R visitForeignKeyRef(ForeignKeyRef node) {
        return defaultResult();
    }

    @Override
    public R visitPrimaryKeyConstraint(PrimaryKeyConstraint node) {
        return defaultResult();
    }

    @Override
    public R visitUniqueConstraint(UniqueConstraint node) {
        return defaultResult();
    }

    @Override
    public R visitForeignKeyConstraint(ForeignKeyConstraint node) {
        return defaultResult();
    }

    @Override
    public R visitDropTableStatement(DropTableStatement node) {
        return defaultResult();
    }

    @Override
    public R visitDropViewStatement(DropViewStatement node) {
        return defaultResult();
    }

    @Override
    public R visitDropRoutineStatement(DropRoutineStatement node) {
        return defaultResult();
    }

    @Override
    public R visitDropIndexStatement(DropIndexStatement node) {
        return defaultResult();
    }

    @Override
    public R visitTruncateStatement(TruncateStatement node) {
        return defaultResult();
    }

    @Override
    public R visitAlterTableStatement(AlterTableStatement node) {
        return defaultResult();
    }

    @Override
    public R visitAddColumn(AddColumn node) {
        return defaultResult();
    }

    @Override
    public R visitDropColumn(DropColumn node) {
        return defaultResult();
    }

    @Override
    public R visitAlterColumnType(AlterColumnType node) {
        return defaultResult();
    }

    @Override
    public R visitCreateIndexStatement(CreateIndexStatement node) {
        return defaultResult();
    }

    @Override
    public R visitIndexColumn(IndexColumn node) {
        return defaultResult();
    }

    @Override
    public R visitBinaryOp(BinaryOp node) {
        return defaultResult();
    }

    @Override
    public R visitUnaryOp(UnaryOp node) {
        return defaultResult();
    }

    @Override
    public R visitBetweenPredicate(BetweenPredicate node) {
        return defaultResult();
    }

    @Override
    public R visitLikePredicate(LikePredicate node) {
        return defaultResult();
    }

    @Override
    public R visitInListPredicate(InListPredicate node) {
        return defaultResult();
    }

    @Override
    public R visitInSubqueryPredicate(InSubqueryPredicate node) {
        node.value().accept(this);
        node.subquery().accept(this);
        return defaultResult();
    }

    @Override
    public R visitIsNullPredicate(IsNullPredicate node) {
        node.value().accept(this);
        return defaultResult();
    }

    @Override
    public R visitIsBoolPredicate(IsBoolPredicate node) {
        node.value().accept(this);
        return defaultResult();
    }

    @Override
    public R visitExistsPredicate(ExistsPredicate node) {
        node.subquery().accept(this);
        return defaultResult();
    }

    @Override
    public R visitFunctionCall(FunctionCall node) {
        for (Expression arg : node.args()) {
            arg.accept(this);
        }
        for (OrderItem item : node.orderBy()) {
            item.accept(this);
        }
        node.filter().ifPresent(f -> f.accept(this));
        node.window().ifPresent(w -> w.accept(this));
        return defaultResult();
    }

    @Override
    public R visitWindowSpec(WindowSpec node) {
        for (Expression part : node.partitionBy()) {
            part.accept(this);
        }
        for (OrderItem item : node.orderBy()) {
            item.accept(this);
        }
        node.frame().ifPresent(f -> f.accept(this));
        return defaultResult();
    }

    @Override
    public R visitWindowFrame(WindowFrame node) {
        node.start().accept(this);
        node.end().ifPresent(end -> end.accept(this));
        return defaultResult();
    }

    @Override
    public R visitFrameBound(FrameBound node) {
        node.offset().ifPresent(offset -> offset.accept(this));
        return defaultResult();
    }

    @Override
    public R visitCaseExpression(CaseExpression node) {
        return defaultResult();
    }

    @Override
    public R visitWhenClause(WhenClause node) {
        return defaultResult();
    }

    @Override
    public R visitCastExpression(CastExpression node) {
        return defaultResult();
    }

    @Override
    public R visitExtractExpression(ExtractExpression node) {
        node.source().accept(this);
        return defaultResult();
    }

    @Override
    public R visitSubqueryExpression(SubqueryExpression node) {
        node.query().accept(this);
        return defaultResult();
    }

    @Override
    public R visitNumericLiteral(NumericLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitStringLiteral(StringLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitBooleanLiteral(BooleanLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitNullLiteral(NullLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitIntervalLiteral(IntervalLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitIdentifier(Identifier node) {
        return defaultResult();
    }

    @Override
    public R visitQualifiedName(QualifiedName node) {
        return defaultResult();
    }

    @Override
    public R visitColumnRef(ColumnRef node) {
        return defaultResult();
    }

    @Override
    public R visitDataType(DataType node) {
        return defaultResult();
    }

    @Override
    public R visitFixedLength(FixedLength node) {
        return defaultResult();
    }

    @Override
    public R visitMaxLength(MaxLength node) {
        return defaultResult();
    }
}
