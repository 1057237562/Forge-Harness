package dev.forge.compiler;
import java.io.File;
import java.util.List;

/** Optional Kotlin capability supplied by the host without a cyclic compiler dependency. */
public interface KotlinBackend {
    String identity();
    void compile(File androidJar, File stdlib, List<File> dependencies, List<File> kotlinSources,
        List<File> javaSources, File classes, File environment, Cancellation cancellation, BuildListener listener) throws Exception;
}
