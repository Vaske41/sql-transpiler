package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * One join step; {@code on} is empty exactly for {@link JoinKind#CROSS}
 * (and for T-SQL {@code OUTER APPLY}, modeled as LEFT + lateral with empty ON),
 * or when {@code usingColumns} is non-empty ({@code JOIN … USING (…)}).
 */
public record Join(JoinKind kind, Relation table, Optional<Expression> on,
                   List<Identifier> usingColumns, boolean lateral, SourcePosition pos)
        implements AstNode {

    public Join {
        usingColumns = List.copyOf(usingColumns);
        if (on.isPresent() && !usingColumns.isEmpty()) {
            throw new IllegalArgumentException("JOIN cannot carry both ON and USING");
        }
    }

    /** Non-lateral join with ON (or empty ON for CROSS). */
    public Join(JoinKind kind, Relation table, Optional<Expression> on, SourcePosition pos) {
        this(kind, table, on, List.of(), false, pos);
    }

    /** Lateral/non-lateral join with ON (existing call sites). */
    public Join(JoinKind kind, Relation table, Optional<Expression> on, boolean lateral,
                SourcePosition pos) {
        this(kind, table, on, List.of(), lateral, pos);
    }

    /** Non-lateral {@code JOIN … USING (cols)}. */
    public static Join using(JoinKind kind, Relation table, List<Identifier> columns,
                             SourcePosition pos) {
        return new Join(kind, table, Optional.empty(), columns, false, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitJoin(this);
    }
}
