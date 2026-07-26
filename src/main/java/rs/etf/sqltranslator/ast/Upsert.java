package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * INSERT upsert: PostgreSQL {@code ON CONFLICT … DO NOTHING/UPDATE} or MySQL
 * {@code ON DUPLICATE KEY UPDATE}. Conflict target columns are empty for the
 * MySQL form (and for {@code ON CONFLICT} without a target).
 */
public record Upsert(UpsertKind kind, List<Identifier> conflictTarget,
                     List<Assignment> assignments, Optional<Expression> where,
                     SourcePosition pos)
        implements AstNode {

    public Upsert {
        conflictTarget = List.copyOf(conflictTarget);
        assignments = List.copyOf(assignments);
        if (kind == UpsertKind.ON_CONFLICT_NOTHING && !assignments.isEmpty()) {
            throw new IllegalArgumentException("DO NOTHING cannot carry assignments");
        }
        if (kind != UpsertKind.ON_CONFLICT_NOTHING && assignments.isEmpty()) {
            throw new IllegalArgumentException("UPDATE upsert requires assignments");
        }
        if (kind == UpsertKind.ON_DUPLICATE_KEY && where.isPresent()) {
            throw new IllegalArgumentException("ON DUPLICATE KEY cannot carry WHERE");
        }
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUpsert(this);
    }
}
