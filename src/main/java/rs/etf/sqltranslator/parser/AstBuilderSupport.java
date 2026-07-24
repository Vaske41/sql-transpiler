package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.TerminalNode;
import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.AlterColumnType;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.CaseExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.Cte;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.CreateViewStatement;
import rs.etf.sqltranslator.ast.DropRoutineStatement;
import rs.etf.sqltranslator.ast.DropViewStatement;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.ExtractExpression;
import rs.etf.sqltranslator.ast.FixedLength;
import rs.etf.sqltranslator.ast.ForeignKeyRef;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.IntervalLiteral;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.RowValue;
import rs.etf.sqltranslator.ast.SelectStar;
import rs.etf.sqltranslator.ast.Statement;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.TruncateStatement;
import rs.etf.sqltranslator.ast.TypeLength;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.ast.SetOperator;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.ast.Upsert;
import rs.etf.sqltranslator.ast.UpsertKind;
import rs.etf.sqltranslator.ast.ValuesTable;
import rs.etf.sqltranslator.ast.WhenClause;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shared core behind the three thin dialect builders (D4): node-construction
 * helpers, {@code RowLimit} unification, per-dialect type-folding tables (D3),
 * identifier/string unescaping, function-name uppercasing, and every refusal.
 * The builders do mechanical extraction only; each normalization and bug-fix
 * lands once, here, where it is unit-testable in isolation (D11).
 */
final class AstBuilderSupport {

    /** Length/scale arguments are carried only into these generics (§2.3). */
    private static final Set<GenericType> PARAMETERIZABLE = Set.of(
            GenericType.CHAR, GenericType.VARCHAR, GenericType.NVARCHAR,
            GenericType.DECIMAL, GenericType.TIME, GenericType.TIMESTAMP);

    private final Dialect dialect;
    private final Map<String, Fold> typeTable;

    AstBuilderSupport(Dialect dialect) {
        this.dialect = dialect;
        this.typeTable = typeTable(dialect);
    }

    // ------------------------------------------------------------------
    // Positions and refusals
    // ------------------------------------------------------------------

    static SourcePosition pos(ParserRuleContext ctx) {
        return pos(ctx.getStart());
    }

    static SourcePosition pos(Token token) {
        return new SourcePosition(token.getLine(), token.getCharPositionInLine());
    }

    /**
     * Always throws. Returns {@link UnsupportedFeatureException} so call sites can
     * write {@code throw support.refuse(...)} and remain obviously terminating.
     */
    UnsupportedFeatureException refuse(String construct, SourcePosition position) {
        throw new UnsupportedFeatureException(construct, position);
    }

    void refuseIf(boolean condition, String construct, SourcePosition position) {
        if (condition) {
            throw refuse(construct, position);
        }
    }

    /**
     * Build {@link Upsert} from an {@code ON DUPLICATE KEY UPDATE} clause
     * ({@code kindToken} must read DUPLICATE).
     */
    Upsert duplicateKeyUpsert(Token kindToken, List<Assignment> assignments,
                              SourcePosition pos) {
        requireWord(kindToken, "DUPLICATE", pos);
        return new Upsert(UpsertKind.ON_DUPLICATE_KEY, List.of(), assignments,
                Optional.empty(), pos);
    }

    /**
     * Build {@link Upsert} from {@code ON CONFLICT … DO NOTHING|UPDATE}.
     * {@code conflictToken} must read CONFLICT; {@code doToken} must read DO.
     * For DO NOTHING, {@code nothingToken} must read NOTHING and assignments/where
     * must be empty; for DO UPDATE pass {@code nothingToken == null}.
     */
    Upsert conflictUpsert(Token conflictToken, List<Identifier> target,
                          Token doToken, Token nothingToken,
                          List<Assignment> assignments, Optional<Expression> where,
                          SourcePosition pos) {
        requireWord(conflictToken, "CONFLICT", pos);
        requireWord(doToken, "DO", pos);
        if (nothingToken != null) {
            requireWord(nothingToken, "NOTHING", pos);
            refuseIf(!assignments.isEmpty() || where.isPresent(),
                    "ON CONFLICT DO NOTHING with UPDATE payload", pos);
            return new Upsert(UpsertKind.ON_CONFLICT_NOTHING, target, List.of(),
                    Optional.empty(), pos);
        }
        refuseIf(assignments.isEmpty(), "ON CONFLICT DO UPDATE without assignments", pos);
        return new Upsert(UpsertKind.ON_CONFLICT_UPDATE, target, assignments, where, pos);
    }

    /** {@code RETURNING} list — contextual keyword checked here. */
    List<SelectItem> returningItems(Token returningToken, List<SelectItem> items,
                                    SourcePosition pos) {
        requireWord(returningToken, "RETURNING", pos);
        return List.copyOf(items);
    }

    private void requireWord(Token token, String expected, SourcePosition pos) {
        String text = token.getText();
        refuseIf(text == null || !expected.equalsIgnoreCase(text),
                "expected " + expected + " (got '" + text + "')", pos);
    }

