package dev.forge.compiler;

import dev.forge.build.WorkspaceFiles;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** An isolated, bounded AAR extraction. Never executes bundled scripts or follows archive paths. */
final class AarLibrary {
    final File directory, manifest;
    final String packageName;
    final List<File> jars;
    private AarLibrary(File directory, File manifest, String packageName, List<File> jars) {
        this.directory = directory; this.manifest = manifest; this.packageName = packageName; this.jars = jars;
    }
    static AarLibrary extract(File archive, File directory, int compileSdk, Cancellation token) throws Exception {
        if (!directory.mkdirs()) throw new IOException("AAR output must be a new directory: " + directory);
        long total = 0; int count = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                token.check(); ZipEntry entry = entries.nextElement(); String name = entry.getName();
                if (++count > 20000) throw new IOException("AAR entry limit exceeded");
                if (name.isEmpty() || Arrays.asList(name.split("/", -1)).contains("..") || Arrays.asList(name.split("/", -1)).contains("."))
                    throw new IOException("Unsafe AAR entry: " + name);
                File target = WorkspaceFiles.resolve(directory, name);
                if (!names.add(target.getCanonicalPath().toLowerCase(Locale.ROOT))) throw new IOException("Duplicate AAR entry: " + name);
                if (entry.isDirectory()) { Files.createDirectories(target.toPath()); continue; }
                if (entry.getSize() > 128L * 1024 * 1024) throw new IOException("AAR entry exceeds size limit: " + name);
                Files.createDirectories(target.toPath().getParent());
                try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW)) {
                    byte[] bytes = new byte[32768]; int n; long size = 0;
                    while ((n = input.read(bytes)) != -1) {
                        token.check(); size += n; total += n;
                        if (size > 128L * 1024 * 1024 || total > 256L * 1024 * 1024) throw new IOException("AAR expanded size limit exceeded");
                        output.write(bytes, 0, n);
                    }
                }
            }
        }
        if (new File(directory, "prefab").exists()) throw new IOException("AAR Prefab needs native build integration");
        File metadata = new File(directory, "META-INF/com/android/build/gradle/aar-metadata.properties");
        if (metadata.isFile()) {
            Properties properties = new Properties();
            try (Reader reader = new StringReader(WorkspaceFiles.readUtf8(metadata, 65536))) { properties.load(reader); }
            int min = Integer.parseInt(properties.getProperty("minCompileSdk", "1"));
            if (min > compileSdk) throw new IOException("AAR requires compileSdk " + min + ", selected " + compileSdk);
            if (!properties.getProperty("minCompileSdkExtension", "0").equals("0")) throw new IOException("AAR requires SDK extension support");
            if (!properties.getProperty("coreLibraryDesugaringEnabled", "false").equals("false")) throw new IOException("AAR requires core library desugaring");
        }
        File manifest = new File(directory, "AndroidManifest.xml");
        String xml = WorkspaceFiles.readUtf8(manifest, 1024 * 1024);
        if (xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || xml.toUpperCase(Locale.ROOT).contains("<!ENTITY")) throw new IOException("AAR manifest entities are forbidden");
        DocumentBuilderFactory factory = ManifestXmlFactories.dom();
        factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        String pkg = root.getAttribute("package");
        if (!root.getTagName().equals("manifest") || !pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) throw new IOException("AAR manifest needs a valid package");
        List<File> jars = new ArrayList<>();
        File classes = new File(directory, "classes.jar"); if (classes.isFile()) jars.add(classes);
        for (File jar : WorkspaceFiles.collect(directory, "libs")) {
            if (!jar.getName().endsWith(".jar")) throw new IOException("Unexpected AAR libs entry: " + jar.getName());
            jars.add(jar);
        }
        return new AarLibrary(directory, manifest, pkg, jars);
    }
}
