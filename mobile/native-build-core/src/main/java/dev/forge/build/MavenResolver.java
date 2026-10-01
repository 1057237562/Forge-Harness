package dev.forge.build;

import java.io.*;
import java.util.*;
import org.w3c.dom.Element;
import static dev.forge.build.PomXml.*;

/** A bounded consumer-POM resolver. Does not execute Maven, Gradle, build plugins or POM profiles. */
public final class MavenResolver {
    public static final class Artifact {
        public final String id, type, sha256, repository;
        public final File file;
        public final boolean compile, runtime;
        Artifact(String id, String type, File file, String sha, String repository, boolean compile, boolean runtime) {
            this.id = id; this.type = type; this.file = file; this.sha256 = sha; this.repository = repository;
            this.compile = compile; this.runtime = runtime;
        }
    }
    public static final class Resolution {
        /** Dependency-before-consumer order. Earlier direct declarations have higher overlay priority. */
        public final List<Artifact> artifacts;
        public final Map<String, String> metadata;
        public final List<String> notices;
        public final String fingerprint;
        Resolution(List<Artifact> artifacts, Map<String, String> metadata, List<String> notices) {
            this.artifacts = Collections.unmodifiableList(artifacts);
            this.metadata = Collections.unmodifiableMap(new TreeMap<>(metadata));
            this.notices = Collections.unmodifiableList(new ArrayList<>(notices));
            Map<String, String> hashes = new TreeMap<>(metadata);
            for (Artifact artifact : artifacts) hashes.put("artifact:" + artifact.id,
                artifact.sha256 + ":" + artifact.type + ":" + artifact.compile + ":" + artifact.runtime + ":" + artifact.repository);
            this.fingerprint = InputSnapshot.digest(hashes);
        }
    }
    private final RepositoryCache cache;
    private final List<String> repositories;
    private final RepositoryCache.CancellationCheck cancellation;
    private final Map<String, Pom> poms = new HashMap<>();
    private final Map<String, GradleModuleMetadata> modules = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final Map<String, String> metadata = new TreeMap<>();
    private final List<String> notices = new ArrayList<>();
    public MavenResolver(RepositoryCache cache, List<String> repositories, RepositoryCache.CancellationCheck cancellation) {
        this.cache = cache; this.repositories = new ArrayList<>(repositories); this.cancellation = cancellation;
    }
    public synchronized Resolution resolve(ProjectModel project) throws IOException {
        poms.clear(); modules.clear(); loading.clear(); metadata.clear(); notices.clear();
        List<GradleModuleMetadata.Constraint> constraints = new ArrayList<>();
        Map<String, String> pins = new HashMap<>();
        LinkedHashMap<String, MutableArtifact> artifacts = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> graph = new LinkedHashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (ProjectModel.Dependency dependency : project.dependencies) {
            boolean compile = dependency.scope != ProjectModel.Scope.RUNTIME_ONLY;
            boolean runtime = dependency.scope != ProjectModel.Scope.COMPILE_ONLY;
            if (dependency.local) {
                File file = WorkspaceFiles.resolve(project.root, dependency.value);
                String id = "local:" + dependency.value;
                MutableArtifact artifact = artifacts.get(id);
                if (artifact == null) artifacts.put(id, artifact = new MutableArtifact(id,
                    dependency.value.endsWith(".aar") ? "aar" : "jar", file, WorkspaceFiles.sha256(file), "workspace"));
                artifact.compile |= compile; artifact.runtime |= runtime;
                graph.putIfAbsent(id, new LinkedHashSet<>());
            } else {
                MavenCoordinate coordinate = MavenCoordinate.parse(dependency.value);
                String previous = pins.put(coordinate.ga(), coordinate.version);
                if (previous != null && !previous.equals(coordinate.version)) throw new IOException("Conflicting explicit versions for " + coordinate.ga());
                queue.add(new Node(coordinate, null, compile, runtime, dependency.excludes, new ArrayList<>(), null));
            }
        }
        Map<String, String> selectedVersions = new HashMap<>();
        Set<String> visits = new HashSet<>();
        int processed = 0;
        while (!queue.isEmpty()) {
            cancellation.check();
            Node node = queue.remove();
            if (++processed > 4096 || node.ancestors.size() > 32) throw new IOException("Dependency graph exceeds native resolver limits");
            String pin = pins.get(node.coordinate.ga());
            MavenCoordinate coordinate = pin == null ? node.coordinate : node.coordinate.withVersion(pin);
            if (pin != null && !pin.equals(node.coordinate.version)) {
                String notice = "Explicit dependency pin: " + node.coordinate + " -> " + coordinate;
                if (!notices.contains(notice)) notices.add(notice);
            }
            String id = coordinate.toString();
            if (node.ancestors.contains(id)) throw new IOException("Cyclic dependency: " + node.ancestors + " -> " + id);
            String selected = selectedVersions.putIfAbsent(coordinate.key(), coordinate.version);
            if (selected != null && !selected.equals(coordinate.version)) throw new IOException("DEPENDENCY_VERSION_CONFLICT: " + coordinate.ga() +
                " requested " + selected + " and " + coordinate.version + ". Declare the intended g:a:v directly in the project to pin it.");
            graph.putIfAbsent(id, new LinkedHashSet<>());
            if (node.parent != null) graph.get(node.parent).add(id);
            String visit = id + "|" + node.compile + "|" + node.runtime + "|" + node.usageScoped + "|" + new TreeSet<>(node.excludes);
            if (!visits.add(visit)) continue;
            Pom pom = pom(coordinate);
            if (pom.advertisesModule) {
                if (!coordinate.classifier.isEmpty()) throw new IOException("Classified module artifacts need explicit variant selection: " + coordinate);
                GradleModuleMetadata module = module(coordinate, pom.repository);
                List<String> ancestors = new ArrayList<>(node.ancestors); ancestors.add(id);
                for (boolean runtime : new boolean[]{false, true}) {
                    if (runtime ? !node.runtime : !node.compile) continue;
                    GradleModuleMetadata.Variant variant = module.select(runtime);
                    String notice = "Selected " + coordinate + " / " + variant.name + " for " + (runtime ? "runtime" : "compile");
                    if (!notices.contains(notice)) notices.add(notice);
                    constraints.addAll(variant.constraints);
                    if (variant.redirect != null) {
                        queue.add(new Node(variant.redirect, null, !runtime, runtime, node.excludes, ancestors, id, true));
                        continue;
                    }
                    for (GradleModuleMetadata.FileEntry file : variant.files) {
                        String artifactId = id + "@" + file.name;
                        graph.putIfAbsent(artifactId, new LinkedHashSet<>()); graph.get(id).add(artifactId);
                        RepositoryCache.Entry entry = cache.get(file.relativePath, Collections.singletonList(pom.repository), 128L * 1024 * 1024);
                        if ((file.size >= 0 && file.size != entry.file.length()) || (!file.sha256.isEmpty() && !file.sha256.equals(entry.sha256)) ||
                            (!file.sha512.isEmpty() && !file.sha512.equals(hash(entry.file, "SHA-512"))))
                            throw new IOException("Published module artifact checksum/size mismatch: " + artifactId);
                        MutableArtifact artifact = artifacts.get(artifactId);
                        if (artifact == null) artifacts.put(artifactId, artifact = new MutableArtifact(artifactId, file.type(), entry.file, entry.sha256, entry.repository));
                        artifact.compile |= !runtime; artifact.runtime |= runtime;
                    }
                    for (GradleModuleMetadata.Dependency dependency : variant.dependencies) {
                        if (excluded(node.excludes, dependency.coordinate)) continue;
                        Set<String> excludes = new LinkedHashSet<>(node.excludes); excludes.addAll(dependency.excludes);
                        queue.add(new Node(dependency.coordinate, null, !runtime, runtime, excludes, ancestors, id, true));
                    }
                }
                continue;
            }
            String type = node.type == null ? pom.packaging : node.type;
            if (type.equals("bundle")) type = "jar";
            if (!Arrays.asList("jar", "aar", "pom").contains(type)) throw new IOException("Unsupported Maven packaging: " + type + " for " + coordinate);
            if (!type.equals("pom")) {
                MutableArtifact artifact = artifacts.get(id);
                if (artifact == null) {
                    RepositoryCache.Entry entry = cache.get(coordinate.path(type), Collections.singletonList(pom.repository), 128L * 1024 * 1024);
                    artifacts.put(id, artifact = new MutableArtifact(id, type, entry.file, entry.sha256, entry.repository));
                } else if (!artifact.type.equals(type)) throw new IOException("Conflicting artifact types for " + id);
                artifact.compile |= node.compile; artifact.runtime |= node.runtime;
            }
            List<String> ancestors = new ArrayList<>(node.ancestors); ancestors.add(id);
            for (Spec spec : pom.dependencies) {
                if (spec.optional || spec.scope.equals("test") || spec.scope.equals("provided")) continue;
                if (!spec.scope.equals("compile") && !spec.scope.equals("runtime")) throw new IOException("Unsupported dependency scope " + spec.scope + " in " + coordinate);
                if (excluded(node.excludes, spec.coordinate)) continue;
                boolean compile = node.compile && (spec.scope.equals("compile") || (!node.runtime && !node.usageScoped));
                // compileOnly retains its compile graph but never contributes anything to runtime.
                boolean runtime = node.runtime;
                if (!compile && !runtime) continue;
                Set<String> excludes = new LinkedHashSet<>(node.excludes); excludes.addAll(spec.excludes);
                queue.add(new Node(spec.coordinate, spec.explicitType ? spec.type : null, compile, runtime, excludes, ancestors, id, node.usageScoped));
            }
        }
        validateConstraints(constraints, selectedVersions);
        List<String> order = new ArrayList<>(); Set<String> ordered = new HashSet<>();
        List<String> roots = new ArrayList<>(graph.keySet()); Collections.reverse(roots);
        for (String root : roots) order(root, graph, ordered, order);
        List<Artifact> result = new ArrayList<>();
        for (String id : order) {
            MutableArtifact a = artifacts.get(id);
            if (a != null) result.add(new Artifact(a.id, a.type, a.file, a.sha, a.repository, a.compile, a.runtime));
        }
        return new Resolution(result, metadata, notices);
    }
    private GradleModuleMetadata module(MavenCoordinate coordinate, String repository) throws IOException {
        String id = coordinate.toString();
        if (!modules.containsKey(id)) {
            RepositoryCache.Entry entry = cache.get(coordinate.path("module"), Collections.singletonList(repository), 2L * 1024 * 1024);
            metadata.put("module:" + id, entry.sha256 + "@" + entry.repository);
            modules.put(id, new GradleModuleMetadata(entry.file, coordinate, entry.repository));
        }
        return modules.get(id);
    }
    private static String hash(File file, String algorithm) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
            try (InputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[32768]; int n;
                while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
            }
            return WorkspaceFiles.hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void validateConstraints(List<GradleModuleMetadata.Constraint> constraints, Map<String, String> versions) throws IOException {
        for (GradleModuleMetadata.Constraint constraint : constraints) for (Map.Entry<String, String> entry : versions.entrySet()) {
            if (!entry.getKey().startsWith(constraint.ga + ":")) continue;
            String selected = entry.getValue();
            if ((!constraint.strict.isEmpty() && !selected.equals(constraint.strict)) || constraint.rejected.contains(selected))
                throw new IOException("Module version constraint rejects " + constraint.ga + ":" + selected + "; strict=" + constraint.strict + ", rejected=" + constraint.rejected);
            if (!constraint.required.isEmpty() && !selected.equals(constraint.required)) {
                if (!selected.matches("[0-9]+(\\.[0-9]+)*") || !constraint.required.matches("[0-9]+(\\.[0-9]+)*"))
                    throw new IOException("Qualified version constraint needs an explicit exact pin: " + constraint.ga + " requires " + constraint.required + ", selected " + selected);
                String[] left = selected.split("\\."), right = constraint.required.split("\\.");
                int comparison = 0;
                for (int i = 0; i < Math.min(left.length, right.length) && comparison == 0; i++)
                    comparison = new java.math.BigInteger(left[i]).compareTo(new java.math.BigInteger(right[i]));
                if (comparison == 0) comparison = Integer.compare(left.length, right.length);
                if (comparison < 0) throw new IOException("Module constraint requires " + constraint.ga + ":" + constraint.required + " or newer; explicitly pin a compatible version");
            }
        }
    }
    private static void order(String id, Map<String, Set<String>> graph, Set<String> seen, List<String> result) {
        if (!seen.add(id)) return;
        List<String> children = new ArrayList<>(graph.getOrDefault(id, Collections.emptySet())); Collections.reverse(children);
        for (String child : children) order(child, graph, seen, result);
        result.add(id);
    }
    private static boolean excluded(Set<String> patterns, MavenCoordinate coordinate) {
        for (String rule : patterns) {
            String[] parts = rule.split(":", -1);
            if (parts.length == 2 && (parts[0].equals("*") || parts[0].equals(coordinate.group)) &&
                (parts[1].equals("*") || parts[1].equals(coordinate.artifact))) return true;
        }
        return false;
    }
    private Pom pom(MavenCoordinate coordinate) throws IOException {
        cancellation.check();
        String id = coordinate.ga() + ":" + coordinate.version;
        if (poms.containsKey(id)) return poms.get(id);
        if (loading.size() > 32 || poms.size() > 512) throw new IOException("POM inheritance exceeds native resolver limits");
        if (!loading.add(id)) throw new IOException("Cyclic POM parent/BOM: " + id);
        try {
            RepositoryCache.Entry entry = cache.get(coordinate.path("pom"), repositories, 2L * 1024 * 1024);
            metadata.put("pom:" + id, entry.sha256 + "@" + entry.repository);
            boolean advertisesModule = WorkspaceFiles.readUtf8(entry.file, 2 * 1024 * 1024).contains("published-with-gradle-metadata");
            Element xml = PomXml.read(entry.file);
            if (child(child(xml, "distributionManagement"), "relocation") != null)
                throw new IOException("Relocated POM requires the new coordinate explicitly: " + id);
            for (Element profile : children(child(xml, "profiles"), "profile"))
                if (child(profile, "dependencies") != null || child(profile, "dependencyManagement") != null)
                    throw new IOException("Dependency-bearing POM profiles require explicit resolution: " + id);
            Map<String, String> own = new LinkedHashMap<>();
            for (Element property : children(child(xml, "properties"), null)) own.put(name(property), property.getTextContent().trim());
            Map<String, String> properties = new LinkedHashMap<>(own);
            identity(properties, coordinate);
            Pom parent = null;
            Element parentXml = child(xml, "parent");
            if (parentXml != null) {
                MavenCoordinate parentCoordinate = new MavenCoordinate(expand(text(parentXml, "groupId", ""), properties),
                    expand(text(parentXml, "artifactId", ""), properties), expand(text(parentXml, "version", ""), properties), "");
                parent = pom(parentCoordinate);
                properties = new LinkedHashMap<>(parent.properties); properties.putAll(own); identity(properties, coordinate);
                properties.put("project.parent.groupId", parentCoordinate.group); properties.put("project.parent.version", parentCoordinate.version);
                properties.put("project.parent.artifactId", parentCoordinate.artifact);
            }
            String actualGroup = expand(text(xml, "groupId", coordinate.group), properties);
            String actualArtifact = expand(text(xml, "artifactId", ""), properties);
            String actualVersion = expand(text(xml, "version", coordinate.version), properties);
            if (!actualGroup.equals(coordinate.group) || !actualArtifact.equals(coordinate.artifact) || !actualVersion.equals(coordinate.version))
                throw new IOException("POM identity does not match requested coordinate: " + id);
            Pom result = new Pom(properties, expand(text(xml, "packaging", "jar"), properties), entry.repository);
            result.advertisesModule = advertisesModule;
            if (parent != null) {
                for (Raw raw : parent.management.values()) result.management.put(raw.key(properties), raw);
                for (Raw raw : parent.rawDependencies.values()) result.rawDependencies.put(raw.key(properties), raw);
            }
            List<Raw> management = new ArrayList<>();
            for (Element dep : children(child(child(xml, "dependencyManagement"), "dependencies"), "dependency")) management.add(new Raw(dep));
            Set<String> importedHere = new HashSet<>();
            // Child imports override inherited management; the first imported BOM wins at the same level.
            for (Raw raw : management) if (expand(raw.scope, properties).equals("import")) {
                Spec spec = raw.resolve(properties, null);
                if (!spec.type.equals("pom")) throw new IOException("BOM import must have type pom: " + id);
                for (Map.Entry<String, Spec> dependency : pom(spec.coordinate).managed.entrySet())
                    if (importedHere.add(dependency.getKey())) result.management.put(dependency.getKey(), new Raw(dependency.getValue()));
            }
            for (Raw raw : management) if (!expand(raw.scope, properties).equals("import")) result.management.put(raw.key(properties), raw);
            for (Map.Entry<String, Raw> raw : result.management.entrySet()) result.managed.put(raw.getKey(), raw.getValue().resolve(properties, null));
            Set<String> declared = new HashSet<>();
            for (Element dep : children(child(xml, "dependencies"), "dependency")) {
                Raw raw = new Raw(dep);
                String key = raw.key(properties);
                if (!declared.add(key)) throw new IOException("Duplicate POM dependency declaration: " + key + " in " + id);
                result.rawDependencies.put(key, raw);
            }
            for (Raw raw : result.rawDependencies.values()) result.dependencies.add(raw.resolve(properties, result.managed.get(raw.key(properties))));
            poms.put(id, result); return result;
        } finally { loading.remove(id); }
    }
    private static void identity(Map<String, String> properties, MavenCoordinate coordinate) {
        for (String prefix : Arrays.asList("project.", "pom.")) {
            properties.put(prefix + "groupId", coordinate.group); properties.put(prefix + "artifactId", coordinate.artifact); properties.put(prefix + "version", coordinate.version);
        }
    }
    private static String expand(String value, Map<String, String> properties) throws IOException {
        for (int round = 0; round < 20; round++) {
            int start = value.indexOf("${");
            if (start < 0) return value;
            int end = value.indexOf('}', start);
            if (end < 0) throw new IOException("Malformed POM property: " + value);
            String key = value.substring(start + 2, end);
            if (!properties.containsKey(key)) throw new IOException("Unresolved POM property: " + key);
            value = value.substring(0, start) + properties.get(key) + value.substring(end + 1);
        }
        throw new IOException("Cyclic or excessively nested POM property: " + value);
    }
    private static final class Pom {
        final Map<String, String> properties;
        final String packaging, repository;
        boolean advertisesModule;
        final Map<String, Raw> management = new LinkedHashMap<>(), rawDependencies = new LinkedHashMap<>();
        final Map<String, Spec> managed = new LinkedHashMap<>();
        final List<Spec> dependencies = new ArrayList<>();
        Pom(Map<String, String> properties, String packaging, String repository) {
            this.properties = properties; this.packaging = packaging; this.repository = repository;
        }
    }
    private static final class Raw {
        final String group, artifact, version, type, classifier, scope, optional;
        final Set<String> excludes = new LinkedHashSet<>();
        Raw(Element xml) {
            group = text(xml, "groupId", ""); artifact = text(xml, "artifactId", ""); version = text(xml, "version", "");
            type = text(xml, "type", ""); classifier = text(xml, "classifier", ""); scope = text(xml, "scope", ""); optional = text(xml, "optional", "");
            for (Element e : children(child(xml, "exclusions"), "exclusion")) excludes.add(text(e, "groupId", "") + ":" + text(e, "artifactId", ""));
        }
        Raw(Spec spec) {
            group = spec.coordinate.group; artifact = spec.coordinate.artifact; version = spec.coordinate.version;
            type = spec.type; classifier = spec.coordinate.classifier; scope = spec.scope; optional = String.valueOf(spec.optional); excludes.addAll(spec.excludes);
        }
        String key(Map<String, String> properties) throws IOException {
            return expand(group, properties) + ":" + expand(artifact, properties) + ":" + expand(type.isEmpty() ? "jar" : type, properties) + ":" + expand(classifier, properties);
        }
        Spec resolve(Map<String, String> properties, Spec managed) throws IOException {
            String resolvedVersion = version.isEmpty() && managed != null ? managed.coordinate.version : expand(version, properties);
            String resolvedType = type.isEmpty() ? (managed == null ? "jar" : managed.type) : expand(type, properties);
            String resolvedClassifier = expand(classifier, properties);
            if (resolvedType.equals("test-jar")) { resolvedType = "jar"; if (resolvedClassifier.isEmpty()) resolvedClassifier = "tests"; }
            String resolvedScope = scope.isEmpty() ? (managed == null ? "compile" : managed.scope) : expand(scope, properties);
            String resolvedOptional = optional.isEmpty() ? "false" : expand(optional, properties);
            if (!Arrays.asList("true", "false").contains(resolvedOptional)) throw new IOException("Invalid POM optional value");
            Set<String> rules = new LinkedHashSet<>();
            if (managed != null) rules.addAll(managed.excludes);
            for (String rule : excludes) {
                String expanded = expand(rule, properties);
                if (!expanded.matches("(?:[A-Za-z0-9_.-]+|\\*):(?:[A-Za-z0-9_.-]+|\\*)")) throw new IOException("Invalid POM exclusion: " + expanded);
                rules.add(expanded);
            }
            return new Spec(new MavenCoordinate(expand(group, properties), expand(artifact, properties), resolvedVersion, resolvedClassifier),
                resolvedType, !type.isEmpty() || managed != null, resolvedScope, resolvedOptional.equals("true"), rules);
        }
    }
    private static final class Spec {
        final MavenCoordinate coordinate;
        final String type, scope;
        final boolean explicitType, optional;
        final Set<String> excludes;
        Spec(MavenCoordinate coordinate, String type, boolean explicitType, String scope, boolean optional, Set<String> excludes) {
            this.coordinate = coordinate; this.type = type; this.explicitType = explicitType; this.scope = scope; this.optional = optional; this.excludes = excludes;
        }
    }
    private static final class Node {
        final MavenCoordinate coordinate;
        final String type, parent;
        final boolean compile, runtime;
        final boolean usageScoped;
        final Set<String> excludes;
        final List<String> ancestors;
        Node(MavenCoordinate coordinate, String type, boolean compile, boolean runtime, Set<String> excludes, List<String> ancestors, String parent) {
            this(coordinate, type, compile, runtime, excludes, ancestors, parent, false);
        }
        Node(MavenCoordinate coordinate, String type, boolean compile, boolean runtime, Set<String> excludes, List<String> ancestors, String parent, boolean usageScoped) {
            this.coordinate = coordinate; this.type = type; this.compile = compile; this.runtime = runtime;
            this.excludes = new LinkedHashSet<>(excludes); this.ancestors = ancestors; this.parent = parent;
            this.usageScoped = usageScoped;
        }
    }
    private static final class MutableArtifact {
        final String id, type, sha, repository;
        final File file;
        boolean compile, runtime;
        MutableArtifact(String id, String type, File file, String sha, String repository) {
            this.id = id; this.type = type; this.file = file; this.sha = sha; this.repository = repository;
        }
    }
}
