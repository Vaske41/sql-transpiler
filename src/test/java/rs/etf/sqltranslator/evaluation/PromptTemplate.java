package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Loads and renders {@code evaluation/prompts/v1.txt}. */
final class PromptTemplate {

    static final Path DEFAULT = Path.of("evaluation", "prompts", "v1.txt");
    static final String VERSION = "v1";

    private final String template;

    PromptTemplate(String template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    static PromptTemplate load() throws IOException {
        return load(DEFAULT);
    }

    static PromptTemplate load(Path path) throws IOException {
        return new PromptTemplate(Files.readString(path, StandardCharsets.UTF_8));
    }

    String render(Dialect source, Dialect target, String sql) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sql, "sql");
        return template
                .replace("{src}", cliName(source))
                .replace("{tgt}", cliName(target))
                .replace("{sql}", sql);
    }

    static String cliName(Dialect dialect) {
        return dialect.name().toLowerCase(Locale.ROOT);
    }
}
