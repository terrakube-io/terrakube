package io.terrakube.executor.service.terraform;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * The {@code -backend-config} arguments of a {@code TF_CLI_ARGS_init} value. They configure the backend of the
 * workspace's own type (S3, azurerm, gcs). When the executor swaps that backend for the HTTP one, Terraform rejects
 * every key the HTTP backend does not know, so they have to go.
 *
 * <p>Terraform splits the value like a shell does, so this reads single and double quotes and backslashes the same way
 * and leaves every other word exactly as it was written.
 */
final class BackendConfigArguments {
    private static final String NAME = "-backend-config";

    /**
     * @param arguments the value without the {@code -backend-config} arguments, or {@code null} when nothing is left
     * @param removed   what each removed argument pointed at, never its value
     */
    record Stripped(String arguments, List<String> removed) {
    }

    /** One word of the value: as it was written, and as Terraform reads it once the quotes are gone. */
    private record Word(String source, String value) {
    }

    private BackendConfigArguments() {
    }

    static Stripped strip(String arguments) {
        Stripped unchanged = new Stripped(arguments, List.of());
        if (arguments == null || !arguments.contains(NAME)) {
            return unchanged;
        }
        // An unbalanced quote is left for Terraform to report, with the words as the user wrote them.
        Optional<List<Word>> words = new Splitter(arguments).split();
        if (words.isEmpty()) {
            return unchanged;
        }

        List<String> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Iterator<Word> remaining = words.get().iterator();
        while (remaining.hasNext()) {
            Word word = remaining.next();
            String flag = word.value().startsWith("--") ? word.value().substring(1) : word.value();
            if (flag.startsWith(NAME + "=")) {
                removed.add(describe(flag.substring(NAME.length() + 1)));
            } else if (flag.equals(NAME)) {
                removed.add(remaining.hasNext() ? describe(remaining.next().value()) : "(no value)");
            } else {
                kept.add(word.source());
            }
        }
        if (removed.isEmpty()) {
            return unchanged;
        }
        return new Stripped(kept.isEmpty() ? null : String.join(" ", kept), removed);
    }

    /** A {@code key=value} pair is named by its key, a file by its path. A value is never returned. */
    private static String describe(String backendConfig) {
        int equals = backendConfig.indexOf('=');
        return equals > 0 ? backendConfig.substring(0, equals).trim() : backendConfig;
    }

    /** Splits a value into words the way a shell does. */
    private static final class Splitter {
        private final String text;
        private final List<Word> words = new ArrayList<>();
        private final StringBuilder source = new StringBuilder();
        private final StringBuilder value = new StringBuilder();
        private int position;
        private boolean inWord;
        private char quote;

        Splitter(String text) {
            this.text = text;
        }

        /** Returns the words, or nothing for an unbalanced quote. */
        Optional<List<Word>> split() {
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (quote != 0) {
                    insideQuote(c);
                } else {
                    outsideQuote(c);
                }
            }
            if (quote != 0) {
                return Optional.empty();
            }
            endWord();
            return Optional.of(words);
        }

        private void insideQuote(char c) {
            source.append(c);
            if (c == quote) {
                quote = 0;
            } else if (c == '\\' && quote == '"') {
                escape(c);
            } else {
                value.append(c);
            }
        }

        private void outsideQuote(char c) {
            if (Character.isWhitespace(c)) {
                endWord();
                return;
            }
            inWord = true;
            source.append(c);
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '\\') {
                escape(c);
            } else {
                value.append(c);
            }
        }

        /** A backslash makes the next character literal. At the very end it stands for itself. */
        private void escape(char backslash) {
            if (position < text.length()) {
                char next = text.charAt(position++);
                source.append(next);
                value.append(next);
            } else {
                value.append(backslash);
            }
        }

        private void endWord() {
            if (inWord) {
                words.add(new Word(source.toString(), value.toString()));
                source.setLength(0);
                value.setLength(0);
                inWord = false;
            }
        }
    }
}
