package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.ColumnInfo;
import rs.etf.sqltranslator.analysis.TableSchema;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Cte;
import rs.etf.sqltranslator.ast.DeleteStatement;
import rs.etf.sqltranslator.ast.DerivedTable;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.ast.ValuesTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Rule base for catalog-aware transforms. Tracks a stack of FROM-clause scopes
 * (alias or table name → TableSchema) so {@link #resolve(ColumnRef)} can answer
 * "what column is this?" for type-dependent rewrites. Unresolvable references
 * return empty; callers degrade to warnings, never guesses.
 *
 * <p><b>v1 scope contract:</b> resolution uses the <em>top frame only</em> — no
 * outer-scope correlation through expression-position subqueries. Correlated
 * references to enclosing FROM columns systematically return empty.
 *
 * <p>CTE names are tracked in a separate {@code cteFrames} stack consulted from
 * {@link #relationScope(Relation)} <em>before</em> catalog lookup (SQL shadowing)
 * — never buried under the FROM frame.
 *
 * <p>Subclasses override the {@code afterX} hooks (called with scope still pushed)
 * instead of the corresponding {@code visitX} methods, which are final here.
 */
public abstract class ScopedTransformer extends rs.etf.sqltranslator.ast.AstTransformer {

    /** One table visible in the current scope, under its alias or its own name. */
    private record ScopedTable(String key, TableSchema schema) {
    }

    protected final TranslationContext ctx;
    private final Deque<List<ScopedTable>> scopes = new ArrayDeque<>();
    private final Deque<Map<String, TableSchema>> cteFrames = new ArrayDeque<>();

    protected ScopedTransformer(TranslationContext ctx) {
        this.ctx = ctx;
    }

    // --- scope lifecycle (final: subclasses use the afterX hooks) ---

    @Override
    public final Object visitQuery(Query node) {
        boolean pushed = !node.ctes().isEmpty();
        if (pushed) {
            pushCteFrame();
        }
        try {
            List<Cte> rebuiltCtes = new ArrayList<>(node.ctes().size());
            for (Cte cte : node.ctes()) {
                Cte rebuilt = (Cte) cte.accept(this);
                rebuiltCtes.add(rebuilt);
                String name = rebuilt.name().value().toLowerCase(Locale.ROOT);
                cteSchemas().put(name, emptySchema(rebuilt.name()));
            }
            return new Query(
                    rebuiltCtes,
                    node.recursive(),
                    rebuild(node.first()),
                    rebuildList(node.unionArms()),
                    rebuildList(node.orderBy()),
                    rebuildOptional(node.limit()),
                    node.pos());
        } finally {
            if (pushed) {
                popCteFrame();
            }
        }
    }

    @Override
    public final Object visitQuerySpecification(QuerySpecification node) {
        scopes.push(node.from().map(this::tablesOf).orElse(List.of()));
        try {
            return afterQuerySpecification(
                    (QuerySpecification) super.visitQuerySpecification(node));
        } finally {
            scopes.pop();
        }
    }

    @Override
    public final Object visitUpdateStatement(UpdateStatement node) {
        boolean pushed = !node.ctes().isEmpty();
        if (pushed) {
            pushCteFrame();
        }
        try {
            List<Cte> rebuiltCtes = new ArrayList<>(node.ctes().size());
            for (Cte cte : node.ctes()) {
                Cte rebuilt = (Cte) cte.accept(this);
                rebuiltCtes.add(rebuilt);
                String name = rebuilt.name().value().toLowerCase(Locale.ROOT);
                cteSchemas().put(name, emptySchema(rebuilt.name()));
            }
            scopes.push(tableScope(node.table()));
            try {
                return new UpdateStatement(
                        rebuiltCtes,
                        node.recursive(),
                        rebuild(node.table()),
                        rebuildOptional(node.alias()),
                        rebuildList(node.assignments()),
                        rebuildOptional(node.from()),
                        rebuildOptional(node.where()),
                        node.pos());
            } finally {
                scopes.pop();
            }
        } finally {
            if (pushed) {
                popCteFrame();
            }
        }
    }

    @Override
    public final Object visitDeleteStatement(DeleteStatement node) {
        scopes.push(tableScope(node.table()));
        try {
            return super.visitDeleteStatement(node);
        } finally {
            scopes.pop();
        }
    }

    @Override
    public final Object visitInsertStatement(InsertStatement node) {
        scopes.push(tableScope(node.table()));
        try {
            return afterInsertStatement((InsertStatement) super.visitInsertStatement(node));
        } finally {
            scopes.pop();
        }
    }

    /** Hook: rebuilt spec, scope still pushed. Default: identity. */
    protected Object afterQuerySpecification(QuerySpecification rebuilt) {
        return rebuilt;
    }

    /** Hook: rebuilt insert, its table scope still pushed. Default: identity. */
    protected Object afterInsertStatement(InsertStatement rebuilt) {
        return rebuilt;
    }

    // --- resolution ---

    /** Resolves a column reference against the current scope stack (top frame only). */
    protected final Optional<ColumnInfo> resolve(ColumnRef ref) {
        List<ScopedTable> frame = scopes.peek();
        if (frame == null) {
            return Optional.empty();
        }
        List<rs.etf.sqltranslator.ast.Identifier> parts = ref.name().parts();
        String column = parts.get(parts.size() - 1).value();
        if (parts.size() == 1) {
            List<ColumnInfo> hits = new ArrayList<>();
            for (ScopedTable table : frame) {
                table.schema().column(column).ifPresent(hits::add);
            }
            return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
        }
        String qualifier = parts.get(parts.size() - 2).value().toLowerCase(Locale.ROOT);
        for (ScopedTable table : frame) {
            if (table.key().equals(qualifier)) {
                return table.schema().column(column);
            }
        }
        return Optional.empty();
    }

    /**
     * Best-effort type family: literals, catalog-resolved columns, CAST targets,
     * and a few operator-result heuristics. Unsupported shapes ({@code FunctionCall},
     * {@code CaseExpression}, {@code COALESCE}, scalar subqueries, …) return empty —
     * callers must degrade (warn / no-op), never invent a type.
     */
    protected final Optional<TypeFamily> familyOf(Expression expr) {
        if (expr instanceof NumericLiteral) {
            return Optional.of(TypeFamily.NUMERIC);
        }
        if (expr instanceof StringLiteral) {
            return Optional.of(TypeFamily.STRING);
        }
        if (expr instanceof BooleanLiteral) {
            return Optional.of(TypeFamily.BOOLEAN);
        }
        if (expr instanceof CastExpression cast) {
            return Optional.of(TypeFamily.of(cast.targetType().type()));
        }
        if (expr instanceof ColumnRef ref) {
            return resolve(ref).map(c -> TypeFamily.of(c.type().type()));
        }
        if (expr instanceof rs.etf.sqltranslator.ast.BinaryOp op) {
            return Optional.of(switch (op.op()) {
                case ADD, SUB, MUL, DIV, MOD -> TypeFamily.NUMERIC;
                case CONCAT, JSON_GET, JSON_GET_TEXT, JSON_PATH, JSON_PATH_TEXT -> TypeFamily.STRING;
                case OR, AND, EQ, NEQ, LT, LTE, GT, GTE, JSON_CONTAINS -> TypeFamily.BOOLEAN;
            });
        }
        if (expr instanceof rs.etf.sqltranslator.ast.UnaryOp op) {
            return Optional.of(switch (op.op()) {
                case NEG, POS -> TypeFamily.NUMERIC;
                case NOT -> TypeFamily.BOOLEAN;
            });
        }
        return Optional.empty();
    }

    // --- CTE frames ---

    private void pushCteFrame() {
        Map<String, TableSchema> parent = cteFrames.isEmpty() ? Map.of() : cteFrames.peek();
        cteFrames.push(new HashMap<>(parent));
    }

    private void popCteFrame() {
        cteFrames.pop();
    }

    /** Top CTE map (mutable), or empty when no frame is pushed. */
    protected final Map<String, TableSchema> cteSchemas() {
        return cteFrames.isEmpty() ? Map.of() : cteFrames.peek();
    }

    private static TableSchema emptySchema(Identifier name) {
        QualifiedName qn = new QualifiedName(
                List.of(new Identifier(name.value(), false, name.pos())),
                name.pos());
        return new TableSchema(qn, List.of());
    }

    // --- scope construction ---

    private List<ScopedTable> tablesOf(TableSource from) {
        List<ScopedTable> tables = new ArrayList<>(relationScope(from.first()));
        for (Join join : from.joins()) {
            tables.addAll(relationScope(join.table()));
        }
        return tables;
    }

    private List<ScopedTable> relationScope(Relation relation) {
        if (relation instanceof TableRef ref) {
            // SQL shadowing: active CTE names win over catalog base tables.
            String key = ref.alias().map(Identifier::value)
                    .orElse(ref.table().last().value())
                    .toLowerCase(Locale.ROOT);
            String cteKey = ref.table().last().value().toLowerCase(Locale.ROOT);
            Map<String, TableSchema> ctes = cteSchemas();
            TableSchema cte = ctes.get(cteKey);
            if (cte != null) {
                return List.of(new ScopedTable(key, cte));
            }
            return tableScope(ref);
        }
        if (relation instanceof DerivedTable derived) {
            String key = derived.alias().value().toLowerCase(Locale.ROOT);
            QualifiedName qn = new QualifiedName(
                    List.of(new Identifier(derived.alias().value(), false, derived.alias().pos())),
                    derived.alias().pos());
            return List.of(new ScopedTable(key, new TableSchema(qn, List.of())));
        }
        if (relation instanceof ValuesTable values) {
            String key = values.alias().value().toLowerCase(Locale.ROOT);
            QualifiedName qn = new QualifiedName(
                    List.of(new Identifier(values.alias().value(), false, values.alias().pos())),
                    values.alias().pos());
            return List.of(new ScopedTable(key, new TableSchema(qn, List.of())));
        }
        throw new IllegalStateException("unknown Relation: " + relation.getClass());
    }

    private List<ScopedTable> tableScope(TableRef ref) {
        String key = ref.alias().map(a -> a.value())
                .orElse(ref.table().last().value());
        return scopedTable(key, ref.table());
    }

    private List<ScopedTable> tableScope(QualifiedName table) {
        return scopedTable(table.last().value(), table);
    }

    private List<ScopedTable> scopedTable(String key, QualifiedName table) {
        return ctx.catalog().table(table.last().value())
                .map(schema -> List.of(new ScopedTable(key.toLowerCase(Locale.ROOT), schema)))
                .orElse(List.of());
    }
}
