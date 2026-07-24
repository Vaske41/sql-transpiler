package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * Window frame clause ({@code ROWS}/{@code RANGE} …). Printed structurally where
 * the target accepts the shape; T-SQL refuses {@code RANGE} with offset bounds
 * ({@code n PRECEDING/FOLLOWING}).
 */
public record WindowFrame(FrameMode mode, FrameBound start, Optional<FrameBound> end,
                          SourcePosition pos) implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitWindowFrame(this);
    }
}
