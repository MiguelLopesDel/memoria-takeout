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
    private final EventStore store;
    private final AnalyticsService analytics;
    private final YouTubeService youtube;
    private final AnnotationsService annotations;
    private final ImportJobService importJobs;
    private final FileBrowserService fileBrowser;
    private final String defaultTakeoutPath;
    private final ObjectMapper mapper;

    public ApiController(
            EventStore store,
            AnalyticsService analytics,
            YouTubeService youtube,
            AnnotationsService annotations,
            ImportJobService importJobs,
            FileBrowserService fileBrowser,
            @Value("${memoria.default-takeout-path}") String defaultTakeoutPath,
            ObjectMapper mapper) {
        this.store = store;
        this.analytics = analytics;
        this.youtube = youtube;
        this.annotations = annotations;
        this.importJobs = importJobs;
        this.fileBrowser = fileBrowser;
        this.defaultTakeoutPath = defaultTakeoutPath;
        this.mapper = mapper;
    }

    @GetMapping("/api/status")
    public StatusResponse status() {
        return new StatusResponse(store.dbPath(), store.eventCount(), store.latestImport(), defaultTakeoutPath);
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
        store.clearAll();
        return ResponseEntity.ok(Map.of("cleared", true, "eventCount", store.eventCount()));
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
        return analytics.facets(FilterParams.of(q, source, type, domain, from, to, product));
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
        return analytics.metrics(FilterParams.of(q, source, type, domain, from, to, product));
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
        return analytics.overview(FilterParams.of(q, source, type, domain, from, to, product));
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
        return analytics.siteReport(
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
        return analytics.patterns(
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
        return youtube.youtubeReport(
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
        return youtube.youtubeVideos(FilterParams.of(q, source, type, domain, from, to, product), search, limit);
    }

    @GetMapping("/api/youtube/video")
    public ResponseEntity<?> youtubeVideo(@RequestParam String id) {
        try {
            return ResponseEntity.ok(youtube.youtubeVideo(id));
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
        return youtube.youtubeChannel(FilterParams.of(q, source, type, domain, from, to, product), name, limit);
    }

    @GetMapping("/api/topics")
    public List<Map<String, Object>> topics() {
        return annotations.topics();
    }

    @PostMapping("/api/topics")
    public Map<String, Object> createTopic(@RequestBody Map<String, Object> payload) {
        return annotations.createTopic(payload);
    }

    @PostMapping("/api/topics/update")
    public Map<String, Object> updateTopic(@RequestBody Map<String, Object> payload) {
        return annotations.updateTopic(number(payload, "id"), payload);
    }

    @PostMapping("/api/topics/delete")
    public void deleteTopic(@RequestBody Map<String, Object> payload) {
        annotations.deleteTopic(number(payload, "id"));
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
            return ResponseEntity.ok(analytics.topicReport(
                    keywords, FilterParams.of(q, source, type, domain, from, to, product), limit));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @GetMapping("/api/on-this-day")
    public ResponseEntity<?> onThisDay(
            @RequestParam(defaultValue = "") String date, @RequestParam(defaultValue = "8") int perYear) {
        try {
            return ResponseEntity.ok(analytics.onThisDay(date, perYear));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.getMessage()));
        }
    }

    @GetMapping("/api/day")
    public ResponseEntity<?> day(@RequestParam String date) {
        try {
            return ResponseEntity.ok(analytics.day(date));
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
        return analytics.search(
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
                analytics.events(FilterParams.of(q, source, type, domain, from, to, product), safeLimit, safeOffset));
    }

    @GetMapping("/api/products")
    public List<Facet> products() {
        return analytics.products();
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
        return analytics.days(FilterParams.of(q, source, type, domain, from, to, product));
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
        return analytics.calendar(FilterParams.of(q, source, type, domain, from, to, product));
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
        return analytics.calendarDense(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @GetMapping("/api/domains")
    public List<Facet> domains(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "root") String group) {
        return analytics.domainSearch(search, limit, !"host".equalsIgnoreCase(group));
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
        return analytics.domainRanking(FilterParams.of(q, source, type, domain, from, to, product), limit);
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
        return analytics.sourceRanking(FilterParams.of(q, source, type, domain, from, to, product), limit);
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
        return analytics.typeRanking(FilterParams.of(q, source, type, domain, from, to, product), limit);
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
        return analytics.quality(FilterParams.of(q, source, type, domain, from, to, product));
    }

    @PostMapping("/api/backfill/timestamps")
    public Map<String, Object> backfillTimestamps(@RequestBody(required = false) Map<String, Object> payload) {
        int limit =
                payload == null || !payload.containsKey("limit") ? 50_000 : ((Number) payload.get("limit")).intValue();
        return store.backfillTimestamps(limit);
    }

    @PostMapping("/api/backfill/channels")
    public Map<String, Object> backfillChannels() {
        return store.backfillYouTubeChannels();
    }

    @PostMapping("/api/cleanup/format-duplicates")
    public Map<String, Object> cleanupFormatDuplicates() {
        return store.cleanupFormatDuplicates();
    }

    @PostMapping("/api/cleanup/metadata-fragments")
    public Map<String, Object> cleanupMetadataFragments() {
        return store.cleanupMetadataFragments();
    }

    @PostMapping("/api/cleanup/undated-html-fragments")
    public Map<String, Object> cleanupUndatedHtmlFragments() {
        return store.cleanupUndatedHtmlFragments();
    }

    @GetMapping("/api/compare")
    public Map<String, Object> compare(
            @RequestParam String leftFrom,
            @RequestParam String leftTo,
            @RequestParam String rightFrom,
            @RequestParam String rightTo) {
        return analytics.compare(
                FilterParams.of("", "", "", "", leftFrom, leftTo), FilterParams.of("", "", "", "", rightFrom, rightTo));
    }

    @GetMapping("/api/saved-filters")
    public List<Map<String, Object>> savedFilters() {
        return annotations.savedFilters();
    }

    @PostMapping("/api/saved-filters")
    public Map<String, Object> saveFilter(@RequestBody Map<String, Object> payload) {
        return annotations.saveFilter(payload);
    }

    @PostMapping("/api/saved-filters/delete")
    public void deleteSavedFilter(@RequestBody Map<String, Object> payload) {
        annotations.deleteSavedFilter(number(payload, "id"));
    }

    @GetMapping("/api/tags")
    public List<Map<String, Object>> tags() {
        return annotations.tags();
    }

    @PostMapping("/api/tags")
    public Map<String, Object> createTag(@RequestBody Map<String, Object> payload) {
        return annotations.createTag(payload);
    }

    @PostMapping("/api/tags/apply")
    public void tagEvent(@RequestBody Map<String, Object> payload) {
        annotations.tagEvent(number(payload, "eventId"), number(payload, "tagId"));
    }

    @PostMapping("/api/tags/remove")
    public void untagEvent(@RequestBody Map<String, Object> payload) {
        annotations.untagEvent(number(payload, "eventId"), number(payload, "tagId"));
    }

    @GetMapping("/api/events/tags")
    public List<Map<String, Object>> tagsForEvent(@RequestParam long eventId) {
        return annotations.tagsForEvent(eventId);
    }

    @GetMapping("/api/events/by-tag")
    public List<Map<String, Object>> eventsByTag(
            @RequestParam long tagId, @RequestParam(defaultValue = "300") int limit) {
        return annotations.eventsByTag(tagId, limit);
    }

    @GetMapping("/api/collections")
    public List<Map<String, Object>> collections() {
        return annotations.collections();
    }

    @PostMapping("/api/collections")
    public Map<String, Object> createCollection(@RequestBody Map<String, Object> payload) {
        return annotations.createCollection(payload);
    }

    @PostMapping("/api/collections/add")
    public void addToCollection(@RequestBody Map<String, Object> payload) {
        annotations.addToCollection(number(payload, "collectionId"), number(payload, "eventId"));
    }

    @PostMapping("/api/collections/remove")
    public void removeFromCollection(@RequestBody Map<String, Object> payload) {
        annotations.removeFromCollection(number(payload, "collectionId"), number(payload, "eventId"));
    }

    @GetMapping("/api/collections/events")
    public List<Map<String, Object>> collectionEvents(
            @RequestParam long collectionId, @RequestParam(defaultValue = "300") int limit) {
        return annotations.collectionEvents(collectionId, limit);
    }

    @GetMapping("/api/notes")
    public Map<String, Object> note(@RequestParam long eventId) {
        return annotations.note(eventId);
    }

    @PostMapping("/api/notes")
    public Map<String, Object> saveNote(@RequestBody Map<String, Object> payload) {
        return annotations.saveNote(number(payload, "eventId"), payload);
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
        List<Map<String, Object>> rows = analytics.events(filters, 10_000, 0);
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
