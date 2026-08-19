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
    private final boolean placeholder;

    PromptTemplate(String template) {
        this(template, false);
    }

    private PromptTemplate(String template, boolean placeholder) {
        this.template = Objects.requireNonNull(template, "template");
        this.placeholder = placeholder;
    }

    static PromptTemplate load() throws IOException {
        return load(DEFAULT);
    }

    static PromptTemplate load(Path path) throws IOException {
        return new PromptTemplate(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * For fixture-only adapters ({@code forceOffline=true}), which never render a prompt.
     * {@code evaluation/} is gitignored, so CI has no template; returning a placeholder lets
     * the offline driver run there instead of failing at construction. The placeholder throws
     * if anything ever tries to render it, and live paths must keep calling {@link #load()}
     * so a genuinely missing template still fails loudly.
     */
    static PromptTemplate loadOrPlaceholder() throws IOException {
        return Files.exists(DEFAULT) ? load() : new PromptTemplate("", true);
    }

    String render(Dialect source, Dialect target, String sql) {
        if (placeholder) {
            throw new IllegalStateException(
                    "cannot render: " + DEFAULT + " is absent and this is the offline placeholder");
        }
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