    /**
     * Structural recursion flag for coverage render: {@code WITH RECURSIVE} keyword
     * or any CTE body that references its own name (T-SQL omits the keyword).
     * Does not validate recursion semantics.
     */
    boolean isRecursiveWith(boolean recursiveKeyword, List<Cte> ctes) {
        if (recursiveKeyword) {
            return true;
        }
        for (Cte cte : ctes) {
            String name = cte.name().value().toLowerCase(Locale.ROOT);
            if (referencesTableName(cte.query(), name)) {
                return true;
            }
        }
        return false;
    }

    private boolean referencesTableName(Query query, String lowerName) {
        boolean[] hit = {false};
        query.accept(new AbstractAstVisitor<Void>() {
            @Override
            public Void visitTableRef(TableRef ref) {
                if (ref.table().last().value().equalsIgnoreCase(lowerName)) {
                    hit[0] = true;
                }
                return null;
            }
        });
        return hit[0];
    }

    // ------------------------------------------------------------------
    // Identifiers, names, function names
    // ------------------------------------------------------------------

    /** Unquotes/unescapes one identifier token per this dialect's rules (D6).
     *  Also maps {@code nonReservedWord} tokens (KEY/FIRST/LAST/…) to plain Identifiers —
     *  downstream is oblivious to how the name lexed. */
    Identifier identifier(Token token) {
        String text = token.getText();
        SourcePosition p = pos(token);
        char first = text.charAt(0);
        return switch (dialect) {
            case TSQL -> switch (first) {
                case '[' -> new Identifier(undouble(body(text, 1), ']'), true, p);
                case '"' -> new Identifier(undouble(body(text, 1), '"'), true, p);
                default -> new Identifier(text, false, p);
            };
            case MYSQL -> first == '`'
                    ? new Identifier(undouble(body(text, 1), '`'), true, p)
                    : new Identifier(text, false, p);
            case POSTGRESQL -> first == '"'
                    ? new Identifier(undouble(body(text, 1), '"'), true, p)
                    : new Identifier(text, false, p);
        };
    }

    /** Builds a qualified name, refusing more than three parts. */
    QualifiedName qualifiedName(List<Identifier> parts, SourcePosition position) {
        refuseIf(parts.size() > 3, "qualified name with " + parts.size() + " parts", position);
        return new QualifiedName(parts, position);
    }

    /**
     * Canonical function name: uppercased (case-insensitive in all three dialects);
     * a quoted name is refused — uppercasing would corrupt a case-sensitive name.
     */
    String functionName(Identifier name) {
        refuseIf(name.quoted(), "quoted function name \"" + name.value() + "\"", name.pos());
        return name.value().toUpperCase(Locale.ROOT);
    }

    /**
     * Contextual {@code FILTER (WHERE …)} — {@code name} must be the unquoted identifier
     * {@code FILTER}. Avoids reserving {@code FILTER} as a keyword in the shared block.
     */
    Expression aggregateFilterKeyword(Identifier name, Expression predicate) {
        refuseIf(name.quoted() || !name.value().equalsIgnoreCase("FILTER"),
                "aggregate filter keyword \"" + name.value() + "\"", name.pos());
        return predicate;
    }

    /**
     * Contextual {@code EXTRACT(field FROM source)} — {@code name} must be the unquoted
     * identifier {@code EXTRACT}. Avoids reserving {@code EXTRACT} as a keyword.
     */
    ExtractExpression extractExpression(Identifier name, Identifier field, Expression source,
                                        SourcePosition position) {
        requireContextualKeyword(name, "EXTRACT");
        return new ExtractExpression(field.value(), source, position);
    }

    /** Requires an unquoted contextual keyword (VIEW / FUNCTION / TRUNCATE / …). */
    void requireContextualKeyword(Identifier name, String expected) {
        refuseIf(name.quoted() || !name.value().equalsIgnoreCase(expected),
                "expected " + expected + ", got \"" + name.value() + "\"", name.pos());
    }

    Statement dropViewOrRoutine(Identifier kind, QualifiedName name, boolean ifExists,
                                boolean hasSignature, List<DataType> argTypes,
                                Optional<Identifier> cascadeToken, SourcePosition position) {
        boolean cascade = false;
        if (cascadeToken.isPresent()) {
            requireContextualKeyword(cascadeToken.get(), "CASCADE");
            cascade = true;
        }
        if (kind.value().equalsIgnoreCase("VIEW")) {
            refuseIf(kind.quoted(), "quoted DROP object kind", kind.pos());
            refuseIf(hasSignature, "DROP VIEW with argument list", position);
            return new DropViewStatement(name, ifExists, cascade, position);
        }
        if (kind.value().equalsIgnoreCase("FUNCTION")) {
            refuseIf(kind.quoted(), "quoted DROP object kind", kind.pos());
            return new DropRoutineStatement(name, ifExists, cascade, hasSignature, argTypes, position);
        }
        throw refuse("DROP " + kind.value(), kind.pos());
    }

