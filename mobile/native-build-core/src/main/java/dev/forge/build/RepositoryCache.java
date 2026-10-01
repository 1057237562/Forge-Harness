package dev.forge.build;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** HTTPS-only artifact cache. Sidecar hashes detect cache corruption; they are not publisher signatures. */
public final class RepositoryCache {
    public interface CancellationCheck { void check() throws IOException; }
    public interface Progress { void message(String value); }
    public interface Transport {
        /** Return false only for a definitive 404. Other errors must not masquerade as absence. */
        boolean download(URI uri, File output, long limit, CancellationCheck cancellation) throws IOException;
    }
    public static final class Entry {
        public final File file;
        public final String repository, sha256;
        Entry(File file, String repository, String sha256) { this.file = file; this.repository = repository; this.sha256 = sha256; }
    }
    private final File directory;
    private final Transport transport;
    private final boolean offline;
    private final CancellationCheck cancellation;
    private final Progress progress;
    public RepositoryCache(File directory, Transport transport, boolean offline, CancellationCheck cancellation, Progress progress) {
        this.directory = directory; this.transport = transport; this.offline = offline; this.cancellation = cancellation; this.progress = progress;
    }
    public Entry get(String relative, List<String> repositories, long limit) throws IOException {
        if (!relative.matches("[A-Za-z0-9_./-]+") || relative.startsWith("/") || Arrays.asList(relative.split("/")).contains(".."))
            throw new IOException("Unsafe repository path");
        List<String> misses = new ArrayList<>();
        // Search every configured cache before opening any network connection (also works after repository reordering).
        for (String base : repositories) {
            cancellation.check();
            String repository = normalized(base);
            File file = path(repository, relative);
            File hash = path(repository, relative + ".sha256");
            if (file.isFile() && file.length() <= limit && hash.isFile()) {
                String expected = WorkspaceFiles.readUtf8(hash, 128).trim();
                if (expected.matches("[a-f0-9]{64}") && expected.equals(WorkspaceFiles.sha256(file)))
                    return new Entry(file, repository, expected);
                progress.message("Ignoring corrupt cached artifact: " + relative);
            }
        }
        if (offline) throw new IOException("OFFLINE_CACHE_MISS: " + relative);
        for (String base : repositories) {
            cancellation.check();
            String repository = normalized(base);
            File target = path(repository, relative);
            Files.createDirectories(target.toPath().getParent());
            Path temporary = Files.createTempFile(target.toPath().getParent(), "download-", ".part");
            try {
                progress.message("Downloading " + relative + " from " + repository);
                if (!transport.download(URI.create(repository + relative), temporary.toFile(), limit, cancellation)) {
                    misses.add(repository); continue;
                }
                cancellation.check();
                if (Files.size(temporary) == 0 || Files.size(temporary) > limit) throw new IOException("Invalid artifact size: " + relative);
                String digest = WorkspaceFiles.sha256(temporary.toFile());
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                WorkspaceFiles.atomicWrite(path(repository, relative + ".sha256"), digest.getBytes(StandardCharsets.US_ASCII));
                return new Entry(target, repository, digest);
            } finally { Files.deleteIfExists(temporary); }
        }
        throw new FileNotFoundException("Artifact not found: " + relative + " in " + misses);
    }
    private File path(String repository, String relative) throws IOException {
        try {
            String id = WorkspaceFiles.hex(MessageDigest.getInstance("SHA-256").digest(repository.getBytes(StandardCharsets.UTF_8)));
            return WorkspaceFiles.resolve(directory, id + "/" + relative);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String normalized(String base) throws IOException {
        try {
            URI uri = URI.create(base);
            if (!uri.getScheme().equals("https") || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return base.endsWith("/") ? base : base + "/";
        } catch (RuntimeException error) { throw new IOException("Repository must be an HTTPS base URL without credentials/query", error); }
    }
    public static final class HttpsTransport implements Transport {
        @Override public boolean download(URI uri, File output, long limit, CancellationCheck cancellation) throws IOException {
            for (int redirects = 0; redirects < 6; redirects++) {
                normalized(uri.resolve(".").toString());
                if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null) throw new IOException("Insecure repository redirect");
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000); connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "Forge-Native-Builder/1");
                try {
                    cancellation.check();
                    int code = connection.getResponseCode();
                    if (code == 404) return false;
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("Repository redirect without Location");
                        uri = uri.resolve(location); continue;
                    }
                    if (code != 200) throw new IOException("Repository HTTP " + code + " from " + uri.getHost());
                    if (connection.getContentLengthLong() > limit) throw new IOException("Artifact exceeds download limit");
                    try (InputStream input = connection.getInputStream(); OutputStream target = new FileOutputStream(output)) {
                        byte[] buffer = new byte[32768]; int count; long total = 0;
                        while ((count = input.read(buffer)) != -1) {
                            cancellation.check(); total += count;
                            if (total > limit) throw new IOException("Artifact exceeds download limit");
                            target.write(buffer, 0, count);
                        }
                    }
                    return true;
                } finally { connection.disconnect(); }
            }
            throw new IOException("Too many repository redirects");
        }
    }
}
