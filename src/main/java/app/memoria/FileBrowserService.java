package app.memoria;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FileBrowserService {
    private final String defaultTakeoutPath;
    private final List<Path> roots;

    public FileBrowserService(
            @Value("${memoria.default-takeout-path}") String defaultTakeoutPath,
            @Value("${memoria.import-roots}") String importRoots) {
        this.defaultTakeoutPath = defaultTakeoutPath;
        this.roots = allowedRoots(importRoots, defaultTakeoutPath);
    }

    public BrowseResponse browse(String requestedPath) throws IOException {
        if (requestedPath == null || requestedPath.isBlank()) {
            List<BrowseEntry> entries = roots.stream()
                    .filter(Files::exists)
                    .map(path -> entry(path, true))
                    .toList();
            return new BrowseResponse("", null, entries, false);
        }

        Path current = resolveAllowed(requestedPath);
        if (!Files.isDirectory(current)) {
            throw new IllegalArgumentException("Pasta não encontrada: " + requestedPath);
        }

        Path parent = parentWithinRoots(current);
        List<BrowseEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(current)) {
            children.filter(path -> Files.isDirectory(path) || isArchive(path))
                    .limit(600)
                    .forEach(path -> entries.add(entry(path, false)));
        }
        entries.sort(Comparator.comparing((BrowseEntry item) -> !"directory".equals(item.type()))
                .thenComparing(BrowseEntry::name, String.CASE_INSENSITIVE_ORDER));

        return new BrowseResponse(
                current.toString(), parent == null ? null : parent.toString(), entries, isImportable(current));
    }

    public List<String> roots() {
        return roots.stream().map(Path::toString).toList();
    }

    private BrowseEntry entry(Path path, boolean root) {
        String type = Files.isDirectory(path) ? "directory" : "archive";
        return new BrowseEntry(
                path.getFileName() == null
                        ? path.toString()
                        : path.getFileName().toString(),
                path.toString(),
                type,
                root,
                isImportable(path));
    }

    private Path resolveAllowed(String rawPath) {
        Path requested = Path.of(rawPath);
        List<Path> candidates = new ArrayList<>();
        if (requested.isAbsolute()) {
            candidates.add(requested.toAbsolutePath().normalize());
        } else {
            for (Path root : roots)
                candidates.add(root.resolve(rawPath).toAbsolutePath().normalize());
        }

        for (Path candidate : candidates) {
            if (isWithinRoots(candidate)) return candidate;
        }
        throw new IllegalArgumentException("Caminho fora das pastas permitidas: " + rawPath);
    }

    private boolean isWithinRoots(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        return roots.stream().anyMatch(root -> normalized.startsWith(root));
    }

    private Path parentWithinRoots(Path current) {
        Path parent = current.getParent();
        if (parent == null || !isWithinRoots(parent)) return null;
        if (roots.stream().anyMatch(root -> root.equals(current))) return null;
        return parent;
    }

    private boolean isImportable(Path path) {
        if (Files.isRegularFile(path)) return isArchive(path);
        if (!Files.isDirectory(path)) return false;
        String name =
                path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("takeout")) return true;
        try (Stream<Path> children = Files.list(path)) {
            return children.anyMatch(child -> {
                String childName = child.getFileName() == null
                        ? ""
                        : child.getFileName().toString().toLowerCase(Locale.ROOT);
                return Files.isDirectory(child)
                        && (childName.contains("youtube")
                                || childName.equals("chrome")
                                || childName.equals("search")
                                || childName.equals("maps")
                                || childName.equals("minha atividade")
                                || childName.equals("my activity"));
            });
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isArchive(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".tar");
    }

    private List<Path> allowedRoots(String importRoots, String defaultPath) {
        Set<Path> values = new LinkedHashSet<>();
        parseRoots(importRoots).forEach(values::add);
        if (values.isEmpty()) {
            addIfUsable(
                    values, Path.of(defaultPath).toAbsolutePath().normalize().getParent());
            addIfUsable(
                    values,
                    Path.of(System.getProperty("user.dir", "."))
                            .toAbsolutePath()
                            .normalize());
            addIfUsable(
                    values,
                    Path.of(System.getProperty("user.home", "."))
                            .toAbsolutePath()
                            .normalize());
        }
        return values.stream().filter(Files::exists).toList();
    }

    private List<Path> parseRoots(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(root -> !root.isBlank())
                .map(root -> Path.of(root).toAbsolutePath().normalize())
                .toList();
    }

    private void addIfUsable(Set<Path> values, Path path) {
        if (path != null && Files.exists(path)) values.add(path);
    }

    public record BrowseResponse(String path, String parent, List<BrowseEntry> entries, boolean importable) {}

    public record BrowseEntry(String name, String path, String type, boolean root, boolean importable) {}
}