    /**
     * {@code CREATE [OR REPLACE|OR ALTER] VIEW}. {@code headerIds} is either
     * {@code [VIEW]} or {@code [REPLACE|ALTER, VIEW]} (OR is the keyword token).
     */
    CreateViewStatement createView(List<Identifier> headerIds, QualifiedName name,
                                   List<Identifier> columns, Query query,
                                   SourcePosition position) {
        if (headerIds.size() == 1) {
            requireContextualKeyword(headerIds.get(0), "VIEW");
            return new CreateViewStatement(name, columns, query, false, position);
        }
        if (headerIds.size() == 2) {
            String mid = headerIds.get(0).value();
            refuseIf(headerIds.get(0).quoted()
                            || (!"REPLACE".equalsIgnoreCase(mid)
                            && !"ALTER".equalsIgnoreCase(mid)),
                    "expected REPLACE or ALTER, got \"" + mid + "\"",
                    headerIds.get(0).pos());
            requireContextualKeyword(headerIds.get(1), "VIEW");
            return new CreateViewStatement(name, columns, query, true, position);
        }
        throw refuse("malformed CREATE VIEW header", position);
    }

    /**
     * MySQL-style {@code UPDATE t JOIN u ON … SET} → portable {@code UPDATE t SET … FROM …}
     * by folding the first JOIN's ON into WHERE (INNER/CROSS only).
     */
    UpdateStatement updateWithInlineJoins(List<Cte> ctes, boolean recursive,
                                          QualifiedName table, Optional<Identifier> alias,
                                          List<Join> inlineJoins, Optional<TableSource> from,
                                          List<Assignment> assignments, Optional<Expression> where,
                                          SourcePosition position) {
        if (inlineJoins.isEmpty()) {
            return new UpdateStatement(ctes, recursive, table, alias, assignments, from, where,
                    position);
        }
        refuseIf(from.isPresent(),
                "UPDATE cannot combine target JOIN with a separate FROM clause", position);
        for (Join join : inlineJoins) {
            refuseIf(join.kind() != JoinKind.INNER && join.kind() != JoinKind.CROSS,
                    "UPDATE target OUTER JOIN is not supported", join.pos());
            refuseIf(!join.usingColumns().isEmpty(),
                    "UPDATE JOIN USING is not supported; use ON", join.pos());
            refuseIf(join.lateral(), "UPDATE LATERAL join is not supported", join.pos());
        }
        Join first = inlineJoins.get(0);
        List<Join> rest = inlineJoins.subList(1, inlineJoins.size());
        Optional<TableSource> normalizedFrom =
                Optional.of(new TableSource(first.table(), rest, position));
        Optional<Expression> normalizedWhere = where;
        if (first.on().isPresent()) {
            normalizedWhere = andPredicates(first.on().get(), normalizedWhere, first.pos());
        }
        return new UpdateStatement(ctes, recursive, table, alias, assignments, normalizedFrom,
                normalizedWhere, position);
    }

    UpdateStatement updateWithInlineJoins(QualifiedName table, Optional<Identifier> alias,
                                          List<Join> inlineJoins, Optional<TableSource> from,
                                          List<Assignment> assignments, Optional<Expression> where,
                                          SourcePosition position) {
        return updateWithInlineJoins(List.of(), false, table, alias, inlineJoins, from,
                assignments, where, position);
    }

    /** {@code WITH name[(cols)] AS (VALUES …)} → {@code AS (SELECT * FROM (VALUES …) AS name[(cols)])}. */
    Query valuesCteBody(List<RowValue> rows, Identifier alias, Optional<List<Identifier>> columns,
                        SourcePosition position) {
        List<Identifier> cols = columns.orElse(List.of());
        ValuesTable values = new ValuesTable(rows, alias, cols, position);
        QuerySpecification spec = new QuerySpecification(
                Optional.empty(),
                List.of(new SelectStar(Optional.empty(), position)),
                Optional.of(new TableSource(values, List.of(), position)),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                position);
        return new Query(List.of(), false, spec, List.of(), List.of(), Optional.empty(), position);
    }

    private Optional<Expression> andPredicates(Expression left, Optional<Expression> right,
                                               SourcePosition position) {
        if (right.isEmpty()) {
            return Optional.of(left);
        }
        return Optional.of(new BinaryOp(BinaryOperator.AND, left, right.get(), position));
    }

    TruncateStatement truncate(Identifier keyword, QualifiedName table, SourcePosition position) {
        requireContextualKeyword(keyword, "TRUNCATE");
        return new TruncateStatement(table, position);
    }

    AlterColumnType alterColumnType(Identifier column, DataType type,
                                    Optional<Expression> using, SourcePosition position) {
        return new AlterColumnType(column, type, using, position);
    }

    Optional<Expression> usingClause(Identifier keyword, Expression expression) {
        requireContextualKeyword(keyword, "USING");
        return Optional.of(expression);
    }

    void requireModifyKeyword(Identifier keyword) {
        requireContextualKeyword(keyword, "MODIFY");
    }

    void requireTypeKeyword(Identifier keyword) {
        requireContextualKeyword(keyword, "TYPE");
    }

    void requireDataTypeKeywords(Identifier data, Identifier type) {
        requireContextualKeyword(data, "DATA");
        requireContextualKeyword(type, "TYPE");
    }

    // ------------------------------------------------------------------
    // Literals
    // ------------------------------------------------------------------

