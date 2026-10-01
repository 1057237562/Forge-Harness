package dev.forge.compiler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

final class ToolProcess {
    private static final Pattern DIAGNOSTIC = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?: (error|warning): (.*)$");
    static void run(File tool, File cwd, Map<String, String> environment, Cancellation cancellation,
                    BuildListener listener, String... args) throws Exception {
        cancellation.check();
        if (!tool.isFile() || !tool.canExecute()) throw new IOException("Native tool is not executable: " + tool);
        List<String> argv = new ArrayList<>(); argv.add(tool.getAbsolutePath()); Collections.addAll(argv, args);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<?> output = reader.submit(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    listener.log(line);
                    Matcher matcher = DIAGNOSTIC.matcher(line);
                    if (matcher.matches()) listener.diagnostic(matcher.group(4), matcher.group(1),
                        Integer.parseInt(matcher.group(2)), matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)), matcher.group(5));
                }
            } catch (IOException error) { throw new UncheckedIOException(error); }
        });
        try (AutoCloseable hook = cancellation.onCancel(process::destroyForcibly)) {
            if (!process.waitFor(120, TimeUnit.SECONDS)) throw new IOException("Native tool timed out: " + tool.getName());
            cancellation.check();
            output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IOException(tool.getName() + " exited with code " + process.exitValue());
        } finally { process.destroyForcibly(); reader.shutdownNow(); }
    }
}
