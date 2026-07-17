package app.memoria;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiController {
    private final DatabaseService database;
    private final ImportJobService importJobs;
    private final FileBrowserService fileBrowser;
    private final String defaultTakeoutPath;
    private final ObjectMapper mapper;

    public ApiController(
            DatabaseService database,
            ImportJobService importJobs,
            FileBrowserService fileBrowser,
            @Value("${memoria.default-takeout-path}") String defaultTakeoutPath,
            ObjectMapper mapper) {
        this.database = database;
        this.importJobs = importJobs;
        this.fileBrowser = fileBrowser;
        this.defaultTakeoutPath = defaultTakeoutPath;
        this.mapper = mapper;
    }

    @GetMapping("/api/status")
    public StatusResponse status() {
        return new StatusResponse(
                database.dbPath(), database.eventCount(), database.latestImport(), defaultTakeoutPath);
    }

    @GetMapping("/api/files/browse")
    public ResponseEntity<?> browseFiles(@RequestParam(defaultValue = "") String path) {
        try {
            return ResponseEntity.ok(fileBrowser.browse(path));
        } catch (IllegalArgumentException | IOException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @PostMapping("/api/import")
    public ResponseEntity<?> importTakeout(@RequestBody(required = false) ImportRequest request) {
        try {
            String path =
                    request == null || request.path() == null || request.path().isBlank()
                            ? defaultTakeoutPath
                            : request.path();
            return ResponseEntity.ok(importJobs.start(path));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @GetMapping("/api/import/status")
    public ImportJob importStatus() {
        return importJobs.status();
    }

    @PostMapping("/api/reset")
    public ResponseEntity<?> reset() {
        if ("running".equals(importJobs.status().status())) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Aguarde a importação em andamento terminar antes de limpar a base."));
        }
        database.clearAll();
        return ResponseEntity.ok(Map.of("cleared", true, "eventCount", database.eventCount()));
    }

    @GetMapping("/api/facets")
    public FacetsResponse facets(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product) {
        return database.facets(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @GetMapping("/api/metrics")
    public MetricsResponse metrics(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product) {
        return database.metrics(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @GetMapping("/api/metrics/overview")
    public Map<String, Object> metricsOverview(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product) {
        return database.overview(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @GetMapping("/api/site")
    public Map<String, Object> site(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "40") int limit,
            @RequestParam(defaultValue = "true") boolean whole) {
        return database.siteReport(
                FilterParams.of(q, source, type, domain, from, to, product), Math.max(1, Math.min(200, limit)), whole);
    }

    @GetMapping("/api/patterns")
    public Map<String, Object> patterns(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "30") int limit) {
        return database.patterns(
                FilterParams.of(q, source, type, domain, from, to, product), Math.max(1, Math.min(200, limit)));
    }

    @GetMapping("/api/youtube")
    public Map<String, Object> youtube(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "30") int limit) {
        return database.youtubeReport(
                FilterParams.of(q, source, type, domain, from, to, product), Math.max(1, Math.min(200, limit)));
    }

    @GetMapping("/api/youtube/videos")
    public List<Map<String, Object>> youtubeVideos(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "40") int limit) {
        return database.youtubeVideos(FilterParams.of(q, source, type, domain, from, to, product), search, limit);
    }

    @GetMapping("/api/youtube/video")
    public ResponseEntity<?> youtubeVideo(@RequestParam String id) {
        try {
            return ResponseEntity.ok(database.youtubeVideo(id));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @GetMapping("/api/youtube/channel")
    public Map<String, Object> youtubeChannel(
            @RequestParam String name,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "15") int limit) {
        return database.youtubeChannel(FilterParams.of(q, source, type, domain, from, to, product), name, limit);
    }

    @GetMapping("/api/topics")
    public List<Map<String, Object>> topics() {
        return database.topics();
    }

    @PostMapping("/api/topics")
    public Map<String, Object> createTopic(@RequestBody Map<String, Object> payload) {
        return database.createTopic(payload);
    }

    @PostMapping("/api/topics/update")
    public Map<String, Object> updateTopic(@RequestBody Map<String, Object> payload) {
        return database.updateTopic(number(payload, "id"), payload);
    }

    @PostMapping("/api/topics/delete")
    public void deleteTopic(@RequestBody Map<String, Object> payload) {
        database.deleteTopic(number(payload, "id"));
    }

    @GetMapping("/api/topics/report")
    public ResponseEntity<?> topicReport(
            @RequestParam(defaultValue = "") String keywords,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            return ResponseEntity.ok(
                    database.topicReport(keywords, FilterParams.of(q, source, type, domain, from, to, product), limit));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @GetMapping("/api/on-this-day")
    public ResponseEntity<?> onThisDay(
            @RequestParam(defaultValue = "") String date, @RequestParam(defaultValue = "8") int perYear) {
        try {
            return ResponseEntity.ok(database.onThisDay(date, perYear));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @GetMapping("/api/day")
    public ResponseEntity<?> day(@RequestParam String date) {
        try {
            return ResponseEntity.ok(database.day(date));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "80") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return database.search(
                FilterParams.of(q, source, type, domain, from, to, product),
                Math.max(1, Math.min(200, limit)),
                Math.max(0, offset));
    }

    @GetMapping("/api/events")
    public EventsResponse events(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "80") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        int safeOffset = Math.max(0, offset);
        return new EventsResponse(
                database.events(FilterParams.of(q, source, type, domain, from, to, product), safeLimit, safeOffset));
    }

    @GetMapping("/api/products")
    public List<Facet> products() {
        return database.products();
    }

    @GetMapping("/api/timeline/days")
    public List<Map<String, Object>> days(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product) {
        return database.days(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @GetMapping("/api/calendar/activity")
    public List<Map<String, Object>> calendar(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product) {
        return database.calendar(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @GetMapping("/api/metrics/calendar")
    public List<Map<String, Object>> calendarDense(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product) {
        return database.calendarDense(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @GetMapping("/api/domains")
    public List<Facet> domains(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "root") String group) {
        return database.domainSearch(search, limit, !"host".equalsIgnoreCase(group));
    }

    @GetMapping("/api/rankings/domains")
    public List<Facet> domainRanking(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "50") int limit) {
        return database.domainRanking(FilterParams.of(q, source, type, domain, from, to, product), limit);
    }

    @GetMapping("/api/rankings/sources")
    public List<Facet> sourceRanking(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "50") int limit) {
        return database.sourceRanking(FilterParams.of(q, source, type, domain, from, to, product), limit);
    }

    @GetMapping("/api/rankings/types")
    public List<Facet> typeRanking(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product,
            @RequestParam(defaultValue = "50") int limit) {
        return database.typeRanking(FilterParams.of(q, source, type, domain, from, to, product), limit);
    }

    @GetMapping("/api/quality")
    public List<Map<String, Object>> quality(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product) {
        return database.quality(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @PostMapping("/api/backfill/timestamps")
    public Map<String, Object> backfillTimestamps(@RequestBody(required = false) Map<String, Object> payload) {
        int limit =
                payload == null || !payload.containsKey("limit") ? 50_000 : ((Number) payload.get("limit")).intValue();
        return database.backfillTimestamps(limit);
    }

    @PostMapping("/api/backfill/channels")
    public Map<String, Object> backfillChannels() {
        return database.backfillYouTubeChannels();
    }

    @PostMapping("/api/cleanup/format-duplicates")
    public Map<String, Object> cleanupFormatDuplicates() {
        return database.cleanupFormatDuplicates();
    }

    @PostMapping("/api/cleanup/metadata-fragments")
    public Map<String, Object> cleanupMetadataFragments() {
        return database.cleanupMetadataFragments();
    }

    @PostMapping("/api/cleanup/undated-html-fragments")
    public Map<String, Object> cleanupUndatedHtmlFragments() {
        return database.cleanupUndatedHtmlFragments();
    }

    @GetMapping("/api/compare")
    public Map<String, Object> compare(
            @RequestParam String leftFrom,
            @RequestParam String leftTo,
            @RequestParam String rightFrom,
            @RequestParam String rightTo) {
        return database.compare(
                FilterParams.of("", "", "", "", leftFrom, leftTo), FilterParams.of("", "", "", "", rightFrom, rightTo));
    }

    @GetMapping("/api/saved-filters")
    public List<Map<String, Object>> savedFilters() {
        return database.savedFilters();
    }

    @PostMapping("/api/saved-filters")
    public Map<String, Object> saveFilter(@RequestBody Map<String, Object> payload) {
        return database.saveFilter(payload);
    }

    @PostMapping("/api/saved-filters/delete")
    public void deleteSavedFilter(@RequestBody Map<String, Object> payload) {
        database.deleteSavedFilter(number(payload, "id"));
    }

    @GetMapping("/api/tags")
    public List<Map<String, Object>> tags() {
        return database.tags();
    }

    @PostMapping("/api/tags")
    public Map<String, Object> createTag(@RequestBody Map<String, Object> payload) {
        return database.createTag(payload);
    }

    @PostMapping("/api/tags/apply")
    public void tagEvent(@RequestBody Map<String, Object> payload) {
        database.tagEvent(number(payload, "eventId"), number(payload, "tagId"));
    }

    @PostMapping("/api/tags/remove")
    public void untagEvent(@RequestBody Map<String, Object> payload) {
        database.untagEvent(number(payload, "eventId"), number(payload, "tagId"));
    }

    @GetMapping("/api/events/tags")
    public List<Map<String, Object>> tagsForEvent(@RequestParam long eventId) {
        return database.tagsForEvent(eventId);
    }

    @GetMapping("/api/events/by-tag")
    public List<Map<String, Object>> eventsByTag(
            @RequestParam long tagId, @RequestParam(defaultValue = "300") int limit) {
        return database.eventsByTag(tagId, limit);
    }

    @GetMapping("/api/collections")
    public List<Map<String, Object>> collections() {
        return database.collections();
    }

    @PostMapping("/api/collections")
    public Map<String, Object> createCollection(@RequestBody Map<String, Object> payload) {
        return database.createCollection(payload);
    }

    @PostMapping("/api/collections/add")
    public void addToCollection(@RequestBody Map<String, Object> payload) {
        database.addToCollection(number(payload, "collectionId"), number(payload, "eventId"));
    }

    @PostMapping("/api/collections/remove")
    public void removeFromCollection(@RequestBody Map<String, Object> payload) {
        database.removeFromCollection(number(payload, "collectionId"), number(payload, "eventId"));
    }

    @GetMapping("/api/collections/events")
    public List<Map<String, Object>> collectionEvents(
            @RequestParam long collectionId, @RequestParam(defaultValue = "300") int limit) {
        return database.collectionEvents(collectionId, limit);
    }

    @GetMapping("/api/notes")
    public Map<String, Object> note(@RequestParam long eventId) {
        return database.note(eventId);
    }

    @PostMapping("/api/notes")
    public Map<String, Object> saveNote(@RequestBody Map<String, Object> payload) {
        return database.saveNote(number(payload, "eventId"), payload);
    }

    @GetMapping("/api/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String product)
            throws Exception {
        FilterParams filters = FilterParams.of(q, source, type, domain, from, to, product);
        List<Map<String, Object>> rows = database.events(filters, 10_000, 0);
        if ("csv".equalsIgnoreCase(format)) return file("memoria.csv", "text/csv", csv(rows));
        if ("pdf".equalsIgnoreCase(format)) return file("memoria.pdf", "application/pdf", pdf(filters, rows));
        return file(
                "memoria.json",
                "application/json",
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(rows));
    }

    public record ErrorResponse(String error) {}

    private ResponseEntity<byte[]> file(String name, String contentType, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name)
                .contentType(MediaType.parseMediaType(contentType))
                .body(body);
    }

    private byte[] csv(List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("id,timestamp,source,type,domain,title,url,text\n");
        for (Map<String, Object> row : rows) {
            out.append(csv(row.get("id")))
                    .append(',')
                    .append(csv(row.get("timestamp")))
                    .append(',')
                    .append(csv(row.get("source")))
                    .append(',')
                    .append(csv(row.get("type")))
                    .append(',')
                    .append(csv(row.get("domain")))
                    .append(',')
                    .append(csv(row.get("title")))
                    .append(',')
                    .append(csv(row.get("url")))
                    .append(',')
                    .append(csv(row.get("text")))
                    .append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private byte[] pdf(FilterParams filters, List<Map<String, Object>> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 11);
                content.setLeading(14);
                content.newLineAtOffset(48, 740);
                content.showText("Memoria - Relatorio");
                content.newLine();
                content.showText("Eventos exportados: " + rows.size());
                content.newLine();
                content.showText("Filtros: " + sanitizePdf(filters.toString()));
                content.newLine();
                content.newLine();
                for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), 45))) {
                    content.showText(sanitizePdf("%s | %s | %s | %s"
                            .formatted(row.get("timestamp"), row.get("source"), row.get("type"), row.get("title"))));
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
        }
        return out.toByteArray();
    }

    private String sanitizePdf(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ");
        return clean.length() > 105 ? clean.substring(0, 105) : clean;
    }

    private long number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