    NumericLiteral numeric(Token token, boolean decimal) {
        return new NumericLiteral(token.getText(), decimal, pos(token));
    }

    /** Unescapes one string-literal token per this dialect's rules. */
    StringLiteral stringLiteral(Token token) {
        String text = token.getText();
        SourcePosition p = pos(token);
        return switch (dialect) {
            case TSQL -> {
                boolean national = text.charAt(0) == 'N' || text.charAt(0) == 'n';
                yield new StringLiteral(undouble(body(text, national ? 2 : 1), '\''),
                        national, p);
            }
            case MYSQL -> new StringLiteral(
                    unescapeBackslash(body(text, 1), text.charAt(0), true), false, p);
            case POSTGRESQL -> {
                boolean escaped = text.charAt(0) == 'E' || text.charAt(0) == 'e';
                yield new StringLiteral(escaped
                                ? unescapeBackslash(body(text, 2), '\'', false)
                                : undouble(body(text, 1), '\''),
                        false, p);
            }
        };
    }

    /**
     * Builds an {@link IntervalLiteral} from {@code INTERVAL '…'} [unit], normalizing
     * simple {@code 'N unit'} strings to {@code {value, unit}}.
     */
    IntervalLiteral intervalFromString(String content, Optional<String> explicitUnit,
                                       SourcePosition position) {
        if (explicitUnit.isPresent()) {
            return new IntervalLiteral(content.trim(),
                    Optional.of(normalizeIntervalUnit(explicitUnit.get())), position);
        }
        java.util.regex.Matcher m = SIMPLE_INTERVAL.matcher(content.trim());
        if (m.matches()) {
            return new IntervalLiteral(m.group(1),
                    Optional.of(normalizeIntervalUnit(m.group(2))), position);
        }
        return new IntervalLiteral(content, Optional.empty(), position);
    }

    /**
     * Builds an {@link IntervalLiteral} from MySQL-style {@code INTERVAL expr unit}.
     * Non-literal values are refused — the AST carries only a string value.
     */
    IntervalLiteral intervalFromExpression(Expression value, String unit,
                                           SourcePosition position) {
        if (value instanceof NumericLiteral num) {
            return new IntervalLiteral(num.text(),
                    Optional.of(normalizeIntervalUnit(unit)), position);
        }
        if (value instanceof StringLiteral str) {
            return new IntervalLiteral(str.value(),
                    Optional.of(normalizeIntervalUnit(unit)), position);
        }
        throw refuse("INTERVAL with non-literal value", position);
    }

    private static final java.util.regex.Pattern SIMPLE_INTERVAL =
            java.util.regex.Pattern.compile(
                    "^([+-]?\\d+(?:\\.\\d+)?)\\s+([A-Za-z]+)$");

    static String normalizeIntervalUnit(String unit) {
        String u = unit.toLowerCase(Locale.ROOT);
        return switch (u) {
            case "years", "year" -> "year";
            case "months", "month" -> "month";
            case "days", "day" -> "day";
            case "hours", "hour" -> "hour";
            case "minutes", "minute" -> "minute";
            case "seconds", "second" -> "second";
            case "weeks", "week" -> "week";
            case "quarters", "quarter" -> "quarter";
            case "milliseconds", "millisecond", "ms" -> "millisecond";
            default -> u;
        };
    }

    /** Strips {@code skip} leading chars plus the single closing delimiter. */
    private static String body(String text, int skip) {
        return text.substring(skip, text.length() - 1);
    }

    /** Undoes delimiter doubling: {@code ]] → ]}, {@code '' → '}, {@code `` → `}, ... */
    private static String undouble(String body, char delimiter) {
        String twice = String.valueOf(delimiter) + delimiter;
        return body.replace(twice, String.valueOf(delimiter));
    }

