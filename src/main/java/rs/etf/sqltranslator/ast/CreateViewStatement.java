package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/**
 * {@code CREATE [OR REPLACE|OR ALTER] VIEW name [(cols)] AS query}.
 * {@code replaceOrAlter} is true when the source used OR REPLACE / OR ALTER.
 */
public record CreateViewStatement(QualifiedName name, List<Identifier> columns, Query query,
                                  boolean replaceOrAlter, SourcePosition pos)
        implements Statement {

    public CreateViewStatement {
        columns = List.copyOf(columns);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCreateViewStatement(this);
    }
}
