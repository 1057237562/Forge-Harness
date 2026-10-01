package dev.forge.compiler;

import com.android.manifmerger.*;
import com.android.utils.ILogger;
import dev.forge.build.WorkspaceFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official Android manifest semantics; manifests must be validated before the merger opens them. */
public final class LibraryManifestMerger {
    private LibraryManifestMerger() { }
    public static synchronized void merge(File main, List<File> librariesHighToLow, File destination,
                             String applicationId, int minSdk, int targetSdk, BuildListener listener) throws Exception {
        validate(main);
        for (File library : librariesHighToLow) validate(library);
        ILogger logger = new ILogger() {
            private void line(String format, Object... args) { listener.log(String.format(Locale.ROOT, format, args)); }
            public void error(Throwable error, String format, Object... args) { line(format == null ? String.valueOf(error) : format, args); }
            public void warning(String format, Object... args) { line(format, args); }
            public void info(String format, Object... args) { line(format, args); }
            public void verbose(String format, Object... args) { }
        };
        ManifestMerger2.Invoker merger = ManifestMerger2.newMerger(main, logger, ManifestMerger2.MergeType.APPLICATION)
            .setOverride(ManifestSystemProperty.PACKAGE, applicationId)
            .setOverride(ManifestSystemProperty.MIN_SDK_VERSION, String.valueOf(minSdk))
            .setOverride(ManifestSystemProperty.TARGET_SDK_VERSION, String.valueOf(targetSdk))
            .withFeatures(ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS);
        for (File library : librariesHighToLow) merger.addLibraryManifest(library);
        MergingReport report = merger.merge();
        report.log(logger);
        if (report.getResult().isError()) throw new IOException("Manifest merge failed: " + report.getReportString());
        String merged = report.getMergedDocument(MergingReport.MergedManifestKind.MERGED);
        if (merged == null) throw new IOException("Manifest merger returned no document");
        WorkspaceFiles.atomicWrite(destination, merged.getBytes(StandardCharsets.UTF_8));
    }
    private static void validate(File file) throws IOException {
        String xml = WorkspaceFiles.readUtf8(file, 1024 * 1024).toUpperCase(Locale.ROOT);
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("Manifest DTD/entity declarations are not supported: " + file);
    }
}