    /**
     * Backslash unescaping shared by MySQL strings and PostgreSQL {@code E'...'}
     * strings; delimiter doubling is handled in the same pass. When
     * {@code mySqlRules} is set, {@code \%} and {@code \_} keep their backslash
     * (MySQL treats them as LIKE-pattern escapes, not string escapes).
     */
    private static String unescapeBackslash(String body, char delimiter, boolean mySqlRules) {
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                switch (next) {
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '0' -> sb.append(mySqlRules ? '\0' : '0');
                    case 'Z' -> sb.append(mySqlRules ? (char) 0x1A : 'Z');
                    case '%', '_' -> {
                        if (mySqlRules) {
                            sb.append('\\');
                        }
                        sb.append(next);
                    }
                    default -> sb.append(next);   // covers \\ \' \" and everything else
                }
            } else if (c == delimiter && i + 1 < body.length()
                    && body.charAt(i + 1) == delimiter) {
                sb.append(delimiter);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Expression folding
    // ------------------------------------------------------------------

    /**
     * Left-folds a homogeneous binary-operator chain rule
     * ({@code operand (OP operand)*}) into nested {@link BinaryOp}s. Operators are
     * mapped by token text, so one helper serves every chain rule in all three
     * builders; {@code ||} resolves per dialect (MySQL logical OR, PostgreSQL CONCAT).
     */
    Expression foldBinaryChain(ParserRuleContext ctx, ParseTreeVisitor<Object> builder) {
        Expression result = null;
        BinaryOperator pending = null;
        for (ParseTree child : ctx.children) {
            if (child instanceof TerminalNode terminal) {
                pending = binaryOperator(terminal.getText());
            } else {
                Expression operand = (Expression) child.accept(builder);
                result = result == null
                        ? operand
                        : new BinaryOp(pending, result, operand, pos(ctx));
            }
        }
        return result;
    }

    /**
     * Walks a {@code queryExpression} child list and builds {@link UnionArm}s after
     * each {@code UNION|EXCEPT|INTERSECT [ALL]}. Token type ids differ per dialect
     * grammar; the structural walk is shared.
     */
    List<UnionArm> unionArms(ParserRuleContext ctx,
                             int unionTokenType, int exceptTokenType, int intersectTokenType,
                             int allTokenType, ParseTreeVisitor<Object> builder) {
        List<UnionArm> arms = new ArrayList<>();
        SetOperator op = null;
        boolean all = false;
        for (ParseTree child : ctx.children) {
            if (child instanceof TerminalNode terminal) {
                int type = terminal.getSymbol().getType();
                if (type == unionTokenType) {
                    op = SetOperator.UNION;
                    all = false;
                } else if (type == exceptTokenType) {
                    op = SetOperator.EXCEPT;
                    all = false;
                } else if (type == intersectTokenType) {
                    op = SetOperator.INTERSECT;
                    all = false;
                } else if (op != null && type == allTokenType) {
                    all = true;
                }
            } else if (op != null && child instanceof ParserRuleContext spec) {
                arms.add(new UnionArm(op, all, (QuerySpecification) spec.accept(builder),
                        pos(spec)));
                op = null;
            }
        }
        return arms;
    }

    private BinaryOperator binaryOperator(String text) {
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "OR" -> BinaryOperator.OR;
            case "AND" -> BinaryOperator.AND;
            case "+" -> BinaryOperator.ADD;
            case "-" -> BinaryOperator.SUB;
            case "*" -> BinaryOperator.MUL;
            case "/" -> BinaryOperator.DIV;
            case "%" -> BinaryOperator.MOD;
            // MySQL under default sql_mode: logical OR; PostgreSQL: string concat.
            case "||" -> dialect == Dialect.MYSQL ? BinaryOperator.OR : BinaryOperator.CONCAT;
            case "->" -> BinaryOperator.JSON_GET;
            case "->>" -> BinaryOperator.JSON_GET_TEXT;
            case "#>" -> BinaryOperator.JSON_PATH;
            case "#>>" -> BinaryOperator.JSON_PATH_TEXT;
            case "@>" -> BinaryOperator.JSON_CONTAINS;
            default -> throw new IllegalStateException("Unmapped binary operator: " + text);
        };
    }

    BinaryOperator comparisonOperator(String text) {
        return switch (text) {
            case "=" -> BinaryOperator.EQ;
            case "<>", "!=" -> BinaryOperator.NEQ;
            case "<" -> BinaryOperator.LT;
            case "<=" -> BinaryOperator.LTE;
            case ">" -> BinaryOperator.GT;
            case ">=" -> BinaryOperator.GTE;
            default -> throw new IllegalStateException("Unmapped comparison operator: " + text);
        };
    }

    /**
     * Slices the flat expression list of a CASE rule ({@code CASE expression?
     * (WHEN expression THEN expression)+ (ELSE expression)? END}) into its parts.
     */
    CaseExpression caseExpression(List<Expression> expressions,
                                  List<SourcePosition> whenPositions, boolean hasElse,
                                  SourcePosition position) {
        int whenCount = whenPositions.size();
        int withoutOperand = 2 * whenCount + (hasElse ? 1 : 0);
        boolean hasOperand = expressions.size() == withoutOperand + 1;
        int index = hasOperand ? 1 : 0;
        Optional<Expression> operand =
                hasOperand ? Optional.of(expressions.get(0)) : Optional.empty();
        List<WhenClause> whens = new ArrayList<>(whenCount);
        for (int i = 0; i < whenCount; i++) {
            whens.add(new WhenClause(expressions.get(index), expressions.get(index + 1),
                    whenPositions.get(i)));
            index += 2;
        }
        Optional<Expression> elseValue =
                hasElse ? Optional.of(expressions.get(index)) : Optional.empty();
        return new CaseExpression(operand, whens, elseValue, position);
    }

    // ------------------------------------------------------------------
    // RowLimit unification (§2.2)
    // ------------------------------------------------------------------

    /**
     * T-SQL: {@code TOP n} or {@code ORDER BY ... OFFSET m ROWS [FETCH ... n ...]}.
     * Their combination is refused — SQL Server itself rejects it, and merging
     * would invent semantics. {@code TOP} inside UNION is refused here when
     * {@code hasUnionArms} is true.
     */
    Optional<RowLimit> tsqlRowLimit(Expression top, SourcePosition topPosition,
                                    Expression offset, Expression fetch,
                                    SourcePosition offsetPosition) {
        refuseIf(top != null && offset != null, "TOP combined with OFFSET/FETCH", topPosition);
        if (top != null) {
            return Optional.of(new RowLimit(Optional.of(top), Optional.empty(), topPosition));
        }
        if (offset != null) {
            return Optional.of(new RowLimit(Optional.ofNullable(fetch), Optional.of(offset),
                    offsetPosition));
        }
        return Optional.empty();
    }

