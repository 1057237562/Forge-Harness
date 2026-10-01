package dev.forge.kotlin;

import dev.forge.build.WorkspaceFiles;
import dev.forge.compiler.*;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.*;
import org.jetbrains.kotlin.config.Services;
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** ART-compatible Kotlin JVM compilation with Java sources used for symbol resolution. */
public final class NativeKotlinCompiler {
    public static synchronized void compile(File androidJar, File stdlib, List<File> dependencies,
        List<File> kotlinSources, List<File> javaSources, File classes, File environment,
        Cancellation cancellation, BuildListener listener) throws Exception {
        cancellation.check();
        if (kotlinSources.isEmpty()) throw new IOException("No Kotlin sources");
        Files.createDirectories(classes.toPath()); Files.createDirectories(environment.toPath());
        if (classes.list() == null || classes.list().length != 0) throw new IOException("Kotlin output directory must be empty");
        File extension = new File(environment, "META-INF/extensions/compiler.xml");
        try (InputStream input = NativeKotlinCompiler.class.getClassLoader().getResourceAsStream("META-INF/extensions/compiler.xml")) {
            if (input == null) throw new IOException("Kotlin compiler extension metadata is missing");
            ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int n;
            while ((n = input.read(bytes)) != -1) { if (data.size() + n > 1024 * 1024) throw new IOException("Kotlin extension metadata too large"); data.write(bytes, 0, n); }
            WorkspaceFiles.atomicWrite(extension, data.toByteArray());
        }
        List<String> classpath = new ArrayList<>(); classpath.add(androidJar.getAbsolutePath()); classpath.add(stdlib.getAbsolutePath());
        for (File dependency : dependencies) classpath.add(dependency.getAbsolutePath());
        List<String> args = new ArrayList<>(Arrays.asList("-kotlin-home", environment.getAbsolutePath(), "-Xintellij-plugin-root=" + environment.getAbsolutePath(),
            "-no-jdk", "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", String.join(File.pathSeparator, classpath), "-d", classes.getAbsolutePath()));
        for (File file : kotlinSources) args.add(file.getAbsolutePath());
        for (File file : javaSources) args.add(file.getAbsolutePath());
        MessageCollector collector = new MessageCollector() {
            private boolean errors;
            public void clear() { errors = false; }
            public boolean hasErrors() { return errors; }
            public void report(CompilerMessageSeverity severity, String message, CompilerMessageSourceLocation location) {
                cancellationCheck();
                if (severity.isError()) errors = true;
                if (severity.isError() || severity.isWarning()) listener.diagnostic(severity.isError() ? "error" : "warning",
                    location == null ? null : location.getPath(), location == null ? 0 : location.getLine(), location == null ? 0 : location.getColumn(), message);
                else listener.log(message);
            }
            private void cancellationCheck() { if (cancellation.isCancelled()) throw new ProcessCanceledException(); }
        };
        K2JVMCompiler compiler = new K2JVMCompiler();
        compiler.setReadingSettingsFromEnvironmentAllowed(false);
        K2JVMCompilerArguments arguments = compiler.createArguments(); compiler.parseArguments(args.toArray(new String[0]), arguments);
        ProgressIndicatorAndCompilationCanceledStatus.setCompilationCanceledStatus(() -> { if (cancellation.isCancelled()) throw new ProcessCanceledException(); });
        try {
            ExitCode result = compiler.exec(collector, Services.EMPTY, arguments);
            cancellation.check();
            if (result != ExitCode.OK || collector.hasErrors()) throw new IOException("Kotlin compilation failed: " + result);
        } finally { ProgressIndicatorAndCompilationCanceledStatus.setCompilationCanceledStatus(null); }
    }
}
