package dev.forge.build;

import java.util.*;

/** A tokenizer and block parser for a documented declarative subset. It never evaluates Groovy. */
final class GradleSyntax {
    static final class Token {
        final String text;
        final int line;
        final boolean quoted;
        Token(String text, int line, boolean quoted) { this.text = text; this.line = line; this.quoted = quoted; }
    }
    static final class Statement {
        final List<Token> words;
        final List<Statement> children;
        final int line;
        Statement(List<Token> words, List<Statement> children) {
            this.words = words; this.children = children; this.line = words.get(0).line;
        }
        String name() { return words.get(0).text; }
        List<Token> values() {
            List<Token> result = new ArrayList<>();
            for (int i = 1; i < words.size(); i++) {
                Token token = words.get(i);
                if (token.quoted || !Arrays.asList("=", "(", ")", ":", ",").contains(token.text)) result.add(token);
            }
            return result;
        }
    }
    private final String file;
    private final List<Token> tokens;
    private int at;
    private GradleSyntax(String file, String source) throws CompatibilityException {
        this.file = file; this.tokens = lex(file, source);
    }
    static List<Statement> parse(String file, String source) throws CompatibilityException {
        return new GradleSyntax(file, source).block(false);
    }
    private List<Statement> block(boolean nested) throws CompatibilityException {
        List<Statement> result = new ArrayList<>();
        while (at < tokens.size()) {
            Token token = tokens.get(at);
            if (symbol(token, "\n") || symbol(token, ";")) { at++; continue; }
            if (symbol(token, "}")) {
                if (!nested) throw fail(token, "Unexpected '}'");
                at++; return result;
            }
            List<Token> words = new ArrayList<>();
            int parentheses = 0;
            while (at < tokens.size()) {
                token = tokens.get(at);
                if (parentheses == 0 && (symbol(token, "{") || symbol(token, "}") || symbol(token, "\n") || symbol(token, ";"))) break;
                if (symbol(token, "(")) parentheses++;
                if (symbol(token, ")") && --parentheses < 0) throw fail(token, "Unbalanced parentheses");
                if (symbol(token, "{") || symbol(token, "}")) throw fail(token, "Expressions/closures inside arguments are unsupported");
                if (!symbol(token, "\n")) words.add(token);
                at++;
            }
            if (parentheses != 0) throw fail(token, "Unbalanced parentheses");
            if (words.isEmpty()) throw fail(token, "Expected a declaration");
            if (words.get(0).quoted) throw fail(words.get(0), "Expected a declaration name, not a string expression");
            List<Statement> children = null;
            if (at < tokens.size() && symbol(tokens.get(at), "{")) { at++; children = block(true); }
            result.add(new Statement(words, children));
        }
        if (nested) throw new CompatibilityException(file, tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).line, "Missing '}'");
        return result;
    }
    private CompatibilityException fail(Token token, String reason) { return new CompatibilityException(file, token.line, reason); }
    private static boolean symbol(Token token, String text) { return !token.quoted && token.text.equals(text); }
    private static List<Token> lex(String file, String source) throws CompatibilityException {
        List<Token> tokens = new ArrayList<>();
        int line = 1;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (c == '\n') { tokens.add(new Token("\n", line++, false)); i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) throw new CompatibilityException(file, line, "Unterminated comment");
                for (; i < end + 2; i++) if (source.charAt(i) == '\n') line++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int begin = line; char quote = c; i++;
                StringBuilder value = new StringBuilder(); boolean closed = false;
                while (i < source.length()) {
                    char part = source.charAt(i++);
                    if (part == quote) { closed = true; break; }
                    if (part == '\n' || part == '\r') throw new CompatibilityException(file, line, "Multiline strings are unsupported");
                    if (part == '$' && quote == '"') throw new CompatibilityException(file, line, "Interpolated values require explicit .forge/project.json");
                    if (part == '\\') {
                        if (i == source.length()) break;
                        part = source.charAt(i++);
                        if (part != quote && part != '\\') throw new CompatibilityException(file, line, "Unsupported string escape");
                    }
                    value.append(part);
                }
                if (!closed) throw new CompatibilityException(file, begin, "Unterminated string");
                tokens.add(new Token(value.toString(), begin, true)); continue;
            }
            if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) {
                int begin = i++;
                while (i < source.length() && (Character.isJavaIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) i++;
                tokens.add(new Token(source.substring(begin, i), line, false)); continue;
            }
            if ("{}()=:,;[]".indexOf(c) >= 0) { tokens.add(new Token(String.valueOf(c), line, false)); i++; continue; }
            throw new CompatibilityException(file, line, "Unsupported expression token '" + c + "'; native builds do not execute Gradle scripts");
        }
        return tokens;
    }
}
