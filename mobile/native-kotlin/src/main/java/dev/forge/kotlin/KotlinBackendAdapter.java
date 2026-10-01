package dev.forge.kotlin;
import dev.forge.compiler.*;
import java.io.File;
import java.util.List;

public final class KotlinBackendAdapter implements KotlinBackend {
    public String identity() { return "kotlin-1.9.24-art-v1"; }
    public void compile(File androidJar, File stdlib, List<File> dependencies, List<File> kotlinSources,
        List<File> javaSources, File classes, File environment, Cancellation cancellation, BuildListener listener) throws Exception {
        NativeKotlinCompiler.compile(androidJar, stdlib, dependencies, kotlinSources, javaSources, classes, environment, cancellation, listener);
    }
}
