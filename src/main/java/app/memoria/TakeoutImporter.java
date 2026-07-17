package app.memoria;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TakeoutImporter {
    private static final int INSERT_BATCH_SIZE = 10_000;
    private static final int HTML_EVENT_LIMIT = 200_000;
    private static final int MAPS_EVENT_LIMIT = 100_000;
    private static final java.util.regex.Pattern PT_BR_DATE = java.util.regex.Pattern.compile(
            "(\\d{1,2}) de ([a-zç.]+) de (\\d{4}), (\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(BRT|BRST|UTC|GMT)?",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private static final DateTimeFormatter LOCAL_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LOCAL_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DatabaseService database;
    private final ObjectMapper mapper;
    private final String defaultTakeoutPath;
    private final List<Path> importRoots;
    private final ZoneId zone;
    // Per-import occurrence counter: numbers byte-identical content-hash records 0,1,2...
    // so distinct-but-indistinguishable events survive while a re-imported export dedups.
    // Imports run one at a time (single-thread executor), so a per-run field is safe.
    private final Map<String, Integer> occurrenceCounter = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TakeoutImporter(
            DatabaseService database,
            ObjectMapper mapper,
            @Value("${memoria.default-takeout-path}") String defaultTakeoutPath,
            @Value("${memoria.import-roots}") String importRoots,
            @Value("${memoria.timezone:America/Sao_Paulo}") String timezone) {
        this.database = database;
        this.mapper = mapper;
        this.defaultTakeoutPath = defaultTakeoutPath;
        this.importRoots = parseImportRoots(importRoots);
        this.zone = TimeZones.resolve(timezone);
    }

    // Convenience constructor for tests and non-Spring callers; uses the default timezone.
    public TakeoutImporter(
            DatabaseService database, ObjectMapper mapper, String defaultTakeoutPath, String importRoots) {
        this(database, mapper, defaultTakeoutPath, importRoots, "America/Sao_Paulo");
    }

    public ImportResult importTakeout(String requestedPath) throws IOException {
        return importTakeout(requestedPath, ProgressListener.NOOP);
    }

    public ImportResult importTakeout(String requestedPath, ProgressListener progress) throws IOException {
        String rawPath = (requestedPath == null || requestedPath.isBlank()) ? defaultTakeoutPath : requestedPath;
        progress.update(new ImportProgress("Validando caminho", -1, -1, -1, null, null, -1));
        Path source = resolveInputPath(rawPath);
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Caminho não encontrado: " + source);
        }

        Path importRoot = source;
        Path tempDir = null;
        if (Files.isRegularFile(source)) {
            progress.update(new ImportProgress("Extraindo arquivo Takeout", -1, -1, -1, null, null, -1));
            tempDir = Files.createTempDirectory("memoria-takeout-");
            extractArchive(source, tempDir);
            importRoot = findTakeoutRoot(tempDir);
        } else if (Files.isDirectory(source)) {
            importRoot = normalizeImportDirectory(source);
        }

        progress.update(new ImportProgress("Procurando arquivos do Takeout", -1, -1, -1, null, null, -1));
        List<Path> files = relevantFiles(importRoot);
        progress.update(new ImportProgress("Arquivos encontrados", -1, files.size(), 0, null, null, -1));

        progress.update(new ImportProgress("Preparando importação incremental", 0, files.size(), 0, null, null, 0));
        occurrenceCounter.clear();
        long beforeCount = database.eventCount();
        long importId = database.beginImport(source.toString());
        long[] count = {0};
        long[] bytesRead = {0};
        int processed = 0;
        List<EventRecord> batch = new ArrayList<>(INSERT_BATCH_SIZE);

        for (Path file : files) {
            // Identical records in one file are distinct occurrences. The same record repeated
            // in overlapping Takeout files receives the same key and is merged by the database.
            occurrenceCounter.clear();
            int processedForFile = processed;
            String relative = importRoot.relativize(file).toString();
            String sourceName = sourceName(file);
            progress.update(new ImportProgress(
                    "Lendo " + relative, count[0], files.size(), processedForFile, relative, sourceName, bytesRead[0]));
            parseFile(file, importRoot, event -> {
                if (event == null) return;
                batch.add(event);
                count[0]++;
                if (batch.size() >= INSERT_BATCH_SIZE) {
                    database.insertBatch(batch);
                    batch.clear();
                    progress.update(new ImportProgress(
                            "Indexando eventos",
                            count[0],
                            files.size(),
                            processedForFile,
                            relative,
                            sourceName,
                            bytesRead[0]));
                }
            });
            bytesRead[0] += safeSize(file);
            processed++;
            progress.update(new ImportProgress(
                    "Processando arquivos", count[0], files.size(), processed, relative, sourceName, bytesRead[0]));
        }
        if (!batch.isEmpty()) database.insertBatch(batch);
        progress.update(new ImportProgress(
                "Reconstruindo índice de busca", count[0], files.size(), processed, null, null, bytesRead[0]));
        long total = database.eventCount();
        long added = Math.max(0, total - beforeCount);
        database.finishImport(importId, total);

        if (tempDir != null) deleteRecursively(tempDir);
        return new ImportResult(source.toString(), count[0], added, total);
    }

    private Path resolveInputPath(String rawPath) {
        Path requested = Path.of(rawPath);
        Path direct = requested.toAbsolutePath().normalize();
        if (requested.isAbsolute() || Files.exists(direct)) return direct;

        for (Path root : importRoots) {
            Path candidate = root.resolve(rawPath).toAbsolutePath().normalize();
            if (candidate.startsWith(root) && Files.exists(candidate)) return candidate;
        }

        return direct;
    }

    private List<Path> parseImportRoots(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(root -> !root.isBlank())
                .map(root -> Path.of(root).toAbsolutePath().normalize())
                .toList();
    }

    private Path findTakeoutRoot(Path root) {
        Path direct = root.resolve("Takeout");
        return Files.isDirectory(direct) ? direct : root;
    }

    private Path normalizeImportDirectory(Path source) throws IOException {
        if (isTakeoutDirectory(source)) return source;

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> children = Files.list(source)) {
            children.filter(Files::isDirectory).filter(this::isTakeoutDirectory).forEach(candidates::add);
        }

        if (candidates.size() == 1) return candidates.get(0);
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("Escolha uma pasta Takeout específica. Esta pasta contém várias opções: "
                    + candidateNames(candidates));
        }

        throw new IllegalArgumentException(
                "Esta pasta não parece ser um Takeout. Abra o navegador de pastas e selecione a pasta Takeout ou um arquivo .zip/.tgz.");
    }

    private boolean isTakeoutDirectory(Path path) {
        String name =
                path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("takeout")) return true;
        try (Stream<Path> children = Files.list(path)) {
            return children.anyMatch(child -> {
                if (!Files.isDirectory(child)) return false;
                String childName = child.getFileName() == null
                        ? ""
                        : child.getFileName().toString().toLowerCase(Locale.ROOT);
                return childName.contains("youtube")
                        || childName.equals("chrome")
                        || childName.equals("search")
                        || childName.equals("maps")
                        || childName.equals("minha atividade")
                        || childName.equals("my activity");
            });
        } catch (IOException ignored) {
            return false;
        }
    }

    private String candidateNames(List<Path> candidates) {
        return candidates.stream()
                .limit(8)
                .map(path -> path.getFileName() == null
                        ? path.toString()
                        : path.getFileName().toString())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private List<Path> relevantFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String lower = dir.toString().toLowerCase(Locale.ROOT);
                String name = dir.getFileName() == null
                        ? ""
                        : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.contains("/drive/")
                        || lower.contains("/google fotos/")
                        || name.equals("node_modules")
                        || name.equals("target")
                        || name.equals("dist")
                        || name.equals(".git")
                        || name.equals(".memoria")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isRelevantFile(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> normalized(root.relativize(path))));
        return files;
    }

    private boolean isYouTube(String lower) {
        return lower.contains("youtube e youtube music") || lower.contains("youtube and youtube music");
    }

    private boolean isTimeline(String lower) {
        return lower.contains("/linha do tempo")
                || lower.contains("/histórico de localização")
                || lower.contains("/location history")
                || lower.contains("/semantic location history")
                || lower.contains("/timeline");
    }

    private boolean youtubeSection(String lower, String ptFolder, String enFolder) {
        return isYouTube(lower) && (lower.contains("/" + ptFolder) || lower.contains("/" + enFolder));
    }

    private boolean isRelevantFile(Path file) {
        String lower = normalized(file);
        return lower.endsWith("minhaatividade.json")
                || lower.endsWith("myactivity.json")
                || lower.endsWith("minhaatividade.html")
                || lower.endsWith("myactivity.html")
                || lower.endsWith("chrome/histórico.json")
                || lower.endsWith("chrome/history.json")
                || lower.endsWith(".mbox")
                || (lower.contains("/agenda/") && lower.endsWith(".ics"))
                || (lower.contains("/atividade do registro de acesso/atividades_") && lower.endsWith(".csv"))
                || (lower.contains("/google meet/conferencehistory/")
                        && lower.endsWith("conference_history_records.csv"))
                || (lower.contains("/google pay/transações no google pay/") && lower.endsWith(".csv"))
                || isSupportedTemporalJson(lower)
                || (lower.contains("/blogger/") && lower.endsWith(".atom"))
                || ((lower.contains("/maps") || isTimeline(lower)) && lower.endsWith(".json"))
                || (youtubeSection(lower, "comentários", "comments") && lower.endsWith(".csv"))
                || (youtubeSection(lower, "chats ao vivo", "live chats") && lower.endsWith(".csv"))
                || (youtubeSection(lower, "postagens", "posts") && lower.endsWith(".csv"))
                || (isYouTube(lower) && lower.contains("/mensagens/") && lower.endsWith(".csv"))
                || (isYouTube(lower) && lower.contains("/metadados do vídeo/") && lower.endsWith("vídeos.csv"))
                || (youtubeSection(lower, "histórico", "history")
                        && (lower.endsWith(".json") || lower.endsWith(".html")));
    }

    private boolean isSupportedTemporalJson(String lower) {
        return lower.endsWith("/google play store/installs.json")
                || lower.endsWith("/google play store/library.json")
                || lower.endsWith("/google play store/order history.json")
                || lower.endsWith("/google play store/purchase history.json")
                || lower.endsWith("/tarefas/tasks.json")
                || lower.endsWith("/search contributions/comentários.json")
                || (lower.contains("/notebooklm/") && lower.endsWith("metadata.json"));
    }

    private void parseFile(Path file, Path root, EventSink sink) throws IOException {
        String lower = normalized(file);
        if (lower.endsWith("minhaatividade.json") || lower.endsWith("myactivity.json"))
            parseMyActivity(file, root, sink);
        else if (lower.endsWith("minhaatividade.html") || lower.endsWith("myactivity.html"))
            parseMyActivityHtml(file, root, sink);
        else if (lower.endsWith("chrome/histórico.json") || lower.endsWith("chrome/history.json"))
            parseChromeHistory(file, root, sink);
        else if (lower.endsWith(".mbox")) parseMbox(file, root, sink);
        else if (lower.contains("/agenda/") && lower.endsWith(".ics")) parseCalendar(file, root, sink);
        else if (lower.contains("/atividade do registro de acesso/atividades_") && lower.endsWith(".csv"))
            parseAccessLog(file, root, sink);
        else if (lower.contains("/google meet/conferencehistory/") && lower.endsWith("conference_history_records.csv"))
            parseMeetHistory(file, root, sink);
        else if (lower.contains("/google pay/transações no google pay/") && lower.endsWith(".csv"))
            parseGooglePay(file, root, sink);
        else if (isSupportedTemporalJson(lower)) parseSupportedTemporalJson(file, root, sink);
        else if (lower.contains("/blogger/") && lower.endsWith(".atom")) parseBlogger(file, root, sink);
        else if ((lower.contains("/maps") || isTimeline(lower)) && lower.endsWith(".json"))
            parseMapsJson(file, root, sink);
        else if (youtubeSection(lower, "histórico", "history") && lower.endsWith(".json"))
            parseYouTubeHistory(file, root, sink);
        else if (youtubeSection(lower, "histórico", "history") && lower.endsWith(".html"))
            parseActivityHtml(file, root, "YouTube", sink);
        else if (youtubeSection(lower, "comentários", "comments") && lower.endsWith(".csv"))
            parseYouTubeComments(file, root, sink);
        else if (youtubeSection(lower, "chats ao vivo", "live chats") && lower.endsWith(".csv"))
            parseYouTubeLiveChats(file, root, sink);
        else if (youtubeSection(lower, "postagens", "posts") && lower.endsWith(".csv"))
            parseYouTubePosts(file, root, sink);
        else if (isYouTube(lower) && lower.contains("/mensagens/") && lower.endsWith(".csv"))
            parseYouTubeMessages(file, root, sink);
        else if (isYouTube(lower) && lower.contains("/metadados do vídeo/") && lower.endsWith("vídeos.csv"))
            parseYouTubeVideos(file, root, sink);
    }

    private void parseMyActivity(Path file, Path root, EventSink sink) throws IOException {
        String fallbackSource = file.getParent().getFileName().toString();
        forEachArrayItem(file, row -> {
            String url = unwrapGoogleRedirect(firstText(row, "titleUrl", "url"));
            // JSON-format exports prefix titles with the action ("Watched X", "Searched for X");
            // the HTML format doesn't (the anchor holds just the object). Infer the type from the
            // raw title (it relies on those verbs), then store the stripped one so both formats
            // produce the same title and FTS/grouping don't split between them.
            String rawTitle = stripHtml(firstNonBlank(text(row, "title"), text(row, "header"), fallbackSource));
            String title = stripActivityAction(rawTitle);
            String source = firstNonBlank(firstArrayText(row.get("products")), text(row, "header"), fallbackSource);
            String text = String.join(
                    " ",
                    flatten(row.get("description")),
                    flatten(row.get("details")),
                    flatten(row.get("subtitles")),
                    flatten(row.get("safeHtmlItem")),
                    flatten(row.get("locationInfos")),
                    flatten(row.get("attachedFiles")),
                    flatten(row.get("products")),
                    flatten(row.get("activityControls")));
            sink.accept(normalizeEvent(
                    time(row),
                    source,
                    inferType(source, rawTitle, url, file),
                    title,
                    text,
                    url,
                    root.relativize(file).toString(),
                    row));
        });
    }

    // Leading action verbs used by JSON-format My Activity / YouTube history titles.
    private static final String[] ACTIVITY_TITLE_PREFIXES = {
        "Watched ", "Visited ", "Searched for ", "Viewed ", "Liked ", "Disliked ",
        "Subscribed to ", "Used ", "Prompted ", "Translated ", "Voted on ", "Played ",
        "Dismissed ", "Explored ", "Shared ", "Saved ", "Defined "
    };

    private String stripActivityAction(String title) {
        if (title == null) return null;
        for (String prefix : ACTIVITY_TITLE_PREFIXES) {
            if (title.length() > prefix.length() && title.startsWith(prefix)) {
                return title.substring(prefix.length()).trim();
            }
        }
        return title;
    }

    private void parseChromeHistory(Path file, Path root, EventSink sink) throws IOException {
        forEachArrayFieldItem(file, "Browser History", row -> {
            String url = text(row, "url");
            String title = firstNonBlank(text(row, "title"), url);
            String timestamp = chromeTime(row.path("time_usec").asLong(0));
            String details = firstNonBlank(
                    text(row, "page_transition"), text(row, "page_transition_qualifier"), text(row, "client_id"));
            sink.accept(normalizeEvent(
                    timestamp,
                    "Chrome",
                    "access",
                    title,
                    details,
                    url,
                    root.relativize(file).toString(),
                    row));
        });
    }

    private void parseMyActivityHtml(Path file, Path root, EventSink sink) throws IOException {
        parseActivityHtml(file, root, file.getParent().getFileName().toString(), sink);
    }

    private void parseActivityHtml(Path file, Path root, String source, EventSink sink) throws IOException {
        Document document = Jsoup.parse(file.toFile(), "UTF-8");
        int emitted = 0;
        var cells = document.select("div.outer-cell");
        if (cells.isEmpty()) cells = document.select("div.content-cell, div.mdl-cell");
        for (Element element : cells) {
            String text = element.text();
            if (text.length() < 12) continue;
            String timestamp = extractIsoOrLooseDate(text);
            if (timestamp == null) continue;
            String url = element.select("a[href]").stream()
                    .map(link -> link.attr("abs:href").isBlank() ? link.attr("href") : link.attr("abs:href"))
                    .filter(link -> !link.isBlank())
                    .findFirst()
                    .orElse(null);
            String title = element.select("a").stream()
                    .map(Element::text)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse(text.substring(0, Math.min(120, text.length())));
            sink.accept(normalizeEvent(
                    timestamp,
                    source,
                    inferType(source, title, url, file),
                    title,
                    text,
                    url,
                    root.relativize(file).toString(),
                    mapper.valueToTree(Map.of("htmlText", text))));
            emitted++;
            if (emitted >= HTML_EVENT_LIMIT) break;
        }
    }

    private void parseCalendar(Path file, Path root, EventSink sink) throws IOException {
        List<String> unfolded = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!unfolded.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                int last = unfolded.size() - 1;
                unfolded.set(last, unfolded.get(last) + line.substring(1));
            } else {
                unfolded.add(line);
            }
        }

        Map<String, String> event = null;
        for (String line : unfolded) {
            if ("BEGIN:VEVENT".equals(line)) {
                event = new HashMap<>();
            } else if ("END:VEVENT".equals(line) && event != null) {
                String timestamp = normalizeIcsDate(event.get("DTSTART"));
                String title = firstNonBlank(unescapeIcs(event.get("SUMMARY")), "Evento do calendário");
                String details = String.join(
                        " ",
                        firstNonBlank(unescapeIcs(event.get("DESCRIPTION")), ""),
                        firstNonBlank(unescapeIcs(event.get("LOCATION")), ""),
                        firstNonBlank(event.get("STATUS"), ""));
                sink.accept(normalizeEvent(
                        timestamp,
                        "Agenda",
                        "calendar",
                        title,
                        details,
                        event.get("URL"),
                        root.relativize(file).toString(),
                        mapper.valueToTree(event)));
                event = null;
            } else if (event != null) {
                int separator = line.indexOf(':');
                if (separator <= 0) continue;
                String property = line.substring(0, separator);
                int parameters = property.indexOf(';');
                if (parameters >= 0) property = property.substring(0, parameters);
                event.putIfAbsent(property, line.substring(separator + 1));
            }
        }
    }

    private String normalizeIcsDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String clean = value.trim();
            if (clean.matches("\\d{8}")) {
                return java.time.LocalDate.parse(clean, DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toString();
            }
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
            if (clean.endsWith("Z")) {
                return LocalDateTime.parse(clean.substring(0, clean.length() - 1), format)
                        .toInstant(ZoneOffset.UTC)
                        .toString();
            }
            return LocalDateTime.parse(clean, format).atZone(zone).toInstant().toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String unescapeIcs(String value) {
        if (value == null) return "";
        return value.replace("\\n", " ")
                .replace("\\N", " ")
                .replace("\\,", ",")
                .replace("\\;", ";")
                .replace("\\\\", "\\")
                .trim();
    }

    private void parseAccessLog(Path file, Path root, EventSink sink) throws IOException {
        forEachCsv(file, row -> {
            String product = firstNonBlank(get(row, "Product Name"), get(row, "Sub-Product Name"), "Conta do Google");
            String title = "Acesso a " + product;
            String details = String.join(
                    " ",
                    get(row, "Activity Type"),
                    get(row, "Activity Country"),
                    get(row, "Activity Region"),
                    get(row, "Activity City"),
                    get(row, "User Agent String"),
                    get(row, "Gmail Access Channel"));
            sink.accept(normalizeEvent(
                    get(row, "Activity Timestamp"),
                    "Registro de acesso",
                    "access",
                    title,
                    details,
                    null,
                    root.relativize(file).toString(),
                    mapper.valueToTree(row.toMap())));
        });
    }

    private void parseMeetHistory(Path file, Path root, EventSink sink) throws IOException {
        forEachCsv(file, row -> {
            String meetingCode = get(row, "Meeting Code");
            String title = meetingCode.isBlank() ? "Chamada do Google Meet" : "Google Meet " + meetingCode;
            String details = String.join(
                    " ",
                    get(row, "Call Direction"),
                    get(row, "Duration"),
                    get(row, "Direct Call Result"),
                    get(row, "Participation State"),
                    get(row, "Call Counterparts"));
            sink.accept(normalizeEvent(
                    get(row, "Start Time"),
                    "Google Meet",
                    "meeting",
                    title,
                    details,
                    null,
                    root.relativize(file).toString(),
                    mapper.valueToTree(row.toMap())));
        });
    }

    private void parseGooglePay(Path file, Path root, EventSink sink) throws IOException {
        forEachCsv(file, row -> {
            String title = firstNonBlank(get(row, "Descrição"), get(row, "Description"), "Transação do Google Pay");
            String details = String.join(
                    " ", get(row, "Produto"), get(row, "Forma de pagamento"), get(row, "Status"), get(row, "Valor"));
            sink.accept(normalizeEvent(
                    firstNonBlank(get(row, "Hora"), get(row, "Time")),
                    "Google Pay",
                    "purchase",
                    title,
                    details,
                    null,
                    root.relativize(file).toString(),
                    mapper.valueToTree(row.toMap())));
        });
    }

    private void parseSupportedTemporalJson(Path file, Path root, EventSink sink) throws IOException {
        JsonNode data = mapper.readTree(file.toFile());
        String lower = normalized(file);
        String source;
        String type;
        if (lower.contains("/google play store/")) {
            source = "Google Play Store";
            type = lower.endsWith("installs.json")
                    ? "install"
                    : lower.endsWith("library.json") ? "acquisition" : "purchase";
        } else if (lower.contains("/tarefas/")) {
            collectTaskEvents(data, file, root, sink);
            return;
        } else if (lower.contains("/notebooklm/")) {
            source = "NotebookLM";
            type = "notebook";
        } else {
            source = "Search Contributions";
            type = "comment";
        }

        if (data.isArray()) {
            for (JsonNode row : data) emitTemporalJson(row, source, type, file, root, sink);
        } else {
            emitTemporalJson(data, source, type, file, root, sink);
        }
    }

    private void collectTaskEvents(JsonNode node, Path file, Path root, EventSink sink) throws IOException {
        if (node == null || node.isNull()) return;
        if (node.isObject() && "tasks#task".equals(text(node, "kind"))) {
            emitTemporalJson(node, "Tarefas", "task", file, root, sink);
            return;
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) collectTaskEvents(children.next(), file, root, sink);
    }

    private void emitTemporalJson(JsonNode row, String source, String type, Path file, Path root, EventSink sink)
            throws IOException {
        String timestamp = firstDeepText(
                row,
                "time",
                "timestamp",
                "creationTime",
                "createTime",
                "created",
                "Published",
                "published",
                "purchaseTime",
                "acquisitionTime",
                "firstInstallationTime",
                "sourceAddedTimestamp",
                "dateAdded",
                "lastViewed");
        if (timestamp.isBlank()) return;
        String title = firstDeepText(row, "title", "name", "summary", "Search Query", "description");
        String url = firstDeepText(row, "url", "selfLink", "titleUrl");
        if (title.isBlank()) title = file.getFileName().toString().replaceFirst("(?i)\\.json$", "");
        sink.accept(normalizeEvent(
                timestamp,
                source,
                type,
                title,
                trimText(flatten(row), 2500),
                url,
                root.relativize(file).toString(),
                row));
    }

    private String firstDeepText(JsonNode node, String... fields) {
        if (node == null || node.isNull()) return "";
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.asText("").isBlank()) return value.asText();
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            String value = firstDeepText(children.next(), fields);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private void parseBlogger(Path file, Path root, EventSink sink) throws IOException {
        Document document = Jsoup.parse(Files.readString(file, StandardCharsets.UTF_8), "", Parser.xmlParser());
        for (Element entry : document.getElementsByTag("entry")) {
            String timestamp = elementText(entry, "blogger:created", "published", "updated");
            String body = elementText(entry, "content");
            String author = entry.getElementsByTag("author").isEmpty()
                    ? ""
                    : entry.getElementsByTag("author").first().text();
            String title = body.isBlank() ? "Comentário do Blogger" : trimText(body, 160);
            sink.accept(normalizeEvent(
                    timestamp,
                    "Blogger",
                    "comment",
                    title,
                    author + " " + body,
                    null,
                    root.relativize(file).toString(),
                    mapper.valueToTree(Map.of("xml", entry.outerHtml()))));
        }
    }

    private String elementText(Element root, String... tags) {
        for (String tag : tags) {
            var elements = root.getElementsByTag(tag);
            if (!elements.isEmpty() && !elements.first().text().isBlank())
                return elements.first().text();
        }
        return "";
    }

    private void parseMapsJson(Path file, Path root, EventSink sink) throws IOException {
        JsonNode data = mapper.readTree(file.toFile());
        int[] emitted = {0};
        collectJsonEvents(data, "Maps", "map", file, root, sink, emitted);
    }

    private void collectJsonEvents(
            JsonNode node, String source, String type, Path file, Path root, EventSink sink, int[] emitted)
            throws IOException {
        if (node == null || node.isNull() || emitted[0] >= MAPS_EVENT_LIMIT) return;
        if (node.isObject()) {
            String timestamp = firstNonBlank(
                    text(node, "time"), text(node, "timestamp"), text(node, "date"), text(node, "creationTime"));
            String title = firstNonBlank(
                    text(node, "title"),
                    text(node, "name"),
                    text(node, "placeName"),
                    text(node, "address"),
                    text(node, "url"));
            String url = firstNonBlank(text(node, "url"), text(node, "mapsUrl"));
            String flattened = flatten(node);
            if (!timestamp.isBlank() && (!title.isBlank() || !flattened.isBlank())) {
                sink.accept(normalizeEvent(
                        timestamp,
                        source,
                        type,
                        title.isBlank() ? "Registro do Maps" : title,
                        flattened,
                        url,
                        root.relativize(file).toString(),
                        node));
                emitted[0]++;
                // A node that is itself an event is a leaf record: don't also emit its nested objects.
                return;
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) collectJsonEvents(children.next(), source, type, file, root, sink, emitted);
        } else if (node.isArray()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) collectJsonEvents(children.next(), source, type, file, root, sink, emitted);
        }
    }

    private void parseMbox(Path file, Path root, EventSink sink) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            StringBuilder message = new StringBuilder();
            String envelopeDate = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("From ")) {
                    if (!message.isEmpty()) {
                        sink.accept(emailEvent(message.toString(), envelopeDate, file, root));
                        message.setLength(0);
                    }
                    envelopeDate = normalizeMboxEnvelopeDate(line);
                    continue;
                }
                message.append(line).append("\r\n");
            }
            if (!message.isEmpty()) sink.accept(emailEvent(message.toString(), envelopeDate, file, root));
        }
    }

    private EventRecord emailEvent(String rawMessage, String envelopeDate, Path file, Path root) {
        try {
            Session session = Session.getInstance(new Properties());
            MimeMessage message =
                    new MimeMessage(session, new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
            String from = joinAddresses(message.getFrom());
            String to = joinAddresses(message.getRecipients(Message.RecipientType.TO));
            String subject = firstNonBlank(message.getSubject(), "Email");
            String date = message.getSentDate() != null
                    ? message.getSentDate().toInstant().toString()
                    : firstNonBlank(normalizeEmailDate(headerValue(message, "Date")), envelopeDate);
            String body;
            try {
                body = extractMailText(message, 2500);
            } catch (Exception ignored) {
                body = trimText(rawMessage, 2500);
            }
            String text = ("De: " + from + " Para: " + to + " " + body).trim();
            return normalizeEvent(
                    date,
                    "Gmail",
                    "email",
                    subject,
                    text,
                    null,
                    root.relativize(file).toString(),
                    mapper.valueToTree(
                            Map.of("from", from, "to", to, "subject", subject, "date", date == null ? "" : date)));
        } catch (Exception error) {
            return fallbackEmailEvent(rawMessage, envelopeDate, file, root);
        }
    }

    private EventRecord fallbackEmailEvent(String rawMessage, String envelopeDate, Path file, Path root) {
        Map<String, String> headers = new HashMap<>();
        String currentName = null;
        for (String line : rawMessage.split("\\r?\\n")) {
            if (line.isEmpty()) break;
            if ((line.startsWith(" ") || line.startsWith("\t")) && currentName != null) {
                headers.computeIfPresent(currentName, (key, value) -> value + " " + line.trim());
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            currentName = line.substring(0, separator).toLowerCase(Locale.ROOT);
            headers.putIfAbsent(currentName, line.substring(separator + 1).trim());
        }
        String subject = decodeMimeHeader(firstNonBlank(headers.get("subject"), "Email não decodificado"));
        String date = firstNonBlank(normalizeEmailDate(headers.get("date")), envelopeDate);
        String text = trimText(
                "De: " + firstNonBlank(headers.get("from"), "") + " Para: " + firstNonBlank(headers.get("to"), "") + " "
                        + rawMessage,
                2500);
        return normalizeEvent(
                date,
                "Gmail",
                "email",
                subject,
                text,
                null,
                root.relativize(file).toString(),
                mapper.valueToTree(headers));
    }

    private String normalizeMboxEnvelopeDate(String line) {
        try {
            int senderEnd = line.indexOf(' ', 5);
            if (senderEnd < 0) return null;
            DateTimeFormatter format = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
            return ZonedDateTime.parse(line.substring(senderEnd + 1).trim(), format)
                    .toInstant()
                    .toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String decodeMimeHeader(String value) {
        try {
            return MimeUtility.decodeText(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String headerValue(Part part, String name) {
        try {
            String[] values = part.getHeader(name);
            return values == null || values.length == 0 ? null : values[0];
        } catch (Exception ignored) {
            return null;
        }
    }

    private String joinAddresses(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) return "";
        return Stream.of(addresses)
                .map(Object::toString)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private String extractMailText(Part part, int limit) throws Exception {
        String disposition = part.getDisposition();
        if (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT)) return "";
        if (part.isMimeType("text/plain")) return trimText(String.valueOf(part.getContent()), limit);
        if (part.isMimeType("text/html"))
            return trimText(Jsoup.parse(String.valueOf(part.getContent())).text(), limit);
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < multipart.getCount() && out.length() < limit; i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                out.append(extractMailText(bodyPart, limit - out.length())).append(' ');
            }
            return trimText(out.toString(), limit);
        }
        return "";
    }

    private String trimText(String value, int limit) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() > limit ? clean.substring(0, limit) : clean;
    }

    private void parseYouTubeHistory(Path file, Path root, EventSink sink) throws IOException {
        forEachArrayItem(file, row -> {
            String url = firstText(row, "titleUrl", "url");
            String rawTitle = stripHtml(firstNonBlank(text(row, "title"), text(row, "header"), "YouTube"));
            String title = stripActivityAction(rawTitle);
            String text = flatten(row.get("description")) + " " + flatten(row.get("subtitles")) + " "
                    + flatten(row.get("details")) + " " + flatten(row.get("activityControls"));
            sink.accept(normalizeEvent(
                    time(row),
                    "YouTube",
                    inferType("YouTube", rawTitle, url, file),
                    title,
                    text,
                    url,
                    root.relativize(file).toString(),
                    row));
        });
    }

    private void parseYouTubeMessages(Path file, Path root, EventSink sink) throws IOException {
        forEachCsv(file, row -> {
            String videoId = get(row, "Mídia compartilhada: ID do vídeo");
            String messageId = get(row, "ID da mensagem");
            String url = videoId.isBlank() ? null : "https://www.youtube.com/watch?v=" + videoId;
            String title = videoId.isBlank() ? "Mensagem do YouTube" : "Vídeo compartilhado em mensagem";
            String details = String.join(
                    " ",
                    messageId,
                    get(row, "ID do canal do remetente"),
                    get(row, "ID da conversa"),
                    get(row, "IDs dos canais dos destinatários:"));
            sink.accept(normalizeEvent(
                    get(row, "Carimbo de data/hora da criação da mensagem (UTC)"),
                    "YouTube",
                    "message",
                    title,
                    details,
                    url,
                    root.relativize(file).toString(),
                    mapper.valueToTree(row.toMap())));
        });
    }

    private void parseYouTubeVideos(Path file, Path root, EventSink sink) throws IOException {
        forEachCsv(file, row -> {
            String videoId = get(row, "ID do vídeo");
            String title = firstNonBlank(get(row, "Título do vídeo (original)"), "Vídeo enviado ao YouTube");
            String details = String.join(
                    " ",
                    get(row, "Descrição do vídeo (original)"),
                    get(row, "Categoria do vídeo"),
                    get(row, "Privacidade"),
                    get(row, "Estado do vídeo"));
            String url = videoId.isBlank() ? null : "https://www.youtube.com/watch?v=" + videoId;
            sink.accept(normalizeEvent(
                    firstNonBlank(
                            get(row, "Carimbo de data/hora de criação do vídeo"),
                            get(row, "Carimbo de data/hora de publicação do vídeo")),
                    "YouTube",
                    "upload",
                    title,
                    details,
                    url,
                    root.relativize(file).toString(),
                    mapper.valueToTree(row.toMap())));
        });
    }

    private void parseYouTubeComments(Path file, Path root, EventSink sink) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                CSVParser csv = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .build()
                        .parse(reader)) {
            for (CSVRecord row : csv) {
                String videoId = get(row, "ID do vídeo");
                String url = videoId == null || videoId.isBlank() ? null : "https://www.youtube.com/watch?v=" + videoId;
                String text = decodeYouTubeText(get(row, "Texto do comentário"));
                sink.accept(normalizeEvent(
                        get(row, "Carimbo de data/hora em que o comentário foi criado"),
                        "YouTube",
                        "comment",
                        videoId == null || videoId.isBlank()
                                ? "Comentário do YouTube"
                                : "Comentário em vídeo " + videoId,
                        text,
                        url,
                        root.relativize(file).toString(),
                        mapper.valueToTree(row.toMap())));
            }
        }
    }

    private void parseYouTubeLiveChats(Path file, Path root, EventSink sink) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                CSVParser csv = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .build()
                        .parse(reader)) {
            for (CSVRecord row : csv) {
                String videoId = firstNonBlank(get(row, "ID do vídeo"), get(row, "Video ID"));
                String url = videoId == null || videoId.isBlank() ? null : "https://www.youtube.com/watch?v=" + videoId;
                String text =
                        decodeYouTubeText(firstNonBlank(get(row, "Texto do chat ao vivo"), get(row, "Live chat text")));
                sink.accept(normalizeEvent(
                        firstNonBlank(
                                get(row, "Marcação de tempo da criação do chat ao vivo"), get(row, "Creation time")),
                        "YouTube",
                        "chat",
                        videoId == null || videoId.isBlank()
                                ? "Chat ao vivo do YouTube"
                                : "Chat ao vivo em vídeo " + videoId,
                        text,
                        url,
                        root.relativize(file).toString(),
                        mapper.valueToTree(row.toMap())));
            }
        }
    }

    private void parseYouTubePosts(Path file, Path root, EventSink sink) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                CSVParser csv = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .build()
                        .parse(reader)) {
            for (CSVRecord row : csv) {
                Map<String, String> raw = row.toMap();
                String timestamp = firstNonBlank(
                        raw.get("Carimbo de data/hora da criação do post"),
                        raw.get("Post creation timestamp"),
                        raw.get("Carimbo de data/hora da publicação do post"),
                        raw.get("Carimbo de data/hora da atualização do post"),
                        raw.get("Carimbo de data/hora"),
                        raw.get("Time"),
                        raw.get("Data"));
                if (timestamp == null || timestamp.isBlank()) continue;
                String postId = firstNonBlank(raw.get("ID do post"), raw.get("Post ID"));
                String body = decodeYouTubeText(firstNonBlank(raw.get("Texto do post"), raw.get("Post text")));
                String title = firstNonBlank(trimText(body, 160), raw.get("Title"), postId, "Postagem do YouTube");
                String url = postId == null || postId.isBlank() ? null : "https://www.youtube.com/post/" + postId;
                sink.accept(normalizeEvent(
                        timestamp,
                        "YouTube",
                        "post",
                        title,
                        body,
                        url,
                        root.relativize(file).toString(),
                        mapper.valueToTree(raw)));
            }
        }
    }

    private EventRecord normalizeEvent(
            String timestampValue,
            String source,
            String type,
            String title,
            String text,
            String url,
            String filePath,
            JsonNode raw) {
        String timestamp = normalizeDate(timestampValue);
        ZonedDateTime local = localTime(timestamp);
        String yearMonth = local == null ? null : local.format(LOCAL_MONTH);
        String localDay = local == null ? null : local.format(LOCAL_DAY);
        Integer localHour = local == null ? null : local.getHour();
        Integer localWeekday = local == null ? null : local.getDayOfWeek().getValue() % 7;
        String domain = extractDomain(url);
        String rootDomain = Domains.registrable(domain);
        String cleanSource = clean(source, "Unknown");
        String cleanType = clean(type, "activity");
        String cleanTitle = clean(title);
        String cleanText = clean(text);
        String cleanUrl = clean(url);
        if (timestamp == null && cleanTitle == null && cleanText == null && cleanUrl == null) return null;
        String rawJson;
        try {
            rawJson = mapper.writeValueAsString(raw);
        } catch (IOException error) {
            rawJson = "{}";
        }
        String eventKey = eventKey(cleanSource, cleanType, timestamp, cleanTitle, cleanText, cleanUrl, rawJson, raw);
        return new EventRecord(
                eventKey,
                timestamp,
                yearMonth,
                localDay,
                localHour,
                localWeekday,
                cleanSource,
                cleanType,
                cleanTitle,
                cleanText,
                cleanUrl,
                domain,
                rootDomain,
                filePath,
                rawJson);
    }

    private ZonedDateTime localTime(String instant) {
        if (instant == null) return null;
        try {
            return Instant.parse(instant).atZone(zone);
        } catch (Exception ignored) {
            return null;
        }
    }

    // Natural key when the source carries a reliable id present in raw_json (so the backfill
    // re-derives it identically); otherwise a content hash plus a per-import occurrence ordinal.
    private String eventKey(
            String source,
            String type,
            String timestamp,
            String title,
            String text,
            String url,
            String rawJson,
            JsonNode raw) {
        String natural = EventKeys.naturalKey(source, raw);
        if (natural != null) return natural;
        String hash = EventKeys.contentHash(timestamp, source, type, url, title, text, rawJson, raw);
        int ordinal = occurrenceCounter.merge(hash, 1, Integer::sum) - 1;
        return hash + ":" + ordinal;
    }

    private String inferType(String source, String title, String url, Path file) {
        String host = extractDomain(url);
        String lowerSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        String lowerTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String path = normalized(file);
        if (path.contains("/comentários")
                || path.contains("/comments")
                || lowerTitle.startsWith("comentário")
                || lowerTitle.startsWith("comment")) return "comment";
        if (host != null && (host.contains("youtube.com") || host.equals("youtu.be"))) return "video";
        if (lowerSource.contains("youtube") && (lowerTitle.startsWith("assistiu") || lowerTitle.startsWith("watched")))
            return "video";
        if ((host != null && host.contains("google") && lowerUrl.contains("/search"))
                || lowerTitle.startsWith("pesquisou")
                || lowerTitle.startsWith("searched")) return "search";
        if (lowerSource.contains("maps") || lowerSource.contains("mapas")) return "map";
        if (url != null && !url.isBlank()) return "access";
        return "activity";
    }

    private String chromeTime(long micros) {
        if (micros <= 0) return null;
        return Instant.ofEpochMilli(micros / 1000).toString();
    }

    private String normalizeDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim()).toString();
        } catch (Exception ignored) {
            try {
                DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");
                return LocalDateTime.parse(value.trim(), utc)
                        .toInstant(ZoneOffset.UTC)
                        .toString();
            } catch (Exception ignoredUtc) {
                // Try the localized Takeout date below.
            }
            java.util.regex.Matcher matcher = PT_BR_DATE.matcher(value);
            if (!matcher.find()) return null;
            Month month = ptMonth(matcher.group(2));
            if (month == null) return null;
            LocalDateTime local = LocalDateTime.of(
                    Integer.parseInt(matcher.group(3)),
                    month,
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)),
                    matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6)));
            String zoneAbbrev =
                    matcher.group(7) == null ? "BRT" : matcher.group(7).toUpperCase(Locale.ROOT);
            return local.toInstant(brazilOffset(zoneAbbrev)).toString();
        }
    }

    private ZoneOffset brazilOffset(String zoneAbbrev) {
        return switch (zoneAbbrev) {
            case "UTC", "GMT" -> ZoneOffset.UTC;
            case "BRST" -> ZoneOffset.ofHours(-2);
            default -> ZoneOffset.ofHours(-3);
        };
    }

    private String normalizeEmailDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toString();
        } catch (Exception ignored) {
            return normalizeDate(value);
        }
    }

    private String extractIsoOrLooseDate(String text) {
        java.util.regex.Matcher iso = java.util.regex.Pattern.compile(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z")
                .matcher(text);
        if (iso.find()) return iso.group();
        java.util.regex.Matcher pt = PT_BR_DATE.matcher(text);
        if (pt.find()) return pt.group();
        return null;
    }

    private Month ptMonth(String value) {
        String clean = value.toLowerCase(Locale.ROOT).replace(".", "");
        return switch (clean) {
            case "jan", "janeiro" -> Month.JANUARY;
            case "fev", "fevereiro" -> Month.FEBRUARY;
            case "mar", "março", "marco" -> Month.MARCH;
            case "abr", "abril" -> Month.APRIL;
            case "mai", "maio" -> Month.MAY;
            case "jun", "junho" -> Month.JUNE;
            case "jul", "julho" -> Month.JULY;
            case "ago", "agosto" -> Month.AUGUST;
            case "set", "setembro" -> Month.SEPTEMBER;
            case "out", "outubro" -> Month.OCTOBER;
            case "nov", "novembro" -> Month.NOVEMBER;
            case "dez", "dezembro" -> Month.DECEMBER;
            default -> null;
        };
    }

    private String extractDomain(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String host = new URI(value).getHost();
            if (host == null) return null;
            return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (URISyntaxException error) {
            return null;
        }
    }

    private String unwrapGoogleRedirect(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null
                    || !host.toLowerCase(Locale.ROOT).matches("(?:www\\.)?google\\.[a-z.]+")
                    || !"/url".equals(uri.getPath())
                    || uri.getRawQuery() == null) return value;
            for (String pair : uri.getRawQuery().split("&")) {
                int separator = pair.indexOf('=');
                if (separator <= 0 || !"q".equals(pair.substring(0, separator))) continue;
                String target = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                if (target.startsWith("http://") || target.startsWith("https://")) return target;
            }
        } catch (Exception ignored) {
            // Keep the original URL when the redirect is malformed.
        }
        return value;
    }

    private String decodeYouTubeText(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        try {
            StringBuilder text = new StringBuilder();
            collectYouTubeText(mapper.readTree(trimmed), text);
            String decoded = text.toString().trim();
            if (!decoded.isBlank()) return decoded;
        } catch (Exception ignored) {
            // Not JSON; fall back to the raw cell value below.
        }
        return trimmed;
    }

    private void collectYouTubeText(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode child : node) collectYouTubeText(child, out);
        } else if (node.isObject()) {
            JsonNode text = node.get("text");
            if (text != null && text.isTextual()) out.append(text.asText());
            else node.elements().forEachRemaining(child -> collectYouTubeText(child, out));
        } else if (node.isTextual()) {
            out.append(node.asText());
        }
    }

    private String flatten(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual() || node.isNumber() || node.isBoolean()) return stripHtml(node.asText());
        StringBuilder out = new StringBuilder();
        Iterator<JsonNode> iterator = node.isObject() ? node.elements() : node.iterator();
        while (iterator.hasNext()) {
            String value = flatten(iterator.next());
            if (!value.isBlank()) out.append(value).append(' ');
        }
        return out.toString().trim();
    }

    private String stripHtml(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String firstArrayText(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return "";
        JsonNode first = node.get(0);
        return first.isTextual() ? first.asText() : flatten(first);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private String time(JsonNode row) {
        return text(row, "time");
    }

    private String get(CSVRecord row, String key) {
        return row.isMapped(key) ? row.get(key) : "";
    }

    private void forEachCsv(Path file, CsvRecordConsumer consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                CSVParser csv = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .build()
                        .parse(reader)) {
            for (CSVRecord row : csv) consumer.accept(row);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String clean(String value) {
        if (value == null) return null;
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.isBlank() ? null : clean;
    }

    private String clean(String value, String fallback) {
        String clean = clean(value);
        return clean == null ? fallback : clean;
    }

    private String normalized(Path file) {
        return file.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String sourceName(Path file) {
        String lower = normalized(file);
        if (isYouTube(lower)) return "YouTube";
        if (lower.contains("/chrome/")) return "Chrome";
        if (lower.contains("/maps") || isTimeline(lower)) return "Maps";
        if (lower.contains("/e-mail/") || lower.contains("/mail/") || lower.endsWith(".mbox")) return "Gmail";
        if (lower.contains("minha atividade") || lower.contains("my activity"))
            return file.getParent() == null
                    ? "My Activity"
                    : file.getParent().getFileName().toString();
        return file.getParent() == null
                ? "Takeout"
                : file.getParent().getFileName().toString();
    }

    private long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ignored) {
            return 0;
        }
    }

    private void forEachArrayItem(Path file, JsonNodeConsumer consumer) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_ARRAY) return;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                JsonNode row = mapper.readTree(parser);
                consumer.accept(row);
            }
        }
    }

    private void forEachArrayFieldItem(Path file, String fieldName, JsonNodeConsumer consumer) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(file.toFile())) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME || !fieldName.equals(parser.currentName())) continue;
                if (parser.nextToken() != JsonToken.START_ARRAY) return;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode row = mapper.readTree(parser);
                    consumer.accept(row);
                }
                return;
            }
        }
    }

    private void extractArchive(Path source, Path target) throws IOException {
        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            extractZip(source, target);
        } else if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".tar")) {
            extractTar(source, target, lower.endsWith(".tgz") || lower.endsWith(".tar.gz"));
        } else {
            throw new IllegalArgumentException("Formato não suportado. Use pasta Takeout, .zip, .tgz ou .tar.gz.");
        }
    }

    private void extractZip(Path source, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = safeResolve(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void extractTar(Path source, Path target, boolean gzipped) throws IOException {
        InputStream input = Files.newInputStream(source);
        if (gzipped) input = new GzipCompressorInputStream(input);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path out = safeResolve(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(tar, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path safeResolve(Path root, String name) throws IOException {
        Path out = root.resolve(name).normalize();
        if (!out.startsWith(root)) throw new IOException("Arquivo inválido no pacote: " + name);
        return out;
    }

    private void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record ImportProgress(
            String message,
            long eventCount,
            int filesFound,
            int filesProcessed,
            String currentFile,
            String currentSource,
            long bytesRead) {}

    public interface ProgressListener {
        ProgressListener NOOP = progress -> {};

        void update(ImportProgress progress);
    }

    private interface EventSink {
        void accept(EventRecord event) throws IOException;
    }

    private interface JsonNodeConsumer {
        void accept(JsonNode node) throws IOException;
    }

    private interface CsvRecordConsumer {
        void accept(CSVRecord row) throws IOException;
    }
}
