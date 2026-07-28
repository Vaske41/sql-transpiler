package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * An {@code INTERVAL} value normalized to {@code {value, unit}}.
 * <ul>
 *   <li>PostgreSQL {@code INTERVAL '1 day'} → value={@code 1}, unit={@code day}</li>
 *   <li>MySQL {@code INTERVAL 1 DAY} / {@code INTERVAL '1' MINUTE} → same shape</li>
 *   <li>Computed forms like {@code INTERVAL (expr) YEAR} carry the expression as
 *       {@code value} with a present unit</li>
 *   <li>Compound forms like {@code INTERVAL '1 day 03:00'} keep the full string as a
 *       {@link StringLiteral} value with an empty unit (refused toward MySQL / T-SQL)</li>
 * </ul>
 */
public record IntervalLiteral(Expression value, Optional<String> unit, SourcePosition pos)
        implements Literal {

    public IntervalLiteral {
        unit = unit != null ? unit : Optional.empty();
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitIntervalLiteral(this);
    }
}