    /**
     * Picks the TOP clause from query specs, refusing TOP when the query has UNION
     * arms. Returns {@code null} count/pos when no TOP is present.
     */
    ExtractedTop extractTsqlTop(List<ExtractedTop> tops, boolean hasUnionArms) {
        ExtractedTop found = null;
        for (ExtractedTop top : tops) {
            if (top == null) {
                continue;
            }
            refuseIf(hasUnionArms, "TOP inside UNION", top.position());
            found = top;
        }
        return found;
    }

    /** One T-SQL {@code TOP} clause extracted by the dialect builder. */
    record ExtractedTop(Expression count, SourcePosition position) {
    }

    /** MySQL: {@code LIMIT n [OFFSET m]} or {@code LIMIT m, n} (operand swap). */
    RowLimit mysqlRowLimit(Expression first, Expression second, boolean commaForm,
                           SourcePosition position) {
        if (commaForm) {
            return new RowLimit(Optional.of(second), Optional.of(first), position);
        }
        return new RowLimit(Optional.of(first), Optional.ofNullable(second), position);
    }

    /** PostgreSQL: LIMIT and OFFSET in either order. */
    RowLimit pgRowLimit(Expression first, Expression second, boolean limitFirst,
                        SourcePosition position) {
        if (limitFirst) {
            return new RowLimit(Optional.of(first), Optional.ofNullable(second), position);
        }
        return new RowLimit(Optional.ofNullable(second), Optional.of(first), position);
    }

    // ------------------------------------------------------------------
    // Column definitions and auto-increment
    // ------------------------------------------------------------------

    /** T-SQL {@code IDENTITY(seed, increment)}: only {@code (1,1)} is supported. */
    void checkIdentitySeed(String seed, String increment, SourcePosition position) {
        refuseIf(!"1".equals(seed) || !"1".equals(increment),
                "IDENTITY(" + seed + "," + increment + ")", position);
    }

    /**
     * Assembles a {@link ColumnDefinition}, merging the auto-increment contributed
     * by the type fold (PG SERIAL family) with column-constraint syntax
     * (IDENTITY / AUTO_INCREMENT / GENERATED ... AS IDENTITY).
     */
    ColumnDefinition columnDefinition(Identifier name, FoldedType type,
                                      ColumnAttributes attributes, SourcePosition position) {
        return new ColumnDefinition(name, type.dataType(),
                type.autoIncrement() || attributes.autoIncrement,
                attributes.nullable, Optional.ofNullable(attributes.defaultValue),
                attributes.primaryKey, attributes.unique,
                Optional.ofNullable(attributes.references), position);
    }

    /**
     * Applies one extracted column-constraint kind. Builders classify the ANTLR
     * alternative; the attribute mutation lives once here.
     */
    void applyColumnConstraint(ColumnAttributes attributes, ColumnConstraintKind kind,
                               Expression defaultValue, ForeignKeyRef references) {
        switch (kind) {
            case NOT_NULL -> attributes.notNull();
            case NULL_ALLOWED -> attributes.nullAllowed();
            case DEFAULT -> attributes.defaultValue(defaultValue);
            case PRIMARY_KEY -> attributes.primaryKey();
            case UNIQUE -> attributes.unique();
            case REFERENCES -> attributes.references(references);
            case AUTO_INCREMENT -> attributes.autoIncrement();
        }
    }

    enum ColumnConstraintKind {
        NOT_NULL, NULL_ALLOWED, DEFAULT, PRIMARY_KEY, UNIQUE, REFERENCES, AUTO_INCREMENT
    }

    /** Mutable accumulator the builders fill while walking {@code columnConstraint*}. */
    static final class ColumnAttributes {

        private Optional<Boolean> nullable = Optional.empty();
        private Expression defaultValue;
        private boolean primaryKey;
        private boolean unique;
        private boolean autoIncrement;
        private ForeignKeyRef references;

        void notNull() {
            nullable = Optional.of(false);
        }

        void nullAllowed() {
            nullable = Optional.of(true);
        }

        void defaultValue(Expression value) {
            defaultValue = value;
        }

        void primaryKey() {
            primaryKey = true;
        }

        void unique() {
            unique = true;
        }

        void autoIncrement() {
            autoIncrement = true;
        }

        void references(ForeignKeyRef ref) {
            references = ref;
        }
    }

    // ------------------------------------------------------------------
    // Type folding (D3, §2.3)
    // ------------------------------------------------------------------

    /** A folded type plus the auto-increment it implies (PG SERIAL family only). */
    record FoldedType(DataType dataType, boolean autoIncrement) {
    }

    /** Folds a column's declared type; SERIAL folds carry auto-increment. */
    FoldedType foldColumnType(String word1, String word2, List<String> args,
                              SourcePosition position) {
        return fold(word1, word2, args, position);
    }

