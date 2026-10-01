package dev.forge.compiler;

import dev.forge.build.WorkspaceFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Remaps published library symbols to final IDs while retaining each library's styleable indices. */
final class LibraryRGenerator {
    private static final Pattern LINE = Pattern.compile("(int|int\\[\\]) ([a-z][a-z0-9_]*) ([A-Za-z_$][A-Za-z0-9_$]*) (.+)");
    private static final class Symbol {
        final String kind, type, name;
        final List<Integer> values;
        Symbol(String kind, String type, String name, List<Integer> values) { this.kind = kind; this.type = type; this.name = name; this.values = values; }
        String key() { return type + ":" + name; }
    }
    static String generate(String pkg, File published, File linked) throws IOException {
        Map<String, Symbol> library = read(published), finalSymbols = read(linked);
        Map<String, List<String>> classes = new TreeMap<>();
        for (Symbol symbol : library.values()) {
            Symbol actual = finalSymbols.get(symbol.key());
            if (actual == null || !actual.kind.equals(symbol.kind)) throw new IOException("Linked resources lack library symbol " + symbol.key());
            List<Integer> values = actual.values;
            if (symbol.type.equals("styleable")) {
                if (symbol.kind.equals("int")) values = symbol.values;
                else {
                    Integer[] indexed = new Integer[symbol.values.size()];
                    for (Symbol field : library.values()) {
                        if (!field.kind.equals("int") || !field.type.equals("styleable") || !owner(field.name, library).equals(symbol.name)) continue;
                        int index = field.values.get(0);
                        Symbol finalField = finalSymbols.get(field.key());
                        if (index < 0 || index >= indexed.length || indexed[index] != null || finalField == null || !finalField.kind.equals("int"))
                            throw new IOException("Invalid library styleable index: " + field.name);
                        int finalIndex = finalField.values.get(0);
                        if (finalIndex < 0 || finalIndex >= actual.values.size()) throw new IOException("Invalid linked styleable index: " + field.name);
                        indexed[index] = actual.values.get(finalIndex);
                    }
                    if (Arrays.asList(indexed).contains(null)) throw new IOException("Incomplete library styleable mapping: " + symbol.name);
                    values = Arrays.asList(indexed);
                }
            }
            String declaration = "    public static final " + symbol.kind + " " + symbol.name + " = ";
            List<String> constants = new ArrayList<>(); for (int value : values) constants.add(String.format(Locale.ROOT, "0x%08x", value));
            declaration += symbol.kind.equals("int") ? constants.get(0) : "{ " + String.join(", ", constants) + " }";
            classes.computeIfAbsent(symbol.type, key -> new ArrayList<>()).add(declaration + ";\n");
        }
        StringBuilder java = new StringBuilder("package ").append(pkg).append(";\npublic final class R {\n");
        for (Map.Entry<String, List<String>> entry : classes.entrySet()) {
            java.append("  public static final class ").append(entry.getKey()).append(" {\n");
            for (String declaration : entry.getValue()) java.append(declaration);
            java.append("  }\n");
        }
        return java.append("}\n").toString();
    }
    static void write(List<AarLibrary> libraries, File linked, File generated, String appNamespace) throws IOException {
        Map<String, String> sources = new HashMap<>();
        for (AarLibrary library : libraries) {
            File symbols = new File(library.directory, "R.txt");
            if (!symbols.isFile()) continue;
            String source = generate(library.packageName, symbols, linked);
            if (library.packageName.equals(appNamespace)) {
                throw new IOException("AAR shares the app resource namespace; choose a distinct app namespace: " + appNamespace);
            }
            String previous = sources.putIfAbsent(library.packageName, source);
            if (previous != null && !previous.equals(source)) throw new IOException("AARs publish incompatible R symbols for package " + library.packageName);
        }
        for (Map.Entry<String, String> entry : sources.entrySet()) WorkspaceFiles.atomicWrite(
            WorkspaceFiles.resolve(generated, entry.getKey().replace('.', '/') + "/R.java"), entry.getValue().getBytes(StandardCharsets.UTF_8));
    }
    private static String owner(String field, Map<String, Symbol> symbols) {
        String owner = "";
        for (Symbol candidate : symbols.values()) if (candidate.type.equals("styleable") && candidate.kind.equals("int[]") &&
            field.startsWith(candidate.name + "_") && candidate.name.length() > owner.length()) owner = candidate.name;
        return owner;
    }
    private static Map<String, Symbol> read(File file) throws IOException {
        Map<String, Symbol> result = new LinkedHashMap<>();
        for (String line : WorkspaceFiles.readUtf8(file, 8 * 1024 * 1024).split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            Matcher match = LINE.matcher(line.trim()); if (!match.matches()) throw new IOException("Invalid resource symbol: " + line);
            String value = match.group(4).trim(); List<Integer> values = new ArrayList<>();
            if (match.group(1).equals("int[]")) {
                if (!match.group(2).equals("styleable") || !value.startsWith("{") || !value.endsWith("}")) throw new IOException("Invalid resource symbol array");
                value = value.substring(1, value.length() - 1).trim();
                if (!value.isEmpty()) for (String item : value.split(",", -1)) values.add(number(item.trim()));
            } else values.add(number(value));
            Symbol symbol = new Symbol(match.group(1), match.group(2), match.group(3), values);
            if (result.put(symbol.key(), symbol) != null) throw new IOException("Duplicate resource symbol: " + symbol.key());
        }
        return result;
    }
    private static int number(String text) throws IOException {
        try {
            if (!text.matches("(?:0x[0-9a-fA-F]{1,8}|[0-9]{1,10})")) throw new NumberFormatException();
            long number = text.startsWith("0x") ? Long.parseLong(text.substring(2), 16) : Long.parseLong(text);
            if (number > 0xffffffffL) throw new NumberFormatException();
            return (int) number;
        } catch (NumberFormatException error) { throw new IOException("Invalid resource symbol value: " + text); }
    }
}
