package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import rs.etf.sqltranslator.ast.AddColumn;
import rs.etf.sqltranslator.ast.AlterAction;
import rs.etf.sqltranslator.ast.AlterTableStatement;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.BetweenPredicate;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.CreateIndexStatement;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.Cte;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.DeleteStatement;
import rs.etf.sqltranslator.ast.DerivedTable;
import rs.etf.sqltranslator.ast.DropColumn;
import rs.etf.sqltranslator.ast.DropIndexStatement;
import rs.etf.sqltranslator.ast.DropTableStatement;
import rs.etf.sqltranslator.ast.ExistsPredicate;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.ForeignKeyConstraint;
import rs.etf.sqltranslator.ast.ForeignKeyRef;
import rs.etf.sqltranslator.ast.FrameBound;
import rs.etf.sqltranslator.ast.FrameBoundKind;
import rs.etf.sqltranslator.ast.FrameMode;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.InListPredicate;
import rs.etf.sqltranslator.ast.InSubqueryPredicate;
import rs.etf.sqltranslator.ast.IndexColumn;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.RowValue;
import rs.etf.sqltranslator.ast.ValuesTable;
import rs.etf.sqltranslator.ast.LikePredicate;
import rs.etf.sqltranslator.ast.NullLiteral;
import rs.etf.sqltranslator.ast.NullsOrder;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.PrimaryKeyConstraint;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SelectStar;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.SetQuantifier;
import rs.etf.sqltranslator.ast.SortDirection;
import rs.etf.sqltranslator.ast.Statement;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.SubqueryExpression;
import rs.etf.sqltranslator.ast.TableConstraint;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.UnaryOperator;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.ast.UniqueConstraint;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.ast.WindowFrame;
import rs.etf.sqltranslator.ast.WindowSpec;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.grammar.PostgreSqlBaseVisitor;
import rs.etf.sqltranslator.grammar.PostgreSqlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin PostgreSQL parse-tree → AST builder (D4): mechanical extraction only; all
 * logic lives in {@link AstBuilderSupport}. Deliberately near-identical to the other
 * two builders.
 */
final class PostgreSqlAstBuilder extends PostgreSqlBaseVisitor<Object> {

    private final AstBuilderSupport support = new AstBuilderSupport(Dialect.POSTGRESQL);

    // --- script and statements ---

    @Override
    public Object visitScript(PostgreSqlParser.ScriptContext ctx) {
        List<Statement> statements = ctx.statement().stream()
                .map(s -> (Statement) visit(s)).toList();
        return new Script(statements, pos(ctx));
    }

    @Override
    public Object visitSelectStatement(PostgreSqlParser.SelectStatementContext ctx) {
        return new SelectStatement((Query) visit(ctx.queryExpression()), pos(ctx));
    }

    // --- query shape ---

    @Override
    public Object visitQueryExpression(PostgreSqlParser.QueryExpressionContext ctx) {
        List<Cte> ctes = List.of();
        if (ctx.withClause() != null) {
            var w = ctx.withClause();
            support.refuseIfRecursiveKeyword(w, w.RECURSIVE() != null);
            ctes = w.commonTableExpression().stream().map(c -> (Cte) visit(c)).toList();
            for (Cte cte : ctes) {
                support.refuseIfCteSelfReference(cte);
            }
        }
        QuerySpecification first = (QuerySpecification) visit(ctx.querySpecification(0));
        List<UnionArm> arms = support.unionArms(ctx, PostgreSqlParser.UNION, PostgreSqlParser.ALL,
                this);
        List<OrderItem> orderBy = ctx.orderByClause() == null
                ? List.of()
                : ctx.orderByClause().orderItem().stream()
                        .map(i -> (OrderItem) visit(i)).toList();
        return new Query(ctes, first, arms, orderBy, rowLimit(ctx.rowLimitClause()), pos(ctx));
    }

    @Override
    public Object visitCommonTableExpression(PostgreSqlParser.CommonTableExpressionContext ctx) {
        Identifier name = ident(ctx.identifier(0));
        Optional<List<Identifier>> cols = Optional.empty();
        if (ctx.identifier().size() > 1) {
            cols = Optional.of(ctx.identifier().stream().skip(1).map(this::ident).toList());
        }
        Query query = (Query) visit(ctx.queryExpression());
        return new Cte(name, cols, query, pos(ctx));
    }

