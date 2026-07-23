package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * A function call. {@code name} is uppercased at build time (function names are
 * case-insensitive in all three dialects); {@code star} marks {@code COUNT(*)} and
 * {@code quantifier} carries {@code COUNT(DISTINCT x)}. Optional {@code orderBy}
 * holds in-argument {@code ORDER BY} for ordered aggregates; {@code filter} holds
 * a {@code FILTER (WHERE …)} predicate when present. Optional {@code window}
 * holds a frameless {@code OVER (…)} overlay when present.
 */
public record FunctionCall(String name, List<Expression> args, boolean star,
                           Optional<SetQuantifier> quantifier,
                           List<OrderItem> orderBy,
                           Optional<Expression> filter,
                           Optional<WindowSpec> window,
                           SourcePosition pos) implements Expression {

    public FunctionCall {
        args = List.copyOf(args);
        orderBy = List.copyOf(orderBy);
    }

    /** Compact form — no in-arg ORDER BY, no FILTER. Keeps existing call sites unchanged. */
    public FunctionCall(String name, List<Expression> args, boolean star,
                        Optional<SetQuantifier> quantifier, Optional<WindowSpec> window,
                        SourcePosition pos) {
        this(name, args, star, quantifier, List.of(), Optional.empty(), window, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitFunctionCall(this);
    }
}
