package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * Window frame clause ({@code ROWS}/{@code RANGE} …). Parsed in Wave 1 so the
 * error is a clean build refusal, not a syntax error; builders refuse any frame.
 */
public record WindowFrame(FrameMode mode, FrameBound start, Optional<FrameBound> end,
                          SourcePosition pos) implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitWindowFrame(this);
    }
}