    /** PostgreSQL: LIMIT/OFFSET either order, plus OFFSET/FETCH and bare FETCH. */
    private Optional<RowLimit> rowLimit(PostgreSqlParser.RowLimitClauseContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        Expression first = expr(ctx.expression(0));
        Expression second = ctx.expression().size() > 1 ? expr(ctx.expression(1)) : null;
        int start = ctx.getStart().getType();
        // LIMIT n [OFFSET m] and FETCH FIRST/NEXT n fold count-first.
        // OFFSET … (LIMIT | FETCH) folds offset-first.
        boolean countFirst = start == PostgreSqlParser.LIMIT || start == PostgreSqlParser.FETCH;
        return Optional.of(support.pgRowLimit(first, second, countFirst, pos(ctx)));
    }

    @Override
    public Object visitQuerySpecification(PostgreSqlParser.QuerySpecificationContext ctx) {
        List<SelectItem> items = ctx.selectItem().stream()
                .map(i -> (SelectItem) visit(i)).toList();
        Optional<TableSource> from = ctx.tableSource() == null
                ? Optional.empty() : Optional.of((TableSource) visit(ctx.tableSource()));
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        List<Expression> groupBy = ctx.groupByClause() == null
                ? List.of()
                : ctx.groupByClause().expression().stream().map(this::expr).toList();
        Optional<Expression> having = ctx.havingClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.havingClause().expression()));
        return new QuerySpecification(quantifier(ctx.setQuantifier()), items, from, where,
                groupBy, having, pos(ctx));
    }

    private Optional<SetQuantifier> quantifier(PostgreSqlParser.SetQuantifierContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        return Optional.of(ctx.DISTINCT() != null ? SetQuantifier.DISTINCT : SetQuantifier.ALL);
    }

    @Override
    public Object visitSelectStar(PostgreSqlParser.SelectStarContext ctx) {
        return new SelectStar(Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitSelectQualifiedStar(PostgreSqlParser.SelectQualifiedStarContext ctx) {
        return new SelectStar(Optional.of(qname(ctx.qualifiedName())), pos(ctx));
    }

    @Override
    public Object visitSelectExpr(PostgreSqlParser.SelectExprContext ctx) {
        Optional<Identifier> alias = ctx.aliasName() == null
                ? Optional.empty() : Optional.of(aliasName(ctx.aliasName()));
        return new SelectExpr(expr(ctx.expression()), alias, pos(ctx));
    }

    @Override
    public Object visitTableSource(PostgreSqlParser.TableSourceContext ctx) {
        List<Join> joins = ctx.joinedTable().stream().map(j -> (Join) visit(j)).toList();
        return new TableSource((Relation) visit(ctx.tablePrimary()), joins, pos(ctx));
    }

    @Override
    public Object visitNamedTablePrimary(PostgreSqlParser.NamedTablePrimaryContext ctx) {
        Optional<Identifier> alias = ctx.aliasName() == null
                ? Optional.empty() : Optional.of(aliasName(ctx.aliasName()));
        return new TableRef(qname(ctx.qualifiedName()), alias, pos(ctx));
    }

    @Override
    public Object visitDerivedTablePrimary(PostgreSqlParser.DerivedTablePrimaryContext ctx) {
        Query query = (Query) visit(ctx.queryExpression());
        Identifier alias = aliasName(ctx.aliasName());
        Optional<List<Identifier>> cols = Optional.empty();
        if (!ctx.columnName().isEmpty()) {
            cols = Optional.of(ctx.columnName().stream().map(this::columnName).toList());
        }
        return new DerivedTable(query, alias, cols, pos(ctx));
    }

    @Override
    public Object visitValuesTablePrimary(PostgreSqlParser.ValuesTablePrimaryContext ctx) {
        List<RowValue> rows = ctx.rowValue().stream()
                .map(row -> new RowValue(
                        row.expression().stream().map(this::expr).toList(),
                        pos(row)))
                .toList();
        Identifier alias = aliasName(ctx.aliasName());
        List<Identifier> cols = ctx.columnName().stream().map(this::columnName).toList();
        return new ValuesTable(rows, alias, cols, pos(ctx));
    }

    @Override
    public Object visitJoinedTable(PostgreSqlParser.JoinedTableContext ctx) {
        Relation table = (Relation) visit(ctx.tablePrimary());
        if (ctx.APPLY() != null) {
            if (ctx.CROSS() != null) {
                return new Join(JoinKind.CROSS, table, Optional.empty(), true, pos(ctx));
            }
            return new Join(JoinKind.LEFT, table, Optional.empty(), true, pos(ctx));
        }
        boolean lateral = ctx.LATERAL() != null;
        if (ctx.CROSS() != null || ctx.joinType() == null) {
            // CROSS JOIN [LATERAL] or , LATERAL
            return new Join(JoinKind.CROSS, table, Optional.empty(), lateral, pos(ctx));
        }
        return new Join(joinKind(ctx.joinType()), table,
                Optional.of(expr(ctx.expression())), lateral, pos(ctx));
    }

    private JoinKind joinKind(PostgreSqlParser.JoinTypeContext ctx) {
        if (ctx.LEFT() != null) {
            return JoinKind.LEFT;
        }
        if (ctx.RIGHT() != null) {
            return JoinKind.RIGHT;
        }
        if (ctx.FULL() != null) {
            return JoinKind.FULL;
        }
        return JoinKind.INNER;
    }

    /** PostgreSQL carries NULLS FIRST/LAST; translation happens in Phase 4 (§2.2). */
    @Override
    public Object visitOrderItem(PostgreSqlParser.OrderItemContext ctx) {
        SortDirection direction = ctx.DESC() != null ? SortDirection.DESC : SortDirection.ASC;
        Optional<NullsOrder> nulls = ctx.NULLS() == null
                ? Optional.empty()
                : Optional.of(ctx.FIRST() != null ? NullsOrder.FIRST : NullsOrder.LAST);
        return new OrderItem(expr(ctx.expression()), direction, nulls, pos(ctx));
    }

    @Override
    public Object visitSubquery(PostgreSqlParser.SubqueryContext ctx) {
        return visit(ctx.queryExpression());
    }

    // --- DML ---

    @Override
    public Object visitInsertStatement(PostgreSqlParser.InsertStatementContext ctx) {
        List<Identifier> columns = ctx.identifier().stream().map(this::ident).toList();
        QualifiedName table = qname(ctx.qualifiedName());
        if (ctx.insertSource() instanceof PostgreSqlParser.InsertQueryContext queryCtx) {
            return new InsertStatement(table, columns, List.of(),
                    Optional.of((Query) visit(queryCtx.queryExpression())), pos(ctx));
        }
        PostgreSqlParser.InsertValuesContext values =
                (PostgreSqlParser.InsertValuesContext) ctx.insertSource();
        List<List<Expression>> rows = values.rowValue().stream()
                .map(row -> row.expression().stream().map(this::expr).toList())
                .toList();
        return new InsertStatement(table, columns, rows, Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitUpdateStatement(PostgreSqlParser.UpdateStatementContext ctx) {
        List<Assignment> assignments = ctx.assignment().stream()
                .map(a -> new Assignment(qname(a.qualifiedName()), expr(a.expression()), pos(a)))
                .toList();
        Optional<Identifier> alias = ctx.identifier() == null
                ? Optional.empty() : Optional.of(ident(ctx.identifier()));
        Optional<TableSource> from = updateFrom(ctx.tableSource(), ctx.FROM() != null);
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        return new UpdateStatement(qname(ctx.qualifiedName()), alias, assignments, from, where,
                pos(ctx));
    }

    private Optional<TableSource> updateFrom(
            List<PostgreSqlParser.TableSourceContext> sources, boolean hasFromKeyword) {
        if (sources == null || sources.isEmpty()) {
            return Optional.empty();
        }
        // Prefer FROM-clause source when both comma-target and FROM are present.
        int index = hasFromKeyword ? sources.size() - 1 : 0;
        return Optional.of((TableSource) visit(sources.get(index)));
    }

    @Override
    public Object visitDeleteStatement(PostgreSqlParser.DeleteStatementContext ctx) {
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        return new DeleteStatement(qname(ctx.qualifiedName()), where, pos(ctx));
    }

    // --- DDL ---

    @Override
    public Object visitCreateIndexStatement(PostgreSqlParser.CreateIndexStatementContext ctx) {
        if (ctx.indexMethod() != null) {
            throw support.refuse("index method (USING)", pos(ctx.indexMethod()));
        }
        if (ctx.whereClause() != null) {
            throw support.refuse("partial index (WHERE)", pos(ctx.whereClause()));
        }
        List<IndexColumn> columns = ctx.indexColumn().stream()
                .map(this::indexColumn).toList();
        return new CreateIndexStatement(ident(ctx.identifier()), ctx.UNIQUE() != null,
                qname(ctx.qualifiedName()), columns, pos(ctx));
    }

    private IndexColumn indexColumn(PostgreSqlParser.IndexColumnContext ctx) {
        if (ctx.NULLS() != null) {
            throw support.refuse("NULLS ordering in index columns", pos(ctx));
        }
        SortDirection direction =
                ctx.DESC() != null ? SortDirection.DESC : SortDirection.ASC;
        return new IndexColumn(ident(ctx.identifier()), direction, pos(ctx));
    }

    @Override
    public Object visitCreateTableStatement(PostgreSqlParser.CreateTableStatementContext ctx) {
        List<ColumnDefinition> columns = new ArrayList<>();
        List<TableConstraint> constraints = new ArrayList<>();
        for (PostgreSqlParser.TableElementContext element : ctx.tableElement()) {
            if (element.columnDefinition() != null) {
                columns.add((ColumnDefinition) visit(element.columnDefinition()));
            } else {
                constraints.add((TableConstraint) visit(element.tableConstraint()));
            }
        }
        return new CreateTableStatement(qname(ctx.qualifiedName()), columns, constraints,
                pos(ctx));
    }

    @Override
    public Object visitColumnDefinition(PostgreSqlParser.ColumnDefinitionContext ctx) {
        AstBuilderSupport.FoldedType type = columnType(ctx.dataType());
        AstBuilderSupport.ColumnAttributes attributes = new AstBuilderSupport.ColumnAttributes();
        for (PostgreSqlParser.ColumnConstraintContext constraint : ctx.columnConstraint()) {
            if (constraint.NOT() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.NOT_NULL, null, null);
            } else if (constraint.DEFAULT() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.DEFAULT,
                        expr(constraint.expression()), null);
            } else if (constraint.NULL() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.NULL_ALLOWED, null, null);
            } else if (constraint.PRIMARY() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.PRIMARY_KEY, null, null);
            } else if (constraint.UNIQUE() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.UNIQUE, null, null);
            } else if (constraint.REFERENCES() != null) {
                Optional<Identifier> column = constraint.identifier() == null
                        ? Optional.empty() : Optional.of(ident(constraint.identifier()));
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.REFERENCES, null,
                        new ForeignKeyRef(qname(constraint.qualifiedName()), column,
                                pos(constraint)));
            } else if (constraint.autoIncrement() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.AUTO_INCREMENT, null, null);
            }
        }
        return support.columnDefinition(ident(ctx.identifier()), type, attributes, pos(ctx));
    }

    @Override
    public Object visitTableConstraint(PostgreSqlParser.TableConstraintContext ctx) {
        Optional<Identifier> name = ctx.identifier() == null
                ? Optional.empty() : Optional.of(ident(ctx.identifier()));
        if (ctx.PRIMARY() != null) {
            return new PrimaryKeyConstraint(name, columns(ctx.columnList(0)), pos(ctx));
        }
        if (ctx.UNIQUE() != null) {
            return new UniqueConstraint(name, columns(ctx.columnList(0)), pos(ctx));
        }
        List<Identifier> refColumns = ctx.columnList().size() > 1
                ? columns(ctx.columnList(1)) : List.of();
        return new ForeignKeyConstraint(name, columns(ctx.columnList(0)),
                qname(ctx.qualifiedName()), refColumns, pos(ctx));
    }

    private List<Identifier> columns(PostgreSqlParser.ColumnListContext ctx) {
        return ctx.identifier().stream().map(this::ident).toList();
    }

    @Override
    public Object visitDropTableStatement(PostgreSqlParser.DropTableStatementContext ctx) {
        return new DropTableStatement(qname(ctx.qualifiedName()), ctx.IF() != null, pos(ctx));
    }

    @Override
    public Object visitDropIndexStatement(PostgreSqlParser.DropIndexStatementContext ctx) {
        return new DropIndexStatement(ident(ctx.identifier()), ctx.IF() != null,
                ctx.qualifiedName() != null ? Optional.of(qname(ctx.qualifiedName())) : Optional.empty(),
                pos(ctx));
    }

    @Override
    public Object visitDropViewOrRoutineStatement(PostgreSqlParser.DropViewOrRoutineStatementContext ctx) {
        boolean hasSignature = false;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if ("(".equals(ctx.getChild(i).getText())) {
                hasSignature = true;
                break;
            }
        }
        List<DataType> argTypes = hasSignature
                ? ctx.dataType().stream().map(this::castType).toList()
                : List.of();
        Optional<Identifier> cascade = ctx.identifier().size() > 1
                ? Optional.of(ident(ctx.identifier(ctx.identifier().size() - 1)))
                : Optional.empty();
        return support.dropViewOrRoutine(ident(ctx.identifier(0)), qname(ctx.qualifiedName()),
                ctx.IF() != null, hasSignature, argTypes, cascade, pos(ctx));
    }

    @Override
    public Object visitTruncateStatement(PostgreSqlParser.TruncateStatementContext ctx) {
        return support.truncate(ident(ctx.identifier()), qname(ctx.qualifiedName()), pos(ctx));
    }

    @Override
    public Object visitAlterTableStatement(PostgreSqlParser.AlterTableStatementContext ctx) {
        return new AlterTableStatement(qname(ctx.qualifiedName()),
                (AlterAction) visit(ctx.alterTableAction()), pos(ctx));
    }

    @Override
    public Object visitAlterAddColumn(PostgreSqlParser.AlterAddColumnContext ctx) {
        return new AddColumn((ColumnDefinition) visit(ctx.columnDefinition()), pos(ctx));
    }

    @Override
    public Object visitAlterDropColumn(PostgreSqlParser.AlterDropColumnContext ctx) {
        return new DropColumn(ident(ctx.identifier()), pos(ctx));
    }

    @Override
    public Object visitAlterChangeColumnType(PostgreSqlParser.AlterChangeColumnTypeContext ctx) {
        @SuppressWarnings("unchecked")
        TypeChange change = (TypeChange) visit(ctx.alterColumnTypeSpec());
        return support.alterColumnType(ident(ctx.identifier()), change.type(), change.using(), pos(ctx));
    }

    @Override
    public Object visitAlterModifyColumn(PostgreSqlParser.AlterModifyColumnContext ctx) {
        support.requireModifyKeyword(ident(ctx.identifier(0)));
        return support.alterColumnType(ident(ctx.identifier(1)), castType(ctx.dataType()),
                Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitAlterTypeKeyword(PostgreSqlParser.AlterTypeKeywordContext ctx) {
        support.requireTypeKeyword(ident(ctx.identifier()));
        Optional<Expression> using = Optional.empty();
        if (ctx.usingClause() != null) {
            @SuppressWarnings("unchecked")
            Optional<Expression> u = (Optional<Expression>) visit(ctx.usingClause());
            using = u;
        }
        return new TypeChange(castAlterType(ctx.alterDataType()), using);
    }

    @Override
    public Object visitAlterSetDataType(PostgreSqlParser.AlterSetDataTypeContext ctx) {
        support.requireDataTypeKeywords(ident(ctx.identifier(0)), ident(ctx.identifier(1)));
        Optional<Expression> using = Optional.empty();
        if (ctx.usingClause() != null) {
            @SuppressWarnings("unchecked")
            Optional<Expression> u = (Optional<Expression>) visit(ctx.usingClause());
            using = u;
        }
        return new TypeChange(castAlterType(ctx.alterDataType()), using);
    }

    @Override
    public Object visitAlterBareType(PostgreSqlParser.AlterBareTypeContext ctx) {
        Optional<Expression> using = Optional.empty();
        if (ctx.usingClause() != null) {
            @SuppressWarnings("unchecked")
            Optional<Expression> u = (Optional<Expression>) visit(ctx.usingClause());
            using = u;
        }
        return new TypeChange(castAlterType(ctx.alterDataType()), using);
    }

    @Override
    public Object visitUsingClause(PostgreSqlParser.UsingClauseContext ctx) {
        return Optional.of(expr(ctx.expression()));
    }

    private record TypeChange(DataType type, Optional<Expression> using) {
    }

    // --- expression ladder ---

    @Override
    public Object visitOrExpression(PostgreSqlParser.OrExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitAndExpression(PostgreSqlParser.AndExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitNotExpression(PostgreSqlParser.NotExpressionContext ctx) {
        if (ctx.NOT() != null) {
            return new UnaryOp(UnaryOperator.NOT, expr(ctx.notExpression()), pos(ctx));
        }
        return visit(ctx.predicate());
    }

    /** PostgreSQL {@code ||} folds to CONCAT here (§2.2). */
    @Override
    public Object visitConcatExpression(PostgreSqlParser.ConcatExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitJsonExpression(PostgreSqlParser.JsonExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitAdditiveExpression(PostgreSqlParser.AdditiveExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitMultiplicativeExpression(
            PostgreSqlParser.MultiplicativeExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitUnaryExpression(PostgreSqlParser.UnaryExpressionContext ctx) {
        if (ctx.unaryExpression() != null) {
            UnaryOperator op = ctx.getChild(0).getText().equals("-")
                    ? UnaryOperator.NEG : UnaryOperator.POS;
            return new UnaryOp(op, expr(ctx.unaryExpression()), pos(ctx));
        }
        return visit(ctx.primaryExpression());
    }

    // --- predicates ---

    @Override
    public Object visitComparisonPredicate(PostgreSqlParser.ComparisonPredicateContext ctx) {
        return new BinaryOp(support.comparisonOperator(ctx.comparisonOperator().getText()),
                expr(ctx.concatExpression(0)), expr(ctx.concatExpression(1)), pos(ctx));
    }

    @Override
    public Object visitBetweenPredicate(PostgreSqlParser.BetweenPredicateContext ctx) {
        return new BetweenPredicate(expr(ctx.concatExpression(0)),
                expr(ctx.concatExpression(1)), expr(ctx.concatExpression(2)),
                ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitLikePredicate(PostgreSqlParser.LikePredicateContext ctx) {
        return new LikePredicate(expr(ctx.concatExpression(0)),
                expr(ctx.concatExpression(1)), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitInListPredicate(PostgreSqlParser.InListPredicateContext ctx) {
        List<Expression> items = ctx.expression().stream().map(this::expr).toList();
        return new InListPredicate(expr(ctx.concatExpression()), items,
                ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitInSubqueryPredicate(PostgreSqlParser.InSubqueryPredicateContext ctx) {
        return new InSubqueryPredicate(expr(ctx.concatExpression()),
                (Query) visit(ctx.subquery()), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitIsNullPredicate(PostgreSqlParser.IsNullPredicateContext ctx) {
        return new IsNullPredicate(expr(ctx.concatExpression()), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitExistsPredicate(PostgreSqlParser.ExistsPredicateContext ctx) {
        return new ExistsPredicate((Query) visit(ctx.subquery()), pos(ctx));
    }

    @Override
    public Object visitSimplePredicate(PostgreSqlParser.SimplePredicateContext ctx) {
        return visit(ctx.concatExpression());
    }

    // --- primary expressions ---

    @Override
    public Object visitPgColonCastChain(PostgreSqlParser.PgColonCastChainContext ctx) {
        Expression value = (Expression) visit(ctx.primaryBase());
        for (PostgreSqlParser.DataTypeContext typeCtx : ctx.dataType()) {
            value = new CastExpression(value, castType(typeCtx), pos(ctx));
        }
        return value;
    }

    @Override
    public Object visitPrimaryBase(PostgreSqlParser.PrimaryBaseContext ctx) {
        if (ctx.literal() != null) {
            return visit(ctx.literal());
        }
        if (ctx.caseExpression() != null) {
            return visit(ctx.caseExpression());
        }
        if (ctx.CAST() != null) {
            return new CastExpression(expr(ctx.expression()), castType(ctx.dataType()), pos(ctx));
        }
        if (ctx.intervalLiteral() != null) {
            return visit(ctx.intervalLiteral());
        }
        if (ctx.functionCall() != null) {
            FunctionCall call = (FunctionCall) visit(ctx.functionCall());
            if (ctx.windowOverlay() != null) {
                WindowSpec spec = (WindowSpec) visit(ctx.windowOverlay().windowSpecification());
                if (spec.frame().isPresent()) {
                    throw support.refuse("window frame", spec.frame().get().pos());
                }
                call = new FunctionCall(call.name(), call.args(), call.star(), call.quantifier(),
                        call.orderBy(), call.filter(), Optional.of(spec), call.pos());
            }
            return call;
        }
        if (ctx.columnReference() != null) {
            return new ColumnRef(columnRef(ctx.columnReference()), pos(ctx));
        }
        if (ctx.subquery() != null) {
            return new SubqueryExpression((Query) visit(ctx.subquery()), pos(ctx));
        }
        return visit(ctx.expression());
    }

    @Override
    public Object visitLiteral(PostgreSqlParser.LiteralContext ctx) {
        if (ctx.INTEGER_LITERAL() != null) {
            return support.numeric(ctx.INTEGER_LITERAL().getSymbol(), false);
        }
        if (ctx.DECIMAL_LITERAL() != null) {
            return support.numeric(ctx.DECIMAL_LITERAL().getSymbol(), true);
        }
        if (ctx.HEX_LITERAL() != null) {
            throw support.refuse("hex literal " + ctx.HEX_LITERAL().getText(), pos(ctx));
        }
        if (ctx.STRING_LITERAL() != null) {
            return support.stringLiteral(ctx.STRING_LITERAL().getSymbol());
        }
        if (ctx.NULL() != null) {
            return new NullLiteral(pos(ctx));
        }
        return new BooleanLiteral(ctx.TRUE() != null, pos(ctx));
    }

    @Override
    public Object visitIntervalLiteral(PostgreSqlParser.IntervalLiteralContext ctx) {
        if (ctx.STRING_LITERAL() != null && ctx.expression() == null) {
            StringLiteral lit = support.stringLiteral(ctx.STRING_LITERAL().getSymbol());
            Optional<String> unit = ctx.identifier() == null
                    ? Optional.empty()
                    : Optional.of(ident(ctx.identifier()).value());
            return support.intervalFromString(lit.value(), unit, pos(ctx));
        }
        return support.intervalFromExpression(
                expr(ctx.expression()),
                ident(ctx.datePartKeyword().identifier()).value(),
                pos(ctx));
    }

    @Override
    public Object visitCaseExpression(PostgreSqlParser.CaseExpressionContext ctx) {
        List<Expression> expressions = ctx.expression().stream().map(this::expr).toList();
        List<SourcePosition> whenPositions = ctx.WHEN().stream()
                .map(w -> AstBuilderSupport.pos(w.getSymbol())).toList();
        return support.caseExpression(expressions, whenPositions, ctx.ELSE() != null,
                pos(ctx));
    }

    @Override
    public Object visitFunctionCall(PostgreSqlParser.FunctionCallContext ctx) {
        String name = support.functionName(functionNameIdentifier(ctx.functionName()));
        boolean star = false;
        List<Expression> args = List.of();
        List<OrderItem> orderBy = List.of();
        Optional<SetQuantifier> quantifier = Optional.empty();
        if (ctx.functionArgs() != null) {
            PostgreSqlParser.FunctionArgsContext fa = ctx.functionArgs();
            for (ParseTree child : fa.children) {
                if (child instanceof TerminalNode terminal && terminal.getText().equals("*")) {
                    star = true;
                }
            }
            if (!star) {
                args = fa.expression().stream().map(this::expr).toList();
                orderBy = fa.orderItem().stream()
                        .map(item -> (OrderItem) visit(item)).toList();
                quantifier = quantifier(fa.setQuantifier());
            }
        }
        if (ctx.withinGroupClause() != null) {
            orderBy = ctx.withinGroupClause().orderItem().stream()
                    .map(item -> (OrderItem) visit(item)).toList();
        }
        Optional<Expression> filter = Optional.empty();
        if (ctx.aggFilter() != null) {
            filter = Optional.of(support.aggregateFilterKeyword(
                    ident(ctx.aggFilter().identifier()),
                    expr(ctx.aggFilter().expression())));
        }
        return new FunctionCall(name, args, star, quantifier, orderBy,
                filter, Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitWindowSpecification(PostgreSqlParser.WindowSpecificationContext ctx) {
        List<Expression> partitionBy = ctx.expression().stream().map(this::expr).toList();
        List<OrderItem> orderBy = ctx.orderItem().stream()
                .map(item -> (OrderItem) visit(item)).toList();
        Optional<WindowFrame> frame = ctx.windowFrame() == null
                ? Optional.empty()
                : Optional.of((WindowFrame) visit(ctx.windowFrame()));
        return new WindowSpec(partitionBy, orderBy, frame, pos(ctx));
    }

    @Override
    public Object visitWindowFrame(PostgreSqlParser.WindowFrameContext ctx) {
        FrameMode mode = ctx.ROWS() != null ? FrameMode.ROWS : FrameMode.RANGE;
        List<PostgreSqlParser.FrameBoundContext> bounds = ctx.frameBound();
        FrameBound start = (FrameBound) visit(bounds.get(0));
        Optional<FrameBound> end = bounds.size() > 1
                ? Optional.of((FrameBound) visit(bounds.get(1)))
                : Optional.empty();
        return new WindowFrame(mode, start, end, pos(ctx));
    }

    @Override
    public Object visitFrameBound(PostgreSqlParser.FrameBoundContext ctx) {
        if (ctx.CURRENT_ROW() != null) {
            return new FrameBound(FrameBoundKind.CURRENT_ROW, Optional.empty(), pos(ctx));
        }
        if (ctx.UNBOUNDED() != null) {
            FrameBoundKind kind = ctx.PRECEDING() != null
                    ? FrameBoundKind.UNBOUNDED_PRECEDING
                    : FrameBoundKind.UNBOUNDED_FOLLOWING;
            return new FrameBound(kind, Optional.empty(), pos(ctx));
        }
        FrameBoundKind kind = ctx.PRECEDING() != null
                ? FrameBoundKind.PRECEDING
                : FrameBoundKind.FOLLOWING;
        return new FrameBound(kind, Optional.of(expr(ctx.expression())), pos(ctx));
    }

    private Identifier functionNameIdentifier(PostgreSqlParser.FunctionNameContext ctx) {
        return ctx.identifier() != null
                ? ident(ctx.identifier())
                : new Identifier(ctx.getText(), false, AstBuilderSupport.pos(ctx));
    }

    // --- shared extraction helpers ---

    private Expression expr(ParseTree tree) {
        return (Expression) visit(tree);
    }

    private Identifier ident(PostgreSqlParser.IdentifierContext ctx) {
        return support.identifier(ctx.getStart());
    }

    private Identifier columnName(PostgreSqlParser.ColumnNameContext ctx) {
        return support.identifier(ctx.getStart());
    }

    private Identifier aliasName(PostgreSqlParser.AliasNameContext ctx) {
        return support.identifier(ctx.getStart());
    }

    private QualifiedName qname(PostgreSqlParser.QualifiedNameContext ctx) {
        return support.qualifiedName(ctx.identifier().stream().map(this::ident).toList(),
                pos(ctx));
    }

    private QualifiedName columnRef(PostgreSqlParser.ColumnReferenceContext ctx) {
        return support.qualifiedName(ctx.columnName().stream().map(this::columnName).toList(),
                pos(ctx));
    }

    private AstBuilderSupport.FoldedType columnType(PostgreSqlParser.DataTypeContext ctx) {
        return AstBuilderSupport.withArrayDims(
                support.foldColumnType(typeWord(ctx, 0), secondTypeWord(ctx), argTexts(ctx),
                        pos(ctx)),
                AstBuilderSupport.arrayDims(ctx));
    }

    private DataType castType(PostgreSqlParser.DataTypeContext ctx) {
        return AstBuilderSupport.withArrayDims(
                support.foldCastType(typeWord(ctx, 0), secondTypeWord(ctx), argTexts(ctx),
                        pos(ctx)),
                AstBuilderSupport.arrayDims(ctx));
    }

    private DataType castAlterType(PostgreSqlParser.AlterDataTypeContext ctx) {
        return AstBuilderSupport.withArrayDims(
                support.foldCastType(alterTypeWord(ctx), null, alterArgTexts(ctx),
                        pos(ctx)),
                AstBuilderSupport.arrayDims(ctx));
    }

    private String typeWord(PostgreSqlParser.DataTypeContext ctx, int index) {
        return support.identifier(ctx.identifier(index).getStart()).value();
    }

    private String secondTypeWord(PostgreSqlParser.DataTypeContext ctx) {
        return ctx.identifier().size() > 1 ? typeWord(ctx, 1) : null;
    }

    private List<String> argTexts(PostgreSqlParser.DataTypeContext ctx) {
        return ctx.dataTypeArg().stream().map(ParserRuleContext::getText).toList();
    }

    private String alterTypeWord(PostgreSqlParser.AlterDataTypeContext ctx) {
        return support.identifier(ctx.identifier().getStart()).value();
    }

    private List<String> alterArgTexts(PostgreSqlParser.AlterDataTypeContext ctx) {
        return ctx.dataTypeArg().stream().map(ParserRuleContext::getText).toList();
    }

    private static SourcePosition pos(ParserRuleContext ctx) {
        return AstBuilderSupport.pos(ctx);
    }
}