    /** Folds a CAST/CONVERT target; auto-increment folds (SERIAL) are meaningless here. */
    DataType foldCastType(String word1, String word2, List<String> args,
                          SourcePosition position) {
        FoldedType folded = fold(word1, word2, args, position);
        refuseIf(folded.autoIncrement(), "type " + word1.toUpperCase(Locale.ROOT) + " in CAST",
                position);
        return folded.dataType();
    }

    /** Counts {@code []} suffixes on a {@code dataType} parse tree. */
    static int arrayDims(ParserRuleContext ctx) {
        int dims = 0;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if ("[".equals(ctx.getChild(i).getText())) {
                dims++;
            }
        }
        return dims;
    }

    static DataType withArrayDims(DataType type, int dims) {
        return dims == 0 ? type
                : new DataType(type.type(), type.length(), type.scale(), dims);
    }

    static FoldedType withArrayDims(FoldedType folded, int dims) {
        return dims == 0 ? folded
                : new FoldedType(withArrayDims(folded.dataType(), dims), folded.autoIncrement());
    }

    private FoldedType fold(String word1, String word2, List<String> args,
                            SourcePosition position) {
        String name = word1.toUpperCase(Locale.ROOT);
        if (word2 != null) {
            name = name + " " + word2.toUpperCase(Locale.ROOT);
            // The builder-side whitelist for the two-word grammar form (§3 fix 2).
            refuseIf(!name.equals("DOUBLE PRECISION"), "type " + word1 + " " + word2, position);
        }
        // MAX is part of the lookup key itself and is consumed by the fold (§2.3).
        if (dialect == Dialect.TSQL && name.equals("VARBINARY")
                && args.size() == 1 && args.get(0).equalsIgnoreCase("MAX")) {
            return new FoldedType(
                    new DataType(GenericType.BLOB, Optional.empty(), Optional.empty()), false);
        }
        Fold entry = typeTable.get(name);
        if (entry == null) {
            throw refuse("type " + name, position);
        }
        GenericType generic = entry.type();
        // MySQL boolean idiom: TINYINT(1) → BOOLEAN (ROADMAP Phase 4 flagship).
        if (dialect == Dialect.MYSQL
                && generic == GenericType.TINYINT
                && args.size() == 1
                && args.get(0).equals("1")) {
            return new FoldedType(
                    new DataType(GenericType.BOOLEAN, Optional.empty(), Optional.empty()),
                    entry.autoIncrement());
        }
        Optional<TypeLength> length = Optional.empty();
        Optional<Integer> scale = Optional.empty();
        if (!args.isEmpty()) {
            refuseIf(!PARAMETERIZABLE.contains(generic),
                    "length argument on type " + name, position);
            String first = args.get(0);
            if (first.equalsIgnoreCase("MAX")) {
                refuseIf(generic != GenericType.VARCHAR && generic != GenericType.NVARCHAR,
                        "MAX length on type " + name, position);
                length = Optional.of(new MaxLength());
            } else {
                length = Optional.of(new FixedLength(parseTypeArg(first, name, position)));
            }
            if (args.size() > 1) {
                String second = args.get(1);
                refuseIf(generic != GenericType.DECIMAL || second.equalsIgnoreCase("MAX"),
                        "scale argument on type " + name, position);
                scale = Optional.of(parseTypeArg(second, name, position));
            }
        }
        return new FoldedType(new DataType(generic, length, scale), entry.autoIncrement());
    }

    private int parseTypeArg(String text, String typeName, SourcePosition position) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw refuse("length argument on type " + typeName, position);
        }
    }

    private record Fold(GenericType type, boolean autoIncrement) {

        static Fold of(GenericType type) {
            return new Fold(type, false);
        }

        static Fold auto(GenericType type) {
            return new Fold(type, true);
        }
    }

    private static Map<String, Fold> typeTable(Dialect dialect) {
        return switch (dialect) {
            case TSQL -> Map.ofEntries(
                    Map.entry("INT", Fold.of(GenericType.INTEGER)),
                    Map.entry("INTEGER", Fold.of(GenericType.INTEGER)),
                    Map.entry("BIGINT", Fold.of(GenericType.BIGINT)),
                    Map.entry("SMALLINT", Fold.of(GenericType.SMALLINT)),
                    Map.entry("TINYINT", Fold.of(GenericType.TINYINT)),
                    Map.entry("BIT", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("DECIMAL", Fold.of(GenericType.DECIMAL)),
                    Map.entry("NUMERIC", Fold.of(GenericType.DECIMAL)),
                    Map.entry("FLOAT", Fold.of(GenericType.DOUBLE)),
                    Map.entry("REAL", Fold.of(GenericType.FLOAT)),
                    Map.entry("DOUBLE PRECISION", Fold.of(GenericType.DOUBLE)),
                    Map.entry("NVARCHAR", Fold.of(GenericType.NVARCHAR)),
                    Map.entry("VARCHAR", Fold.of(GenericType.VARCHAR)),
                    // Documented v1 simplification: fixed-width national char folds
                    // to variable-width generic.
                    Map.entry("NCHAR", Fold.of(GenericType.NVARCHAR)),
                    Map.entry("CHAR", Fold.of(GenericType.CHAR)),
                    Map.entry("DATETIME2", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATETIME", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATE", Fold.of(GenericType.DATE)),
                    Map.entry("TIME", Fold.of(GenericType.TIME)),
                    Map.entry("IMAGE", Fold.of(GenericType.BLOB)),
                    Map.entry("UNIQUEIDENTIFIER", Fold.of(GenericType.UUID)),
                    Map.entry("JSON", Fold.of(GenericType.JSON)),
                    Map.entry("JSONB", Fold.of(GenericType.JSONB)),
                    Map.entry("UUID", Fold.of(GenericType.UUID)),
                    Map.entry("BYTEA", Fold.of(GenericType.BLOB)),
                    Map.entry("SERIAL", Fold.auto(GenericType.INTEGER)),
                    Map.entry("BIGSERIAL", Fold.auto(GenericType.BIGINT)),
                    Map.entry("SMALLSERIAL", Fold.auto(GenericType.SMALLINT)));
            case MYSQL -> Map.ofEntries(
                    Map.entry("INT", Fold.of(GenericType.INTEGER)),
                    Map.entry("INTEGER", Fold.of(GenericType.INTEGER)),
                    Map.entry("TINYINT", Fold.of(GenericType.TINYINT)),
                    Map.entry("SMALLINT", Fold.of(GenericType.SMALLINT)),
                    Map.entry("BIGINT", Fold.of(GenericType.BIGINT)),
                    Map.entry("DECIMAL", Fold.of(GenericType.DECIMAL)),
                    Map.entry("NUMERIC", Fold.of(GenericType.DECIMAL)),
                    Map.entry("FLOAT", Fold.of(GenericType.FLOAT)),
                    Map.entry("DOUBLE", Fold.of(GenericType.DOUBLE)),
                    Map.entry("DOUBLE PRECISION", Fold.of(GenericType.DOUBLE)),
                    Map.entry("VARCHAR", Fold.of(GenericType.VARCHAR)),
                    Map.entry("CHAR", Fold.of(GenericType.CHAR)),
                    Map.entry("TEXT", Fold.of(GenericType.TEXT)),
                    Map.entry("BOOLEAN", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("BOOL", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("DATETIME", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("TIMESTAMP", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATE", Fold.of(GenericType.DATE)),
                    Map.entry("TIME", Fold.of(GenericType.TIME)),
                    Map.entry("BLOB", Fold.of(GenericType.BLOB)),
                    Map.entry("JSON", Fold.of(GenericType.JSON)),
                    Map.entry("JSONB", Fold.of(GenericType.JSONB)),
                    Map.entry("UUID", Fold.of(GenericType.UUID)),
                    Map.entry("BYTEA", Fold.of(GenericType.BLOB)),
                    Map.entry("SERIAL", Fold.auto(GenericType.INTEGER)),
                    Map.entry("BIGSERIAL", Fold.auto(GenericType.BIGINT)),
                    Map.entry("SMALLSERIAL", Fold.auto(GenericType.SMALLINT)));
            case POSTGRESQL -> Map.ofEntries(
                    Map.entry("INTEGER", Fold.of(GenericType.INTEGER)),
                    Map.entry("INT", Fold.of(GenericType.INTEGER)),
                    Map.entry("INT4", Fold.of(GenericType.INTEGER)),
                    Map.entry("SMALLINT", Fold.of(GenericType.SMALLINT)),
                    Map.entry("INT2", Fold.of(GenericType.SMALLINT)),
                    Map.entry("BIGINT", Fold.of(GenericType.BIGINT)),
                    Map.entry("INT8", Fold.of(GenericType.BIGINT)),
                    Map.entry("DECIMAL", Fold.of(GenericType.DECIMAL)),
                    Map.entry("NUMERIC", Fold.of(GenericType.DECIMAL)),
                    Map.entry("REAL", Fold.of(GenericType.FLOAT)),
                    Map.entry("DOUBLE PRECISION", Fold.of(GenericType.DOUBLE)),
                    Map.entry("FLOAT8", Fold.of(GenericType.DOUBLE)),
                    Map.entry("VARCHAR", Fold.of(GenericType.VARCHAR)),
                    Map.entry("CHAR", Fold.of(GenericType.CHAR)),
                    Map.entry("TEXT", Fold.of(GenericType.TEXT)),
                    Map.entry("BOOLEAN", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("BOOL", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("TIMESTAMP", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATE", Fold.of(GenericType.DATE)),
                    Map.entry("TIME", Fold.of(GenericType.TIME)),
                    Map.entry("BYTEA", Fold.of(GenericType.BLOB)),
                    Map.entry("JSON", Fold.of(GenericType.JSON)),
                    Map.entry("JSONB", Fold.of(GenericType.JSONB)),
                    Map.entry("UUID", Fold.of(GenericType.UUID)),
                    Map.entry("SERIAL", Fold.auto(GenericType.INTEGER)),
                    Map.entry("BIGSERIAL", Fold.auto(GenericType.BIGINT)),
                    Map.entry("SMALLSERIAL", Fold.auto(GenericType.SMALLINT)));
        };
    }
}
