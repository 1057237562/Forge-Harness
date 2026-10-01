package dev.forge.build;

import java.io.IOException;
import java.util.Objects;

public final class MavenCoordinate {
    public final String group, artifact, version, classifier;
    public MavenCoordinate(String group, String artifact, String version, String classifier) throws IOException {
        if (group == null || !group.matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*") ||
            !segment(artifact) || !segment(version) || version.endsWith("-SNAPSHOT") ||
            classifier == null || (!classifier.isEmpty() && !segment(classifier)))
            throw new IOException("Expected fixed Maven coordinate, without ranges/dynamic versions: " + group + ":" + artifact + ":" + version);
        this.group = group; this.artifact = artifact; this.version = version; this.classifier = classifier;
    }
    public static MavenCoordinate parse(String text) throws IOException {
        String[] parts = text.split(":", -1);
        if (parts.length != 3) throw new IOException("Expected g:a:v coordinate: " + text);
        return new MavenCoordinate(parts[0], parts[1], parts[2], "");
    }
    private static boolean segment(String text) {
        return text != null && text.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") && !text.equals(".") && !text.equals("..");
    }
    public String ga() { return group + ":" + artifact; }
    public String key() { return ga() + ":" + classifier; }
    public String path(String extension) {
        if (!extension.matches("pom|module|jar|aar")) throw new IllegalArgumentException("Unsupported artifact extension");
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version +
            (classifier.isEmpty() || extension.equals("pom") || extension.equals("module") ? "" : "-" + classifier) + "." + extension;
    }
    public MavenCoordinate withVersion(String value) throws IOException { return new MavenCoordinate(group, artifact, value, classifier); }
    @Override public String toString() { return ga() + ":" + version + (classifier.isEmpty() ? "" : ":" + classifier); }
    @Override public boolean equals(Object other) { return other instanceof MavenCoordinate && toString().equals(other.toString()); }
    @Override public int hashCode() { return Objects.hash(group, artifact, version, classifier); }
}
