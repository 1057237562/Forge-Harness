package dev.forge.kotlin;

import java.io.*;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Returns injection candidates; Kotlin's original annotation filtering remains in place. */
public final class AndroidInjectionMethods {
    private static final Map<String, List<String[]>> INDEX = readIndex();
    private static Map<String, List<String[]>> readIndex() {
        Map<String, List<String[]>> result = new HashMap<>();
        InputStream stream = AndroidInjectionMethods.class.getClassLoader().getResourceAsStream("META-INF/forge-kotlin-injection-index.txt");
        if (stream == null) throw new IllegalStateException("Kotlin injection index is missing");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields[0].equals("C")) result.put(fields[1], new ArrayList<>());
                else if (fields[0].equals("M")) result.get(fields[1]).add(new String[]{fields[2], fields[3]});
                else throw new IOException("Invalid Kotlin injection index");
            }
            return result;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    public static Method[] getMethods(Class<?> type) {
        LinkedHashSet<Method> methods = new LinkedHashSet<>();
        visit(type, type, methods, new HashSet<>());
        return methods.toArray(new Method[0]);
    }
    private static void visit(Class<?> root, Class<?> type, Set<Method> methods, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return;
        List<String[]> candidates = INDEX.get(type.getName());
        if (candidates == null) {
            // Unknown/plugin classes retain normal reflection and annotation behavior.
            for (Method method : type.getMethods()) {
                try { methods.add(root.getMethod(method.getName(), method.getParameterTypes())); }
                catch (NoSuchMethodException error) { throw new IllegalStateException(error); }
            }
        } else {
            for (String[] candidate : candidates) {
                try {
                    Class<?>[] parameters = MethodType.fromMethodDescriptorString(candidate[1], root.getClassLoader()).parameterArray();
                    methods.add(root.getMethod(candidate[0], parameters));
                } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot resolve injection method " + type.getName() + "." + candidate[0], error); }
            }
        }
        visit(root, type.getSuperclass(), methods, visited);
        for (Class<?> parent : type.getInterfaces()) visit(root, parent, methods, visited);
    }
}
