package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL / MySQL JSON access operators ({@code ->}, {@code ->>}, {@code #>}, {@code #>>}).
 */
class JsonOperatorTest {

    @Test
    void arrowTextParsesAsJsonGetText() {
        BinaryOp op = firstJsonOp("SELECT data ->> 'id' FROM t;", Dialect.POSTGRESQL);
        assertThat(op.op()).isEqualTo(BinaryOperator.JSON_GET_TEXT);
        assertThat(op.right()).isInstanceOf(StringLiteral.class);
        assertThat(((StringLiteral) op.right()).value()).isEqualTo("id");
        assertThat(new AstDumper().dump(op)).contains("BinaryOp op=JSON_GET_TEXT");
    }

    @Test
    void arrowObjectParsesAsJsonGet() {
        BinaryOp op = firstJsonOp("SELECT data -> 'k' FROM t;", Dialect.POSTGRESQL);
        assertThat(op.op()).isEqualTo(BinaryOperator.JSON_GET);
    }

    @Test
    void chainedArrowParsesLeftAssociative() {
        BinaryOp op = firstJsonOp("SELECT (a -> 'x') ->> 'y' FROM t;", Dialect.POSTGRESQL);
        assertThat(op.op()).isEqualTo(BinaryOperator.JSON_GET_TEXT);
        assertThat(op.left()).isInstanceOf(BinaryOp.class);
        assertThat(((BinaryOp) op.left()).op()).isEqualTo(BinaryOperator.JSON_GET);
    }

    @Test
    void hashPathParsesAsJsonPath() {
        BinaryOp op = firstJsonOp("SELECT col #> '{a,b}' FROM t;", Dialect.POSTGRESQL);
        assertThat(op.op()).isEqualTo(BinaryOperator.JSON_PATH);
        assertThat(op.right()).isInstanceOf(StringLiteral.class);
        assertThat(((StringLiteral) op.right()).value()).isEqualTo("{a,b}");
    }

    private static BinaryOp firstJsonOp(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        List<BinaryOp> ops = new ArrayList<>();
        script.accept(new AbstractAstVisitor<Void>() {
            @Override
            public Void visitBinaryOp(BinaryOp node) {
                if (isJsonOp(node.op())) {
                    ops.add(node);
                }
                return super.visitBinaryOp(node);
            }
        });
        assertThat(ops).isNotEmpty();
        return ops.get(ops.size() - 1);
    }

    private static boolean isJsonOp(BinaryOperator op) {
        return op == BinaryOperator.JSON_GET
                || op == BinaryOperator.JSON_GET_TEXT
                || op == BinaryOperator.JSON_PATH
                || op == BinaryOperator.JSON_PATH_TEXT
                || op == BinaryOperator.JSON_CONTAINS;
    }
}
