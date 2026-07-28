package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Cte;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.IntervalLiteral;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.NullLiteral;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.TableFunction;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Expand PostgreSQL {@code generate_series(a, b[, step])} in FROM position into a
 * recursive CTE for MySQL / T-SQL. Must run before the SRF renderers so they never
 * see {@code GENERATE_SERIES}.
 *
 * <p>Empty-range faithfulness: PG returns zero rows when {@code a > b} (positive step).
 * The anchor is always {@code SELECT a WHERE a <= b} (or the negative-step dual) —
 * never an unguarded {@code SELECT a}. Non-integer / non-faithful shapes refuse.
 *
 * <p>T-SQL {@code OPTION (MAXRECURSION 0)} comes from setting {@link Query#recursive()}
 * so {@code TSqlPrinter.renderWithKeyword} calls {@code requireMaxRecursion()} once —
 * this rule does not append OPTION itself.
 */
public final class ExpandGenerateSeriesRule implements Rule {

    @Override
    public String name() {
        return "expand-generate-series";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.MYSQL && ctx.target() != Dialect.TSQL) {
            return script;
        }
        return new Expander().transform(script);
    }

    private static final class Expander extends AstTransformer {

        @Override
        public Object visitQuery(Query node) {
            Set<String> bound = new HashSet<>();
            for (Cte cte : node.ctes()) {
                bound.add(cte.name().value().toLowerCase(Locale.ROOT));
            }

            List<Cte> rebuiltCtes = new ArrayList<>();
            boolean recursive = node.recursive();
            for (Cte cte : node.ctes()) {
                // CTE bodies are their own Query scopes — expand inside via rebuild.
                Cte rebuilt = rebuild(cte);
                rebuiltCtes.add(rebuilt);
                if (rebuilt.query().recursive()) {
                    recursive = true;
                }
            }

            List<Cte> generated = new ArrayList<>();
            QuerySpecification first = expandSpec(rebuild(node.first()), generated, bound);
            List<UnionArm> arms = new ArrayList<>();
            for (UnionArm arm : node.unionArms()) {
                arms.add(new UnionArm(
                        arm.operator(),
                        arm.all(),
                        expandQueryOperand(rebuild(arm.operand()), generated, bound),
                        arm.parenthesized(),
                        arm.pos()));
            }

            if (!generated.isEmpty()) {
                recursive = true;
            }

            List<Cte> allCtes = new ArrayList<>(rebuiltCtes.size() + generated.size());
            allCtes.addAll(rebuiltCtes);
            allCtes.addAll(generated);
            return new Query(
                    allCtes,
                    recursive,
                    first,
                    arms,
                    rebuildList(node.orderBy()),
                    rebuildOptional(node.limit()),
                    node.pos());
        }

        private QuerySpecification expandSpec(QuerySpecification spec, List<Cte> generated,
                                              Set<String> bound) {
            if (spec.from().isEmpty()) {
                return spec;
            }
            TableSource from = expandTableSource(spec.from().get(), generated, bound);
            return new QuerySpecification(
                    spec.quantifier(),
                    spec.distinctOn(),
                    spec.items(),
                    Optional.of(from),
                    spec.where(),
                    spec.groupBy(),
                    spec.having(),
                    spec.pos());
        }

        private Query expandQueryOperand(Query operand, List<Cte> generated, Set<String> bound) {
            QuerySpecification first = expandSpec(rebuild(operand.first()), generated, bound);
            List<UnionArm> arms = new ArrayList<>();
            for (UnionArm arm : operand.unionArms()) {
                arms.add(new UnionArm(arm.operator(), arm.all(),
                        expandQueryOperand(rebuild(arm.operand()), generated, bound),
                        arm.parenthesized(), arm.pos()));
            }
            return new Query(List.of(), false, first, arms, List.of(), Optional.empty(),
                    operand.pos());
        }

        private TableSource expandTableSource(TableSource source, List<Cte> generated,
                                              Set<String> bound) {
            Relation first = expandRelation(source.first(), generated, bound);
            List<Join> joins = new ArrayList<>(source.joins().size());
            for (Join join : source.joins()) {
                joins.add(new Join(
                        join.kind(),
                        expandRelation(join.table(), generated, bound),
                        join.on(),
                        join.usingColumns(),
                        join.lateral(),
                        join.pos()));
            }
            return new TableSource(first, joins, source.pos());
        }

        private Relation expandRelation(Relation relation, List<Cte> generated,
                                        Set<String> bound) {
            if (!(relation instanceof TableFunction tf)) {
                return relation;
            }
            String fn = tf.name().last().value().toUpperCase(Locale.ROOT);
            if (!"GENERATE_SERIES".equals(fn)) {
                return relation;
            }
            return expandGenerateSeries(tf, generated, bound);
        }

        private Relation expandGenerateSeries(TableFunction tf, List<Cte> generated,
                                              Set<String> bound) {
            List<Expression> args = tf.args();
            if (args.size() != 2 && args.size() != 3) {
                throw new UnsupportedFeatureException(
                        "table function GENERATE_SERIES (unsupported arity)", tf.pos());
            }

            Expression start = args.get(0);
            Expression stop = args.get(1);
            refuseIfNonIntegerBound(start, tf.pos());
            refuseIfNonIntegerBound(stop, tf.pos());

            long stepValue = 1L;
            Expression stepExpr;
            if (args.size() == 3) {
                Expression step = args.get(2);
                if (!(step instanceof NumericLiteral lit) || lit.decimal()) {
                    throw new UnsupportedFeatureException(
                            "table function GENERATE_SERIES (non-integer step)", tf.pos());
                }
                try {
                    stepValue = Long.parseLong(lit.text());
                } catch (NumberFormatException e) {
                    throw new UnsupportedFeatureException(
                            "table function GENERATE_SERIES (non-integer step)", tf.pos());
                }
                if (stepValue == 0L) {
                    throw new UnsupportedFeatureException(
                            "table function GENERATE_SERIES (step must not be zero)", tf.pos());
                }
                stepExpr = lit;
            } else {
                stepExpr = new NumericLiteral("1", false, tf.pos());
            }

            SourcePosition pos = tf.pos();
            Identifier cteName = freshCteName(bound, pos);
            Identifier colName = seriesColumnName(tf, pos);

            Expression colRef = new ColumnRef(
                    new QualifiedName(List.of(colName), pos), pos);

            // Anchor guard: empty-range must yield zero rows (never unguarded SELECT a).
            BinaryOperator anchorCmp = stepValue > 0 ? BinaryOperator.LTE : BinaryOperator.GTE;
            BinaryOperator recurCmp = stepValue > 0 ? BinaryOperator.LT : BinaryOperator.GT;
            // For |step| != 1 the brief's `n < b` form is wrong; compare n+step against stop.
            Expression recurPred;
            Expression nextVal = new BinaryOp(BinaryOperator.ADD, colRef, stepExpr, pos);
            if (stepValue == 1L || stepValue == -1L) {
                recurPred = new BinaryOp(recurCmp, colRef, stop, pos);
            } else {
                BinaryOperator stepStopCmp =
                        stepValue > 0 ? BinaryOperator.LTE : BinaryOperator.GTE;
                recurPred = new BinaryOp(stepStopCmp, nextVal, stop, pos);
            }

            QuerySpecification anchor = new QuerySpecification(
                    Optional.empty(),
                    List.of(new SelectExpr(start, Optional.empty(), pos)),
                    Optional.empty(),
                    Optional.of(new BinaryOp(anchorCmp, start, stop, pos)),
                    List.of(),
                    Optional.empty(),
                    pos);

            TableRef selfRef = new TableRef(
                    new QualifiedName(List.of(cteName), pos), Optional.empty(), pos);
            QuerySpecification recursiveArm = new QuerySpecification(
                    Optional.empty(),
                    List.of(new SelectExpr(nextVal, Optional.empty(), pos)),
                    Optional.of(new TableSource(selfRef, List.of(), pos)),
                    Optional.of(recurPred),
                    List.of(),
                    Optional.empty(),
                    pos);

            Query cteBody = new Query(
                    List.of(),
                    false,
                    anchor,
                    List.of(new UnionArm(true, recursiveArm, pos)),
                    List.of(),
                    Optional.empty(),
                    pos);

            generated.add(new Cte(cteName, Optional.of(List.of(colName)), cteBody, pos));

            return new TableRef(
                    new QualifiedName(List.of(cteName), pos),
                    tf.alias(),
                    pos);
        }

        private static Identifier seriesColumnName(TableFunction tf, SourcePosition pos) {
            if (tf.columnAliases().isPresent() && !tf.columnAliases().get().isEmpty()) {
                return tf.columnAliases().get().get(0);
            }
            // PG names the single SRF output column after the relation alias (`AS g` → col g).
            if (tf.alias().isPresent()) {
                return tf.alias().get();
            }
            return new Identifier("n", false, pos);
        }

        private static Identifier freshCteName(Set<String> bound, SourcePosition pos) {
            String candidate = "_gs";
            int i = 1;
            while (bound.contains(candidate.toLowerCase(Locale.ROOT))) {
                candidate = "_gs_" + i;
                i++;
            }
            bound.add(candidate.toLowerCase(Locale.ROOT));
            return new Identifier(candidate, false, pos);
        }

        private static void refuseIfNonIntegerBound(Expression expr, SourcePosition pos) {
            if (expr instanceof StringLiteral
                    || expr instanceof IntervalLiteral
                    || expr instanceof BooleanLiteral
                    || expr instanceof NullLiteral) {
                throw new UnsupportedFeatureException(
                        "table function GENERATE_SERIES (non-integer bounds)", pos);
            }
            if (expr instanceof NumericLiteral lit && lit.decimal()) {
                throw new UnsupportedFeatureException(
                        "table function GENERATE_SERIES (non-integer bounds)", pos);
            }
        }
    }
}
