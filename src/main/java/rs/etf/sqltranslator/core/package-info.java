/**
 * Shared kernel ({@link Dialect}, {@link SourcePosition}, parse/unsupported
 * exceptions) and the pipeline facade ({@link Translator}, {@link TranslationOutput}).
 *
 * <p>In this single-module layout {@code core} is both the dependency-light types
 * package and the ROADMAP-mandated facade home: {@code Translator} depends on
 * parser / transform / codegen so CLI and evaluation harnesses call one entry
 * point. If the project later splits Maven modules, move the facade to a thin
 * {@code api} module and keep only kernel types here.
 */
package rs.etf.sqltranslator.core;
