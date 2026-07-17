package app.memoria;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatabaseService {
    private static final Pattern PT_BR_DATE = Pattern.compile(
            "(\\d{1,2}) de ([a-zç.]+) de (\\d{4}), (\\d{1,2}):(\\d{2}):(\\d{2})\\s*(BRT|BRST|UTC|GMT)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ISO_IN_TEXT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z");
    private static final Pattern MONTH_DAY = Pattern.compile("^\\d{2}-\\d{2}$");
    private static final Pattern LOCAL_DAY_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter MONTH_DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");
    // Recall queries return a lean projection (no raw_json) since a single day can hold thousands of rows.
    private static final String RECALL_COLUMNS =
            "id, timestamp, local_day, local_hour, source, type, title, text, url, domain";
    private static final DateTimeFormatter LOCAL_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LOCAL_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private final Path dbPath;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transactions;
    private final ZoneId zone;

    @org.springframework.beans.factory.annotation.Autowired
    public DatabaseService(
            @Value("${memoria.data-dir}") String dataDir,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            TransactionTemplate transactions,
            @Value("${memoria.timezone:America/Sao_Paulo}") String timezone)
            throws IOException {
        Path dir = Path.of(dataDir);
        this.dbPath = dir.resolve("memoria.db");
        this.jdbc = jdbc;
        this.named = named;
        this.transactions = transactions;
        this.zone = TimeZones.resolve(timezone);
    }

    // Convenience constructor for tests and non-Spring callers; uses the default timezone.
    public DatabaseService(
            String dataDir, JdbcTemplate jdbc, NamedParameterJdbcTemplate named, TransactionTemplate transactions)
            throws IOException {
        this(dataDir, jdbc, named, transactions, "America/Sao_Paulo");
    }

    @PostConstruct
    public void init() {
        jdbc.execute("PRAGMA journal_mode = WAL");
        jdbc.execute("PRAGMA synchronous = NORMAL");
        jdbc.execute("PRAGMA temp_store = MEMORY");
        jdbc.execute("PRAGMA busy_timeout = 5000");
        jdbc.execute("PRAGMA cache_size = -131072");
        jdbc.execute("PRAGMA mmap_size = 268435456");
        createOrganizationTables();
        createEventTables();
        createImportJobTables();
    }

    private void createEventTables() {
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS imports (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          source_path TEXT NOT NULL,
          imported_at TEXT NOT NULL,
          event_count INTEGER NOT NULL DEFAULT 0
        )
        """);
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          event_key TEXT,
          timestamp TEXT,
          year_month TEXT,
          local_day TEXT,
          local_hour INTEGER,
          local_weekday INTEGER,
          source TEXT NOT NULL,
          type TEXT NOT NULL,
          title TEXT,
          text TEXT,
          url TEXT,
          domain TEXT,
          root_domain TEXT,
          file_path TEXT,
          raw_json TEXT
        )
        """);
        // Migrate databases created before local time / root domain columns existed.
        boolean added = ensureColumn("events", "local_day", "TEXT");
        ensureColumn("events", "local_hour", "INTEGER");
        ensureColumn("events", "local_weekday", "INTEGER");
        boolean addedRoot = ensureColumn("events", "root_domain", "TEXT");
        boolean addedChannel = ensureColumn("events", "channel", "TEXT");
        if (added) backfillLocalFieldsApprox();
        if (addedRoot) backfillRootDomains();
        if (addedChannel) backfillYouTubeChannels();
        // Migrate databases created before the stable event_key existed. Backfill and dedup
        // must run before the UNIQUE index is created below, or index creation would fail.
        boolean addedKey = ensureColumn("events", "event_key", "TEXT");
        boolean upgradedKey = !addedKey && needsEventKeyUpgrade();
        if (addedKey || upgradedKey) {
            jdbc.execute("DROP INDEX IF EXISTS idx_events_key");
            backfillEventKeys();
        }
        jdbc.execute(
                """
        CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5(
          title,
          text,
          url,
          domain,
          source,
          type,
          content='events',
          content_rowid='id'
        )
        """);
        jdbc.execute("DROP TRIGGER IF EXISTS events_ai");
        jdbc.execute("DROP TRIGGER IF EXISTS events_ad");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_year_month ON events(year_month)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_local_day ON events(local_day)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_source ON events(source)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_type ON events(type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_domain ON events(domain)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_root_domain ON events(root_domain)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_channel ON events(channel)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_source_timestamp ON events(source, timestamp)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_type_timestamp ON events(type, timestamp)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_domain_timestamp ON events(domain, timestamp)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_source_type_timestamp ON events(source, type, timestamp)");
        // Identity index used by incremental import's insert-or-update behavior.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_events_key ON events(event_key)");
        // The key backfill may have collapsed accidental duplicate rows; refresh the search index.
        if (addedKey || upgradedKey) jdbc.execute("INSERT INTO events_fts(events_fts) VALUES('rebuild')");
        jdbc.update(
                """
        UPDATE imports
        SET event_count = (SELECT COUNT(*) FROM events)
        WHERE id = (SELECT MAX(id) FROM imports)
        """);
    }

    private boolean needsEventKeyUpgrade() {
        Long oldKeys = jdbc.queryForObject(
                """
        SELECT COUNT(*) FROM events
        WHERE event_key IS NULL OR (
          event_key NOT LIKE 'event:%'
          AND event_key NOT LIKE 'chrome:%'
          AND event_key NOT LIKE 'ytc:%'
          AND event_key NOT LIKE 'ytchat:%'
          AND event_key NOT LIKE 'ytpost:%'
        )
        """,
                Long.class);
        return oldKeys != null && oldKeys > 0;
    }

    private boolean ensureColumn(String table, String column, String type) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            return true;
        } catch (Exception ignored) {
            // Column already exists.
            return false;
        }
    }

    // One-time migration for rows imported before local time columns existed.
    // Approximates local time as UTC-3 (Brazil, no DST); a full re-import recomputes
    // these exactly using the configured timezone and the corrected date parsing.
    private void backfillLocalFieldsApprox() {
        jdbc.update(
                """
        UPDATE events
        SET local_day = strftime('%Y-%m-%d', timestamp, '-3 hours'),
            local_hour = CAST(strftime('%H', timestamp, '-3 hours') AS INTEGER),
            local_weekday = CAST(strftime('%w', timestamp, '-3 hours') AS INTEGER),
            year_month = strftime('%Y-%m', timestamp, '-3 hours')
        WHERE timestamp IS NOT NULL AND local_day IS NULL
        """);
    }

    // Populates root_domain for pre-existing rows. Computes once per distinct host
    // (~a few thousand) and updates by domain, so it stays cheap even on large tables.
    private void backfillRootDomains() {
        List<String> hosts = jdbc.queryForList(
                "SELECT DISTINCT domain FROM events WHERE domain IS NOT NULL AND root_domain IS NULL", String.class);
        for (String host : hosts) {
            jdbc.update("UPDATE events SET root_domain = ? WHERE domain = ?", Domains.registrable(host), host);
        }
    }

    // One-time migration: compute the stable event_key for rows imported before it existed,
    // using the exact same logic as a fresh import (shared EventKeys), so a later incremental
    // import dedups against these rows. Rows are processed in id order (which follows original
    // insertion/encounter order) so content-hash occurrence ordinals match fresh imports.
    private void backfillEventKeys() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Integer> counter = new HashMap<>();
        List<Object[]> updates = new ArrayList<>();
        jdbc.query(
                "SELECT id, timestamp, source, type, title, text, url, file_path, raw_json FROM events ORDER BY id",
                (RowCallbackHandler) rs -> {
                    String source = rs.getString("source");
                    String rawJson = rs.getString("raw_json");
                    JsonNode raw = null;
                    if (rawJson != null && !rawJson.isBlank()) {
                        try {
                            raw = mapper.readTree(rawJson);
                        } catch (Exception ignored) {
                            // Leave raw null; the content hash still works from the stored columns.
                        }
                    }
                    String natural = EventKeys.naturalKey(source, raw);
                    String key;
                    if (natural != null) {
                        key = natural;
                    } else {
                        String hash = EventKeys.contentHash(
                                rs.getString("timestamp"),
                                source,
                                rs.getString("type"),
                                rs.getString("url"),
                                rs.getString("title"),
                                rs.getString("text"),
                                rawJson,
                                raw);
                        String occurrenceGroup = String.valueOf(rs.getString("file_path")) + "\u0001" + hash;
                        int ordinal = counter.merge(occurrenceGroup, 1, Integer::sum) - 1;
                        key = hash + ":" + ordinal;
                    }
                    updates.add(new Object[] {key, rs.getLong("id")});
                });
        // One surrounding transaction: without it SQLite commits every UPDATE to the WAL
        // individually, making a full-database backfill take many minutes on ~500k rows.
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < updates.size(); i += 5000) {
                jdbc.batchUpdate(
                        "UPDATE events SET event_key = ? WHERE id = ?",
                        updates.subList(i, Math.min(updates.size(), i + 5000)));
            }
        });
        // Move annotations from duplicate rows to the canonical row before collapsing them.
        String canonical =
                """
        SELECT event_key, MIN(id) AS keep_id
        FROM events
        GROUP BY event_key
        """;
        jdbc.update(
                """
        INSERT OR IGNORE INTO event_tags(event_id, tag_id)
        SELECT canonical.keep_id, tagged.tag_id
        FROM event_tags tagged
        JOIN events duplicate ON duplicate.id = tagged.event_id
        JOIN ("""
                        + canonical + ") canonical ON canonical.event_key = duplicate.event_key");
        jdbc.update(
                """
        INSERT OR IGNORE INTO collection_events(collection_id, event_id)
        SELECT collected.collection_id, canonical.keep_id
        FROM collection_events collected
        JOIN events duplicate ON duplicate.id = collected.event_id
        JOIN ("""
                        + canonical + ") canonical ON canonical.event_key = duplicate.event_key");
        jdbc.update(
                """
        INSERT OR IGNORE INTO event_notes(event_id, note, updated_at)
        SELECT canonical.keep_id, notes.note, notes.updated_at
        FROM event_notes notes
        JOIN events duplicate ON duplicate.id = notes.event_id
        JOIN ("""
                        + canonical + ") canonical ON canonical.event_key = duplicate.event_key");
        // Collapse duplicate keys, keeping stable earliest IDs.
        jdbc.update("DELETE FROM events WHERE id NOT IN (SELECT MIN(id) FROM events GROUP BY event_key)");
        jdbc.update("DELETE FROM event_tags WHERE event_id NOT IN (SELECT id FROM events)");
        jdbc.update("DELETE FROM collection_events WHERE event_id NOT IN (SELECT id FROM events)");
        jdbc.update("DELETE FROM event_notes WHERE event_id NOT IN (SELECT id FROM events)");
    }

    // Derives events.channel for YouTube activity rows whose raw_json carries the flattened
    // HTML text. Runs when the column is first added and after every import (new rows arrive
    // with channel NULL); one surrounding transaction keeps ~150k UPDATEs fast (see
    // backfillEventKeys). channel is not FTS-indexed, so no rebuild is needed here.
    public Map<String, Object> backfillYouTubeChannels() {
        ObjectMapper mapper = new ObjectMapper();
        List<Object[]> updates = new ArrayList<>();
        long[] scanned = {0};
        jdbc.query(
                """
        SELECT id, title, raw_json
        FROM events
        WHERE source = 'YouTube' AND channel IS NULL
          AND (raw_json LIKE '{"htmlText%' OR raw_json LIKE '%"subtitles"%')
        """,
                (RowCallbackHandler) rs -> {
                    scanned[0]++;
                    JsonNode raw;
                    try {
                        raw = mapper.readTree(rs.getString("raw_json"));
                    } catch (Exception ignored) {
                        return;
                    }
                    JsonNode htmlText = raw.get("htmlText");
                    String channel = htmlText != null
                            ? YouTubeChannels.extract(rs.getString("title"), htmlText.asText(null))
                            : YouTubeChannels.fromSubtitles(raw);
                    if (channel != null) updates.add(new Object[] {channel, rs.getLong("id")});
                });
        if (!updates.isEmpty()) {
            transactions.executeWithoutResult(status -> {
                for (int i = 0; i < updates.size(); i += 5000) {
                    jdbc.batchUpdate(
                            "UPDATE events SET channel = ? WHERE id = ?",
                            updates.subList(i, Math.min(updates.size(), i + 5000)));
                }
            });
        }
        return Map.of("scanned", scanned[0], "updated", updates.size());
    }

    private void createOrganizationTables() {
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS saved_filters (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL,
          query TEXT,
          source TEXT,
          type TEXT,
          domain TEXT,
          from_date TEXT,
          to_date TEXT,
          created_at TEXT NOT NULL
        )
        """);
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS tags (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL UNIQUE,
          color TEXT NOT NULL DEFAULT '#2f7d7e'
        )
        """);
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS event_tags (
          event_id INTEGER NOT NULL,
          tag_id INTEGER NOT NULL,
          PRIMARY KEY (event_id, tag_id)
        )
        """);
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS collections (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL,
          description TEXT,
          created_at TEXT NOT NULL
        )
        """);
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS collection_events (
          collection_id INTEGER NOT NULL,
          event_id INTEGER NOT NULL,
          PRIMARY KEY (collection_id, event_id)
        )
        """);
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS event_notes (
          event_id INTEGER PRIMARY KEY,
          note TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """);
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS topics (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL,
          color TEXT NOT NULL DEFAULT '#a78bfa',
          keywords TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """);
    }

    private void createImportJobTables() {
        jdbc.execute(
                """
        CREATE TABLE IF NOT EXISTS import_jobs (
          id TEXT PRIMARY KEY,
          path TEXT NOT NULL,
          status TEXT NOT NULL,
          message TEXT NOT NULL,
          event_count INTEGER NOT NULL DEFAULT 0,
          files_found INTEGER NOT NULL DEFAULT 0,
          files_processed INTEGER NOT NULL DEFAULT 0,
          current_file TEXT,
          current_source TEXT,
          bytes_read INTEGER NOT NULL DEFAULT 0,
          events_per_minute REAL NOT NULL DEFAULT 0,
          elapsed_seconds INTEGER NOT NULL DEFAULT 0,
          estimated_remaining_seconds INTEGER,
          started_at TEXT,
          finished_at TEXT,
          error TEXT,
          updated_at TEXT NOT NULL
        )
        """);
    }

    public String dbPath() {
        return dbPath.toString();
    }

    public long eventCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        return count == null ? 0 : count;
    }

    public Map<String, Object> latestImport() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM imports ORDER BY id DESC LIMIT 1");
        return rows.isEmpty() ? null : rows.get(0);
    }

    // Starts a merge import: records the run in `imports` without touching existing events,
    // so tags/notes/collections (keyed by the stable event ids) survive a re-import.
    public long beginImport(String sourcePath) {
        jdbc.update(
                "INSERT INTO imports (source_path, imported_at, event_count) VALUES (?, ?, 0)",
                sourcePath,
                Instant.now().toString());
        Number id = jdbc.queryForObject("SELECT MAX(id) FROM imports", Number.class);
        return id == null ? 1 : id.longValue();
    }

    // Destructive wipe of all imported data and annotations. Exposed as an explicit
    // "Limpar base" action; a normal import no longer does this.
    public void clearAll() {
        jdbc.execute((Statement statement) -> {
            statement.executeUpdate("DROP TABLE IF EXISTS events_fts");
            statement.executeUpdate("DROP TABLE IF EXISTS events");
            statement.executeUpdate("DELETE FROM imports");
            statement.executeUpdate("DELETE FROM event_tags");
            statement.executeUpdate("DELETE FROM collection_events");
            statement.executeUpdate("DELETE FROM event_notes");
            return null;
        });
        createEventTables();
    }

    public void insertBatch(List<EventRecord> events) {
        if (events.isEmpty()) return;
        // Re-import updates parser-derived fields in place while preserving event IDs and all
        // annotations that reference them. New immutable exported records are inserted.
        transactions.executeWithoutResult(status -> jdbc.batchUpdate(
                """
          INSERT INTO events (
            event_key, timestamp, year_month, local_day, local_hour, local_weekday,
            source, type, title, text, url, domain, root_domain, file_path, raw_json
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(event_key) DO UPDATE SET
            timestamp = excluded.timestamp,
            year_month = excluded.year_month,
            local_day = excluded.local_day,
            local_hour = excluded.local_hour,
            local_weekday = excluded.local_weekday,
            source = excluded.source,
            type = excluded.type,
            title = excluded.title,
            text = excluded.text,
            url = excluded.url,
            domain = excluded.domain,
            root_domain = excluded.root_domain,
            file_path = excluded.file_path,
            raw_json = excluded.raw_json
          WHERE events.timestamp IS NOT excluded.timestamp
             OR events.year_month IS NOT excluded.year_month
             OR events.local_day IS NOT excluded.local_day
             OR events.local_hour IS NOT excluded.local_hour
             OR events.local_weekday IS NOT excluded.local_weekday
             OR events.source IS NOT excluded.source
             OR events.type IS NOT excluded.type
             OR events.title IS NOT excluded.title
             OR events.text IS NOT excluded.text
             OR events.url IS NOT excluded.url
             OR events.domain IS NOT excluded.domain
             OR events.root_domain IS NOT excluded.root_domain
             OR events.file_path IS NOT excluded.file_path
             OR events.raw_json IS NOT excluded.raw_json
          """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                        EventRecord event = events.get(i);
                        ps.setString(1, event.eventKey());
                        ps.setString(2, event.timestamp());
                        ps.setString(3, event.yearMonth());
                        ps.setString(4, event.localDay());
                        ps.setObject(5, event.localHour());
                        ps.setObject(6, event.localWeekday());
                        ps.setString(7, event.source());
                        ps.setString(8, event.type());
                        ps.setString(9, event.title());
                        ps.setString(10, event.text());
                        ps.setString(11, event.url());
                        ps.setString(12, event.domain());
                        ps.setString(13, event.rootDomain());
                        ps.setString(14, event.filePath());
                        ps.setString(15, event.rawJson());
                    }

                    @Override
                    public int getBatchSize() {
                        return events.size();
                    }
                }));
    }

    public void finishImport(long importId, long count) {
        jdbc.update("UPDATE imports SET event_count = ? WHERE id = ?", count, importId);
        jdbc.execute("INSERT INTO events_fts(events_fts) VALUES('rebuild')");
        backfillYouTubeChannels();
        jdbc.execute("PRAGMA optimize");
    }

    public void upsertImportJob(ImportJob job) {
        jdbc.update(
                """
        INSERT INTO import_jobs (
          id, path, status, message, event_count, files_found, files_processed,
          current_file, current_source, bytes_read, events_per_minute,
          elapsed_seconds, estimated_remaining_seconds, started_at, finished_at,
          error, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          path = excluded.path,
          status = excluded.status,
          message = excluded.message,
          event_count = excluded.event_count,
          files_found = excluded.files_found,
          files_processed = excluded.files_processed,
          current_file = excluded.current_file,
          current_source = excluded.current_source,
          bytes_read = excluded.bytes_read,
          events_per_minute = excluded.events_per_minute,
          elapsed_seconds = excluded.elapsed_seconds,
          estimated_remaining_seconds = excluded.estimated_remaining_seconds,
          started_at = excluded.started_at,
          finished_at = excluded.finished_at,
          error = excluded.error,
          updated_at = excluded.updated_at
        """,
                job.id(),
                job.path(),
                job.status(),
                job.message(),
                job.eventCount(),
                job.filesFound(),
                job.filesProcessed(),
                job.currentFile(),
                job.currentSource(),
                job.bytesRead(),
                job.eventsPerMinute(),
                job.elapsedSeconds(),
                job.estimatedRemainingSeconds(),
                job.startedAt(),
                job.finishedAt(),
                job.error(),
                Instant.now().toString());
    }

    public ImportJob latestImportJob() {
        List<ImportJob> rows = jdbc.query(
                """
        SELECT *
        FROM import_jobs
        ORDER BY updated_at DESC
        LIMIT 1
        """,
                (rs, rowNum) -> importJob(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ImportJob importJob(ResultSet rs) throws SQLException {
        long estimate = rs.getLong("estimated_remaining_seconds");
        Long estimatedRemaining = rs.wasNull() ? null : estimate;
        return new ImportJob(
                rs.getString("id"),
                rs.getString("path"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getLong("event_count"),
                rs.getInt("files_found"),
                rs.getInt("files_processed"),
                rs.getString("current_file"),
                rs.getString("current_source"),
                rs.getLong("bytes_read"),
                rs.getDouble("events_per_minute"),
                rs.getLong("elapsed_seconds"),
                estimatedRemaining,
                rs.getString("started_at"),
                rs.getString("finished_at"),
                rs.getString("error"));
    }

    public MetricsResponse metrics(FilterParams filters) {
        long started = System.nanoTime();
        BuiltFilter built = buildFilters(filters, true);
        String where = built.where().isBlank() ? "" : " WHERE " + built.where();
        Map<String, Object> summary = named.queryForMap(
                """
        SELECT
          COUNT(*) as total,
          COUNT(DISTINCT domain) as domains,
          MIN(timestamp) as firstSeen,
          MAX(timestamp) as lastSeen
        FROM events
        """
                        + where,
                built.params());

        MetricsResponse response = new MetricsResponse(
                summary,
                grouped("type", built, 100, ""),
                grouped("source", built, 16, ""),
                grouped("COALESCE(domain, 'sem dominio')", built, 20, ""),
                timeline(built));
        logSlow("metrics", started);
        return response;
    }

    public Map<String, Object> overview(FilterParams filters) {
        MetricsResponse metrics = metrics(filters);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", metrics.summary());
        out.put("byType", metrics.byType());
        out.put("bySource", metrics.bySource());
        out.put("topDomains", metrics.topDomains());
        out.put("timeline", metrics.timeline());
        out.put("hourly", hourly(filters));
        out.put("weekdays", weekdays(filters));
        out.put("quality", quality(filters));
        return out;
    }

    public Map<String, Object> siteReport(FilterParams filters, int limit, boolean whole) {
        String label = filters.domain();
        if (whole && !filters.domain().isBlank()) {
            // Expand the registrable domain into all of its hosts so every aggregation
            // (metrics, hourly, top pages...) covers the site as a whole, with no changes
            // to the shared filter builder.
            String root = Domains.registrable(filters.domain());
            List<String> hosts = jdbc.queryForList(
                    "SELECT DISTINCT domain FROM events WHERE root_domain = ? AND domain IS NOT NULL LIMIT 300",
                    String.class,
                    root);
            if (!hosts.isEmpty()) {
                label = root;
                filters = new FilterParams(
                        filters.q(),
                        filters.source(),
                        filters.type(),
                        String.join(",", hosts),
                        filters.from(),
                        filters.to(),
                        filters.product());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        MetricsResponse metrics = metrics(filters);
        out.put("domain", label);
        out.put("whole", whole);
        out.put("summary", metrics.summary());
        out.put("timeline", metrics.timeline());
        out.put("byType", metrics.byType());
        out.put("hourly", hourly(filters));
        out.put("weekdays", weekdays(filters));
        out.put("days", days(filters));
        out.put("topPages", topPages(filters, limit));
        out.put("topReturns", topReturns(filters, limit));
        return out;
    }

    // Pages you kept coming back to: ranked by the number of distinct days they were visited,
    // which surfaces the periodic returns rather than one-off bursts.
    private List<Map<String, Object>> topReturns(FilterParams filters, int limit) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "url IS NOT NULL AND local_day IS NOT NULL");
        MapSqlParameterSource params = copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
        return named.queryForList(
                """
        SELECT url as url,
               MAX(title) as title,
               COUNT(DISTINCT local_day) as days,
               COUNT(*) as value,
               MIN(local_day) as firstDay,
               MAX(local_day) as lastDay
        FROM events
        WHERE %s
        GROUP BY url
        HAVING days >= 2
        ORDER BY days DESC, value DESC
        LIMIT :limit
        """
                        .formatted(where),
                params);
    }

    private List<Map<String, Object>> topPages(FilterParams filters, int limit) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "url IS NOT NULL");
        MapSqlParameterSource params = copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
        return named.queryForList(
                """
        SELECT url as url,
               MAX(title) as title,
               COUNT(*) as value,
               MAX(timestamp) as lastSeen
        FROM events
        WHERE %s
        GROUP BY url
        ORDER BY value DESC, lastSeen DESC
        LIMIT :limit
        """
                        .formatted(where),
                params);
    }

    public FacetsResponse facets(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, false);
        return new FacetsResponse(
                grouped("source", built, 100, ""),
                grouped("type", built, 100, ""),
                grouped("domain", built, 40, "domain IS NOT NULL"));
    }

    public List<Facet> products() {
        return grouped("source", buildFilters(FilterParams.of("", "", "", "", "", ""), false), 200, "");
    }

    public List<Map<String, Object>> days(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "local_day IS NOT NULL");
        return named.queryForList(
                """
        SELECT local_day as day, COUNT(*) as total
        FROM events
        WHERE %s
        GROUP BY day
        ORDER BY day DESC
        LIMIT 400
        """
                        .formatted(where),
                built.params());
    }

    // "Neste dia" — events sharing today's (or a given) month-day across all years,
    // grouped by year with a per-year sample, plus a random flashback from long ago.
    public Map<String, Object> onThisDay(String date, int perYear) {
        String md = resolveMonthDay(date);
        int sample = Math.max(1, Math.min(50, perYear));
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("md", md).addValue("sample", sample);

        List<Map<String, Object>> counts = named.queryForList(
                """
        SELECT substr(local_day, 1, 4) AS year, COUNT(*) AS total
        FROM events
        WHERE local_day IS NOT NULL AND substr(local_day, 6, 5) = :md
        GROUP BY year
        ORDER BY year DESC
        """,
                params);

        List<Map<String, Object>> sampled = named.queryForList(
                """
        SELECT %s FROM (
          SELECT %s,
                 ROW_NUMBER() OVER (PARTITION BY substr(local_day, 1, 4) ORDER BY COALESCE(timestamp, '') ASC, id ASC) AS rn
          FROM events
          WHERE local_day IS NOT NULL AND substr(local_day, 6, 5) = :md
        ) WHERE rn <= :sample
        ORDER BY local_day DESC, rn ASC
        """
                        .formatted(RECALL_COLUMNS, RECALL_COLUMNS),
                params);

        Map<String, List<Map<String, Object>>> byYear = new LinkedHashMap<>();
        for (Map<String, Object> row : sampled) {
            String year = String.valueOf(row.get("local_day")).substring(0, 4);
            byYear.computeIfAbsent(year, key -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> years = new ArrayList<>();
        for (Map<String, Object> count : counts) {
            String year = String.valueOf(count.get("year"));
            years.add(
                    Map.of("year", year, "total", count.get("total"), "events", byYear.getOrDefault(year, List.of())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthDay", md);
        out.put("years", years);
        out.put("flashback", flashback());
        return out;
    }

    // A chronological reconstruction of a single local day, all sources interleaved.
    public List<Map<String, Object>> day(String localDay) {
        if (localDay == null || !LOCAL_DAY_PATTERN.matcher(localDay.trim()).matches()) {
            throw new IllegalArgumentException("Data inválida. Use o formato AAAA-MM-DD.");
        }
        return named.queryForList(
                """
        SELECT %s
        FROM events
        WHERE local_day = :day
        ORDER BY COALESCE(timestamp, '') ASC, id ASC
        LIMIT 5000
        """
                        .formatted(RECALL_COLUMNS),
                new MapSqlParameterSource("day", localDay.trim()));
    }

    private String resolveMonthDay(String date) {
        if (date == null || date.isBlank()) return LocalDate.now(zone).format(MONTH_DAY_FMT);
        String trimmed = date.trim();
        if (LOCAL_DAY_PATTERN.matcher(trimmed).matches()) return trimmed.substring(5);
        if (MONTH_DAY.matcher(trimmed).matches()) return trimmed;
        throw new IllegalArgumentException("Data inválida. Use MM-DD ou AAAA-MM-DD.");
    }

    // A single notable event from more than a year ago, to resurface something forgotten.
    private Map<String, Object> flashback() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
        SELECT %s
        FROM events
        WHERE url IS NOT NULL AND title IS NOT NULL AND timestamp IS NOT NULL
          AND local_day IS NOT NULL AND local_day < date('now', '-1 year')
        ORDER BY RANDOM()
        LIMIT 1
        """
                        .formatted(RECALL_COLUMNS));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> calendar(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "local_day IS NOT NULL");
        return named.queryForList(
                """
        SELECT local_day as day, COUNT(*) as value
        FROM events
        WHERE %s
        GROUP BY day
        ORDER BY day ASC
        """
                        .formatted(where),
                built.params());
    }

    public List<Map<String, Object>> calendarDense(FilterParams filters) {
        List<Map<String, Object>> rows = calendar(filters);
        if (rows.isEmpty()) return rows;
        LocalDate first = LocalDate.parse(String.valueOf(rows.get(0).get("day")));
        LocalDate last =
                LocalDate.parse(String.valueOf(rows.get(rows.size() - 1).get("day")));
        Map<String, Object> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) byDay.put(String.valueOf(row.get("day")), row.get("value"));
        List<Map<String, Object>> dense = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            String key = day.toString();
            dense.add(Map.of("day", key, "value", byDay.getOrDefault(key, 0)));
        }
        return dense;
    }

    public List<Facet> domainRanking(FilterParams filters, int limit) {
        return grouped("domain", buildFilters(filters, true), Math.max(1, Math.min(200, limit)), "domain IS NOT NULL");
    }

    // Searches every site by name substring (not just the top ranks), so the whole
    // long tail is reachable. When root is true, groups subdomains into their registrable
    // domain (pt./en.wikipedia.org -> wikipedia.org). Ignores the active filters on purpose.
    public List<Facet> domainSearch(String search, int limit, boolean root) {
        String column = root ? "root_domain" : "domain";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", Math.max(1, Math.min(500, limit)));
        String where = column + " IS NOT NULL";
        if (search != null && !search.isBlank()) {
            where += " AND " + column + " LIKE :search";
            params.addValue("search", "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
        }
        return named.query(
                """
        SELECT %1$s as label, COUNT(*) as value
        FROM events
        WHERE %2$s
        GROUP BY %1$s
        ORDER BY value DESC, label ASC
        LIMIT :limit
        """
                        .formatted(column, where),
                params,
                (rs, rowNum) -> new Facet(rs.getString("label"), rs.getLong("value")));
    }

    public List<Facet> sourceRanking(FilterParams filters, int limit) {
        return grouped("source", buildFilters(filters, true), Math.max(1, Math.min(200, limit)), "");
    }

    public List<Facet> typeRanking(FilterParams filters, int limit) {
        return grouped("type", buildFilters(filters, true), Math.max(1, Math.min(200, limit)), "");
    }

    public List<Map<String, Object>> hourly(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "local_hour IS NOT NULL");
        return named.queryForList(
                """
        SELECT local_hour as hour, COUNT(*) as value
        FROM events
        WHERE %s
        GROUP BY hour
        ORDER BY hour ASC
        """
                        .formatted(where),
                built.params());
    }

    public List<Map<String, Object>> weekdays(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "local_weekday IS NOT NULL");
        return named.queryForList(
                """
        SELECT local_weekday as weekday, COUNT(*) as value
        FROM events
        WHERE %s
        GROUP BY weekday
        ORDER BY weekday ASC
        """
                        .formatted(where),
                built.params());
    }

    // Padrões & repetições — rotina, streaks, fases, revisitas e buscas recorrentes.
    // Todos honram os mesmos 7 filtros para poderem ser recortados por fonte/tipo/site/período.
    public Map<String, Object> patterns(FilterParams filters, int limit) {
        long started = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rhythm", rhythm(filters));
        out.put("streaks", streaks(filters));
        out.put("phases", phases(filters, 8));
        out.put("returns", topReturns(filters, limit));
        out.put("searches", recurringSearches(filters, limit));
        logSlow("patterns", started);
        return out;
    }

    // 7x24 activity matrix (weekday x hour). Returned sparse; the frontend fills the grid.
    private List<Map<String, Object>> rhythm(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "local_weekday IS NOT NULL AND local_hour IS NOT NULL");
        return named.queryForList(
                """
        SELECT local_weekday AS weekday, local_hour AS hour, COUNT(*) AS value
        FROM events
        WHERE %s
        GROUP BY weekday, hour
        """
                        .formatted(where),
                built.params());
    }

    // Longest runs of consecutive active days, computed in Java over the distinct local days.
    private Map<String, Object> streaks(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "local_day IS NOT NULL");
        List<String> days = named.query(
                "SELECT DISTINCT local_day FROM events WHERE " + where + " ORDER BY local_day ASC",
                built.params(),
                (rs, rowNum) -> rs.getString(1));

        List<Map<String, Object>> runs = new ArrayList<>();
        if (!days.isEmpty()) {
            LocalDate start = LocalDate.parse(days.get(0));
            LocalDate prev = start;
            for (int i = 1; i < days.size(); i++) {
                LocalDate day = LocalDate.parse(days.get(i));
                if (!day.equals(prev.plusDays(1))) {
                    runs.add(runMap(start, prev));
                    start = day;
                }
                prev = day;
            }
            runs.add(runMap(start, prev));
        }
        runs.sort((a, b) -> Integer.compare((int) b.get("length"), (int) a.get("length")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeDays", days.size());
        out.put("firstDay", days.isEmpty() ? null : days.get(0));
        out.put("lastDay", days.isEmpty() ? null : days.get(days.size() - 1));
        out.put("top", runs.size() > 5 ? new ArrayList<>(runs.subList(0, 5)) : runs);
        return out;
    }

    private Map<String, Object> runMap(LocalDate start, LocalDate end) {
        int length = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("length", length);
        run.put("start", start.toString());
        run.put("end", end.toString());
        return run;
    }

    // "Fases / obsessões": top registrable domains with their monthly series and the peak month,
    // so a contiguous burst reads as e.g. "jul–set/2023: fase Wikipedia".
    private List<Map<String, Object>> phases(FilterParams filters, int limit) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "root_domain IS NOT NULL AND year_month IS NOT NULL");
        int top = Math.max(1, Math.min(20, limit));

        MapSqlParameterSource topParams = copyParams(built.params()).addValue("limit", top);
        List<Map<String, Object>> tops = named.queryForList(
                "SELECT root_domain AS label, COUNT(*) AS total FROM events WHERE " + where
                        + " GROUP BY root_domain ORDER BY total DESC LIMIT :limit",
                topParams);
        if (tops.isEmpty()) return List.of();

        return monthlyPhases("root_domain", where, built.params(), tops);
    }

    // Shared by phases (Padrões) and channelPhases (YouTube): fetch each top label's monthly
    // series and derive first/last/peak stats. `column` is a trusted identifier, never user input.
    private List<Map<String, Object>> monthlyPhases(
            String column, String where, MapSqlParameterSource baseParams, List<Map<String, Object>> tops) {
        MapSqlParameterSource seriesParams = copyParams(baseParams);
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < tops.size(); i++) {
            seriesParams.addValue("lbl" + i, String.valueOf(tops.get(i).get("label")));
            placeholders.add(":lbl" + i);
        }
        List<Map<String, Object>> monthly = named.queryForList(
                "SELECT " + column + " AS label, year_month AS ym, COUNT(*) AS value FROM events WHERE " + where
                        + " AND " + column + " IN (" + String.join(", ", placeholders) + ")"
                        + " GROUP BY " + column + ", year_month ORDER BY year_month ASC",
                seriesParams);

        Map<String, List<Map<String, Object>>> series = new LinkedHashMap<>();
        for (Map<String, Object> row : monthly) {
            series.computeIfAbsent(String.valueOf(row.get("label")), key -> new ArrayList<>())
                    .add(Map.of("ym", row.get("ym"), "value", row.get("value")));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> entry : tops) {
            String label = String.valueOf(entry.get("label"));
            List<Map<String, Object>> points = series.getOrDefault(label, List.of());
            String first = null;
            String last = null;
            String peak = null;
            long peakValue = 0;
            for (Map<String, Object> point : points) {
                String ym = String.valueOf(point.get("ym"));
                long value = ((Number) point.get("value")).longValue();
                if (first == null) first = ym;
                last = ym;
                if (value > peakValue) {
                    peakValue = value;
                    peak = ym;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("total", entry.get("total"));
            row.put("first", first);
            row.put("last", last);
            row.put("peak", peak);
            row.put("peakValue", peakValue);
            row.put("months", points.size());
            row.put("series", points);
            out.add(row);
        }
        return out;
    }

    // Recurring searches: identical search titles repeated over time (grouped case-insensitively).
    private List<Map<String, Object>> recurringSearches(FilterParams filters, int limit) {
        BuiltFilter built = buildFilters(filters, true);
        String where = joinWhere(built.where(), "type = 'search' AND title IS NOT NULL");
        MapSqlParameterSource params = copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
        return named.queryForList(
                """
        SELECT MAX(title) AS title,
               COUNT(*) AS value,
               COUNT(DISTINCT local_day) AS days,
               MIN(local_day) AS firstDay,
               MAX(local_day) AS lastDay
        FROM events
        WHERE %s
        GROUP BY lower(title)
        HAVING value >= 2
        ORDER BY value DESC, days DESC
        LIMIT :limit
        """
                        .formatted(where),
                params);
    }

    // YouTube deep-dive. The ecosystem covers the YouTube source (My Activity, watch history,
    // comments, chats, posts...) plus browser visits to youtube.com, so a video's view count
    // merges Takeout activity with Chrome history hits on the same watch URL.
    private static final String YOUTUBE_SCOPE = "(source = 'YouTube' OR root_domain = 'youtube.com')";
    private static final String VIDEO_GUARD = "url LIKE '%watch?v=%'";
    // Canonical 11-char video id lifted straight from the watch URL; no extra column needed.
    private static final String VIDEO_ID = "substr(url, instr(url, 'watch?v=') + 8, 11)";

    public Map<String, Object> youtubeReport(FilterParams filters, int limit) {
        long started = System.nanoTime();
        BuiltFilter built = scopedFilter(buildFilters(filters, true), YOUTUBE_SCOPE);
        Map<String, Object> summary = named.queryForMap(
                """
        SELECT
          COUNT(*) as total,
          COUNT(DISTINCT CASE WHEN %s THEN %s END) as videos,
          COUNT(DISTINCT channel) as channels,
          SUM(CASE WHEN type = 'comment' THEN 1 ELSE 0 END) as comments,
          COUNT(DISTINCT local_day) as activeDays,
          MIN(timestamp) as firstSeen,
          MAX(timestamp) as lastSeen
        FROM events
        WHERE %s
        """
                        .formatted(VIDEO_GUARD, VIDEO_ID, built.where()),
                built.params());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("timeline", timeline(built));
        out.put("byType", grouped("type", built, 12, ""));
        out.put("topVideos", youtubeVideos(filters, "", limit));
        out.put("topChannels", youtubeTopChannels(built, limit));
        out.put("channelPhases", youtubeChannelPhases(built, 10));
        logSlow("youtube", started);
        return out;
    }

    // Videos grouped by watch id — "quantas vezes vi, primeira e última vez". The optional
    // search matches the video title or the channel name.
    public List<Map<String, Object>> youtubeVideos(FilterParams filters, String search, int limit) {
        // Ads impressions share watch URLs and would otherwise dominate the "most watched" list.
        BuiltFilter built = scopedFilter(
                buildFilters(filters, true),
                YOUTUBE_SCOPE + " AND " + VIDEO_GUARD + " AND raw_json NOT LIKE '%From Google Ads%'");
        String where = built.where();
        MapSqlParameterSource params = copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
        if (search != null && !search.isBlank()) {
            where += " AND (title LIKE :video_search OR channel LIKE :video_search)";
            params.addValue("video_search", "%" + search.trim() + "%");
        }
        // Watch events carry the real video title; comments/chats only have a synthetic
        // "Comentário em vídeo <id>" one, so prefer the former when both exist. "value" counts
        // only watch/visit moments — a livestream where you typed 200 chat messages is 200
        // interactions, not 200 views. DISTINCT timestamp collapses the same watch exported
        // into overlapping files (My Activity HTML/JSON + watch history).
        return named.queryForList(
                """
        SELECT %s as videoId,
               COALESCE(MAX(CASE WHEN type NOT IN ('comment', 'chat', 'message') THEN title END), MAX(title)) as title,
               MAX(channel) as channel,
               %s as value,
               SUM(CASE WHEN type IN ('comment', 'chat', 'message', 'post') THEN 1 ELSE 0 END) as interactions,
               COUNT(DISTINCT local_day) as days,
               MIN(local_day) as firstDay,
               MAX(local_day) as lastDay,
               MIN(timestamp) as firstSeen
        FROM events
        WHERE %s
        GROUP BY videoId
        ORDER BY value DESC, interactions DESC, lastDay DESC
        LIMIT :limit
        """
                        .formatted(VIDEO_ID, VIEW_COUNT, where),
                params);
    }

    // "How many times you actually watched/opened it": non-interaction events, deduplicated
    // by second-truncated timestamp so the same record found in overlapping Takeout files
    // counts once (the JSON format carries milliseconds, the HTML one doesn't).
    private static final String VIEW_COUNT =
            "COUNT(DISTINCT CASE WHEN type NOT IN ('comment', 'chat', 'message', 'post') THEN COALESCE(substr(timestamp, 1, 19), 'row:' || id) END)";

    // Everything recorded about one video: every encounter in order (the first row answers
    // "qual foi a primeira vez"), plus per-year and per-type breakdowns.
    public Map<String, Object> youtubeVideo(String videoId) {
        String clean = videoId == null ? "" : videoId.trim();
        if (clean.isEmpty() || clean.length() > 20 || !clean.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("ID de vídeo inválido.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource("pattern", "%watch?v=" + clean + "%");
        // The indexed scope narrows the LIKE scan to YouTube rows (a full-table LIKE takes ~1s).
        String where = YOUTUBE_SCOPE + " AND url LIKE :pattern";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", clean);
        out.put(
                "summary",
                named.queryForMap(
                        """
        SELECT COUNT(*) as total,
               %s as views,
               SUM(CASE WHEN type IN ('comment', 'chat', 'message', 'post') THEN 1 ELSE 0 END) as interactions,
               COUNT(DISTINCT local_day) as days,
               COALESCE(MAX(CASE WHEN type NOT IN ('comment', 'chat', 'message') THEN title END), MAX(title)) as title,
               MAX(channel) as channel,
               MIN(timestamp) as firstSeen,
               MAX(timestamp) as lastSeen,
               MIN(local_day) as firstDay,
               MAX(local_day) as lastDay
        FROM events
        WHERE %s
        """
                                .formatted(VIEW_COUNT, where),
                        params));
        out.put(
                "byYear",
                named.queryForList(
                        """
        SELECT substr(local_day, 1, 4) as year, COUNT(*) as value
        FROM events
        WHERE %s AND local_day IS NOT NULL
        GROUP BY year
        ORDER BY year ASC
        """
                                .formatted(where),
                        params));
        out.put(
                "byType",
                named.queryForList(
                        """
        SELECT type as label, COUNT(*) as value
        FROM events
        WHERE %s
        GROUP BY type
        ORDER BY value DESC
        """
                                .formatted(where),
                        params));
        out.put(
                "events",
                named.queryForList(
                        """
        SELECT %s, channel FROM events
        WHERE %s
        ORDER BY COALESCE(timestamp, '') ASC, id ASC
        LIMIT 500
        """
                                .formatted(RECALL_COLUMNS, where),
                        params));
        return out;
    }

    // One channel's story: monthly rhythm, most-watched videos, first/last contact.
    public Map<String, Object> youtubeChannel(FilterParams filters, String channel, int limit) {
        BuiltFilter base = scopedFilter(buildFilters(filters, true), YOUTUBE_SCOPE + " AND channel = :yt_channel");
        MapSqlParameterSource params = copyParams(base.params()).addValue("yt_channel", channel);
        BuiltFilter built = new BuiltFilter(base.where(), params);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", channel);
        out.put(
                "summary",
                named.queryForMap(
                        """
        SELECT COUNT(*) as total,
               COUNT(DISTINCT CASE WHEN %s THEN %s END) as videos,
               COUNT(DISTINCT local_day) as days,
               MIN(timestamp) as firstSeen,
               MAX(timestamp) as lastSeen
        FROM events
        WHERE %s
        """
                                .formatted(VIDEO_GUARD, VIDEO_ID, built.where()),
                        built.params()));
        out.put("timeline", timeline(built));
        MapSqlParameterSource videoParams =
                copyParams(built.params()).addValue("limit", Math.max(1, Math.min(100, limit)));
        out.put(
                "topVideos",
                named.queryForList(
                        """
        SELECT %s as videoId,
               COALESCE(MAX(CASE WHEN type NOT IN ('comment', 'chat', 'message') THEN title END), MAX(title)) as title,
               %s as value,
               SUM(CASE WHEN type IN ('comment', 'chat', 'message', 'post') THEN 1 ELSE 0 END) as interactions,
               MIN(local_day) as firstDay,
               MAX(local_day) as lastDay
        FROM events
        WHERE %s AND %s
        GROUP BY videoId
        ORDER BY value DESC, interactions DESC
        LIMIT :limit
        """
                                .formatted(VIDEO_ID, VIEW_COUNT, built.where(), VIDEO_GUARD),
                        videoParams));
        return out;
    }

    private List<Map<String, Object>> youtubeTopChannels(BuiltFilter built, int limit) {
        String where = joinWhere(built.where(), "channel IS NOT NULL");
        MapSqlParameterSource params = copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
        return named.queryForList(
                """
        SELECT channel as channel,
               COUNT(*) as value,
               COUNT(DISTINCT CASE WHEN %s THEN %s END) as videos,
               COUNT(DISTINCT local_day) as days,
               COUNT(DISTINCT year_month) as months,
               MIN(local_day) as firstDay,
               MAX(local_day) as lastDay
        FROM events
        WHERE %s
        GROUP BY channel
        ORDER BY value DESC
        LIMIT :limit
        """
                        .formatted(VIDEO_GUARD, VIDEO_ID, where),
                params);
    }

    // Channel trajectory over the years, in the same shape as `phases`: monthly series with
    // first/last/peak plus a status classifying whether you still follow the channel. "Last
    // month of data" (not today) anchors the comparison so an old export still reads sensibly.
    private List<Map<String, Object>> youtubeChannelPhases(BuiltFilter built, int limit) {
        String where = joinWhere(built.where(), "channel IS NOT NULL AND year_month IS NOT NULL");
        MapSqlParameterSource topParams =
                copyParams(built.params()).addValue("limit", Math.max(1, Math.min(20, limit)));
        List<Map<String, Object>> tops = named.queryForList(
                "SELECT channel AS label, COUNT(*) AS total FROM events WHERE " + where
                        + " GROUP BY channel ORDER BY total DESC LIMIT :limit",
                topParams);
        if (tops.isEmpty()) return List.of();
        String latestMonth =
                named.queryForObject("SELECT MAX(year_month) FROM events WHERE " + where, built.params(), String.class);
        // The global top is dominated by long-running channels, so channels you dropped years
        // ago would never surface. Mix in the biggest channels whose activity stopped well
        // before the end of the data — those are the "acompanhei e abandonei" stories.
        if (latestMonth != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Map<String, Object> top : tops) seen.add(String.valueOf(top.get("label")));
            MapSqlParameterSource droppedParams = copyParams(built.params())
                    .addValue("cutoff", shiftMonth(latestMonth, -6))
                    .addValue("limit", Math.max(1, Math.min(20, limit)));
            List<Map<String, Object>> dropped = named.queryForList(
                    "SELECT channel AS label, COUNT(*) AS total FROM events WHERE " + where
                            + " GROUP BY channel HAVING MAX(year_month) < :cutoff ORDER BY total DESC LIMIT :limit",
                    droppedParams);
            for (Map<String, Object> row : dropped) {
                if (seen.add(String.valueOf(row.get("label")))) tops.add(row);
            }
        }

        List<Map<String, Object>> out = monthlyPhases("channel", where, built.params(), tops);
        for (Map<String, Object> row : out) {
            row.put("status", channelStatus((String) row.get("first"), (String) row.get("last"), latestMonth));
        }
        return out;
    }

    private String channelStatus(String first, String last, String latestMonth) {
        if (first == null || last == null || latestMonth == null) return "ativo";
        int gapFromEnd = monthsBetween(last, latestMonth);
        if (gapFromEnd > 6) return "abandonado";
        if (monthsBetween(first, latestMonth) <= 6) return "novo";
        return "ativo";
    }

    // "yyyy-MM" plus a (possibly negative) number of months.
    private String shiftMonth(String ym, int months) {
        try {
            String[] parts = ym.split("-");
            int total = Integer.parseInt(parts[0]) * 12 + Integer.parseInt(parts[1]) - 1 + months;
            return String.format(Locale.ROOT, "%04d-%02d", total / 12, total % 12 + 1);
        } catch (Exception ignored) {
            return ym;
        }
    }

    private int monthsBetween(String fromYm, String toYm) {
        try {
            String[] from = fromYm.split("-");
            String[] to = toYm.split("-");
            return (Integer.parseInt(to[0]) - Integer.parseInt(from[0])) * 12
                    + Integer.parseInt(to[1])
                    - Integer.parseInt(from[1]);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private BuiltFilter scopedFilter(BuiltFilter built, String scope) {
        return new BuiltFilter(joinWhere(built.where(), scope), built.params());
    }

    // Assuntos: user-defined interests (keywords matched via FTS across every source), so a
    // theme like "mangá & anime" reads across searches, videos, articles and comments at once.
    public List<Map<String, Object>> topics() {
        return jdbc.queryForList("SELECT * FROM topics ORDER BY name ASC");
    }

    public Map<String, Object> createTopic(Map<String, Object> payload) {
        // RETURNING avoids the pooled-connection last_insert_rowid() hazard (see createCollection).
        return jdbc.queryForMap(
                """
        INSERT INTO topics (name, color, keywords, created_at)
        VALUES (?, ?, ?, ?)
        RETURNING *
        """,
                text(payload, "name", "Assunto"),
                text(payload, "color", "#a78bfa"),
                text(payload, "keywords", ""),
                Instant.now().toString());
    }

    public Map<String, Object> updateTopic(long id, Map<String, Object> payload) {
        jdbc.update(
                "UPDATE topics SET name = ?, color = ?, keywords = ? WHERE id = ?",
                text(payload, "name", "Assunto"),
                text(payload, "color", "#a78bfa"),
                text(payload, "keywords", ""),
                id);
        return jdbc.queryForMap("SELECT * FROM topics WHERE id = ?", id);
    }

    public void deleteTopic(long id) {
        jdbc.update("DELETE FROM topics WHERE id = ?", id);
    }

    public Map<String, Object> topicReport(String keywords, FilterParams filters, int limit) {
        long started = System.nanoTime();
        String fts = topicFtsQuery(keywords);
        if (fts.isEmpty()) throw new IllegalArgumentException("Informe ao menos uma palavra-chave.");
        BuiltFilter base = buildFilters(filters, true);
        MapSqlParameterSource params = copyParams(base.params()).addValue("topic_fts", fts);
        BuiltFilter built = new BuiltFilter(
                joinWhere(base.where(), "id IN (SELECT rowid FROM events_fts WHERE events_fts MATCH :topic_fts)"),
                params);

        int safeLimit = Math.max(1, Math.min(100, limit));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keywords", keywords);
        out.put(
                "summary",
                named.queryForMap(
                        """
        SELECT COUNT(*) as total,
               COUNT(DISTINCT local_day) as activeDays,
               COUNT(DISTINCT root_domain) as domains,
               MIN(timestamp) as firstSeen,
               MAX(timestamp) as lastSeen
        FROM events
        WHERE %s
        """
                                .formatted(built.where()),
                        built.params()));
        out.put("timeline", timeline(built));
        out.put("bySource", grouped("source", built, 12, ""));
        out.put("byType", grouped("type", built, 12, ""));
        out.put("topDomains", grouped("root_domain", built, safeLimit, "root_domain IS NOT NULL"));
        MapSqlParameterSource pageParams = copyParams(built.params()).addValue("limit", safeLimit);
        out.put(
                "topPages",
                named.queryForList(
                        """
        SELECT url as url,
               MAX(title) as title,
               COUNT(*) as value,
               COUNT(DISTINCT local_day) as days,
               MIN(local_day) as firstDay,
               MAX(local_day) as lastDay
        FROM events
        WHERE %s
        GROUP BY url
        ORDER BY value DESC
        LIMIT :limit
        """
                                .formatted(joinWhere(built.where(), "url IS NOT NULL")),
                        pageParams));
        out.put(
                "topChannels",
                named.queryForList(
                        """
        SELECT channel as channel, COUNT(*) as value, MIN(local_day) as firstDay, MAX(local_day) as lastDay
        FROM events
        WHERE %s
        GROUP BY channel
        ORDER BY value DESC
        LIMIT :limit
        """
                                .formatted(joinWhere(built.where(), "channel IS NOT NULL")),
                        pageParams));
        out.put(
                "searches",
                named.queryForList(
                        """
        SELECT MAX(title) as title, COUNT(*) as value, MIN(local_day) as firstDay, MAX(local_day) as lastDay
        FROM events
        WHERE %s
        GROUP BY lower(title)
        ORDER BY value DESC
        LIMIT :limit
        """
                                .formatted(joinWhere(built.where(), "type = 'search' AND title IS NOT NULL")),
                        pageParams));
        out.put(
                "recent",
                named.queryForList(
                        """
        SELECT %s
        FROM events
        WHERE %s
        ORDER BY COALESCE(timestamp, '') DESC
        LIMIT 30
        """
                                .formatted(RECALL_COLUMNS, built.where()),
                        built.params()));
        logSlow("topicReport", started);
        return out;
    }

    // Turns a comma/newline-separated keyword list into an FTS5 OR query. Each keyword becomes
    // a quoted phrase with prefix matching ("one piece"* / "manga"*), so multi-word themes and
    // plural/diacritic variants (mangá/mangás) all match via the unicode61 tokenizer.
    private String topicFtsQuery(String keywords) {
        List<String> parts = new ArrayList<>();
        for (String raw : String.valueOf(keywords).split("[,;\\n]+")) {
            String clean = raw.replace("\"", " ").replaceAll("\\s+", " ").trim();
            if (!clean.isEmpty()) parts.add("\"" + clean + "\"*");
        }
        return String.join(" OR ", parts);
    }

    public List<Map<String, Object>> quality(FilterParams filters) {
        BuiltFilter built = buildFilters(filters, true);
        String where = built.where().isBlank() ? "" : "WHERE " + built.where();
        return named.queryForList(
                """
        SELECT
          source,
          COUNT(*) as total,
          SUM(CASE WHEN timestamp IS NOT NULL THEN 1 ELSE 0 END) as withTimestamp,
          SUM(CASE WHEN timestamp IS NULL THEN 1 ELSE 0 END) as withoutTimestamp,
          ROUND((SUM(CASE WHEN timestamp IS NOT NULL THEN 1 ELSE 0 END) * 100.0) / COUNT(*), 2) as timestampCoverage,
          COUNT(DISTINCT file_path) as files
        FROM events
        %s
        GROUP BY source
        ORDER BY withoutTimestamp DESC, total DESC
        LIMIT 200
        """
                        .formatted(where),
                built.params());
    }

    public Map<String, Object> search(FilterParams filters, int limit, int offset) {
        long started = System.nanoTime();
        List<Map<String, Object>> rows = events(filters, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("limit", limit);
        out.put("offset", offset);
        out.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0);
        return out;
    }

    public Map<String, Object> backfillTimestamps(int limit) {
        int safeLimit = Math.max(1, Math.min(100_000, limit));
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
        SELECT id, text, title, raw_json
        FROM events
        WHERE timestamp IS NULL
        LIMIT ?
        """,
                safeLimit);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String timestamp = recoverTimestamp(
                    String.valueOf(row.getOrDefault("text", "")) + " " + String.valueOf(row.getOrDefault("title", ""))
                            + " " + String.valueOf(row.getOrDefault("raw_json", "")));
            if (timestamp == null) continue;
            ZonedDateTime local = localTime(timestamp);
            jdbc.update(
                    "UPDATE events SET timestamp = ?, year_month = ?, local_day = ?, local_hour = ?, local_weekday = ? WHERE id = ?",
                    timestamp,
                    local == null ? null : local.format(LOCAL_MONTH),
                    local == null ? null : local.format(LOCAL_DAY),
                    local == null ? null : local.getHour(),
                    local == null ? null : local.getDayOfWeek().getValue() % 7,
                    row.get("id"));
            updated++;
        }
        if (updated > 0) jdbc.execute("PRAGMA optimize");
        return Map.of("scanned", rows.size(), "updated", updated);
    }

    public Map<String, Object> cleanupMetadataFragments() {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        Long candidates = jdbc.queryForObject(
                """
        SELECT COUNT(*)
        FROM events
        WHERE timestamp IS NULL
          AND file_path LIKE '%Minhaatividade.html'
          AND (text LIKE '%Por que isso%' OR text LIKE '%Why this%')
          AND (text LIKE '%Produtos:%' OR text LIKE '%Products:%')
        """,
                Long.class);
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
          DELETE FROM events
          WHERE timestamp IS NULL
            AND file_path LIKE '%Minhaatividade.html'
            AND (text LIKE '%Por que isso%' OR text LIKE '%Why this%')
            AND (text LIKE '%Produtos:%' OR text LIKE '%Products:%')
          """);
            jdbc.execute("INSERT INTO events_fts(events_fts) VALUES('rebuild')");
            jdbc.update(
                    "UPDATE imports SET event_count = (SELECT COUNT(*) FROM events) WHERE id = (SELECT MAX(id) FROM imports)");
        });
        jdbc.execute("PRAGMA optimize");
        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        return Map.of(
                "before", before == null ? 0 : before,
                "deleted", candidates == null ? 0 : candidates,
                "after", after == null ? 0 : after);
    }

    public Map<String, Object> cleanupUndatedHtmlFragments() {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        Long candidates = jdbc.queryForObject(
                """
        SELECT COUNT(*)
        FROM events
        WHERE timestamp IS NULL
          AND file_path LIKE '%Minhaatividade.html'
        """,
                Long.class);
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
          DELETE FROM events
          WHERE timestamp IS NULL
            AND file_path LIKE '%Minhaatividade.html'
          """);
            jdbc.execute("INSERT INTO events_fts(events_fts) VALUES('rebuild')");
            jdbc.update(
                    "UPDATE imports SET event_count = (SELECT COUNT(*) FROM events) WHERE id = (SELECT MAX(id) FROM imports)");
        });
        jdbc.execute("PRAGMA optimize");
        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        return Map.of(
                "before", before == null ? 0 : before,
                "deleted", candidates == null ? 0 : candidates,
                "after", after == null ? 0 : after);
    }

    // Old Takeouts export My Activity as HTML, newer ones as JSON; the same record then
    // exists twice with different raw_json (so different event_keys). This removes the
    // HTML-format row when a richer twin exists at the same second with the same
    // source/type/url, after moving its annotations onto the surviving row.
    public Map<String, Object> cleanupFormatDuplicates() {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        transactions.executeWithoutResult(status -> {
            jdbc.execute("DROP TABLE IF EXISTS temp.format_twin");
            jdbc.execute(
                    """
          CREATE TEMP TABLE format_twin AS
          SELECT MIN(id) AS keep_id, source, type, substr(timestamp, 1, 19) AS second, url
          FROM events
          WHERE raw_json NOT LIKE '{"htmlText%' AND timestamp IS NOT NULL AND url IS NOT NULL
          GROUP BY source, type, second, url
          """);
            jdbc.execute("CREATE INDEX temp.idx_format_twin ON format_twin(source, type, second, url)");
            jdbc.execute("DROP TABLE IF EXISTS temp.format_dup");
            jdbc.execute(
                    """
          CREATE TEMP TABLE format_dup AS
          SELECT h.id AS old_id, twin.keep_id AS keep_id
          FROM events h
          JOIN format_twin twin
            ON twin.source = h.source AND twin.type = h.type
           AND twin.second = substr(h.timestamp, 1, 19) AND twin.url = h.url
          WHERE h.raw_json LIKE '{"htmlText%' AND h.timestamp IS NOT NULL AND h.url IS NOT NULL
          """);
            jdbc.update(
                    """
          INSERT OR IGNORE INTO event_tags(event_id, tag_id)
          SELECT dup.keep_id, tagged.tag_id
          FROM event_tags tagged JOIN format_dup dup ON dup.old_id = tagged.event_id
          """);
            jdbc.update(
                    """
          INSERT OR IGNORE INTO collection_events(collection_id, event_id)
          SELECT collected.collection_id, dup.keep_id
          FROM collection_events collected JOIN format_dup dup ON dup.old_id = collected.event_id
          """);
            jdbc.update(
                    """
          INSERT OR IGNORE INTO event_notes(event_id, note, updated_at)
          SELECT dup.keep_id, notes.note, notes.updated_at
          FROM event_notes notes JOIN format_dup dup ON dup.old_id = notes.event_id
          """);
            jdbc.update("DELETE FROM events WHERE id IN (SELECT old_id FROM format_dup)");
            jdbc.update("DELETE FROM event_tags WHERE event_id NOT IN (SELECT id FROM events)");
            jdbc.update("DELETE FROM collection_events WHERE event_id NOT IN (SELECT id FROM events)");
            jdbc.update("DELETE FROM event_notes WHERE event_id NOT IN (SELECT id FROM events)");
            jdbc.execute("INSERT INTO events_fts(events_fts) VALUES('rebuild')");
            jdbc.update(
                    "UPDATE imports SET event_count = (SELECT COUNT(*) FROM events) WHERE id = (SELECT MAX(id) FROM imports)");
        });
        jdbc.execute("PRAGMA optimize");
        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        return Map.of(
                "before", before == null ? 0 : before,
                "deleted", (before == null ? 0 : before) - (after == null ? 0 : after),
                "after", after == null ? 0 : after);
    }

    public Map<String, Object> compare(FilterParams left, FilterParams right) {
        MetricsResponse leftMetrics = metrics(left);
        MetricsResponse rightMetrics = metrics(right);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("left", leftMetrics);
        out.put("right", rightMetrics);
        long leftTotal = ((Number) leftMetrics.summary().get("total")).longValue();
        long rightTotal = ((Number) rightMetrics.summary().get("total")).longValue();
        out.put("deltaTotal", leftTotal - rightTotal);
        out.put("deltaPercent", rightTotal == 0 ? null : ((leftTotal - rightTotal) * 100.0) / rightTotal);
        return out;
    }

    public List<Map<String, Object>> events(FilterParams filters, int limit, int offset) {
        long started = System.nanoTime();
        QueryParts query = parseQuery(filters.q());
        BuiltFilter built = buildFilters(filters, false, query.text().isBlank() ? "" : "events.");
        MapSqlParameterSource params = built.params().addValue("limit", limit).addValue("offset", offset);

        if (!query.text().isBlank()) {
            params.addValue("fts", toFtsQuery(query.text()));
            String extra = built.where().isBlank() ? "" : " AND " + built.where();
            List<Map<String, Object>> rows = named.queryForList(
                    """
          SELECT
            events.*,
            snippet(events_fts, 1, '<mark>', '</mark>', '...', 18) as snippet,
            bm25(events_fts) as rank
          FROM events_fts
          JOIN events ON events.id = events_fts.rowid
          WHERE events_fts MATCH :fts
          """
                            + extra + "\n"
                            + """
          ORDER BY rank ASC, COALESCE(timestamp, '') DESC
          LIMIT :limit OFFSET :offset
          """,
                    params);
            logSlow("events.fts", started);
            return rows;
        }

        String where = built.where().isBlank() ? "" : " WHERE " + built.where();
        List<Map<String, Object>> rows = named.queryForList(
                """
        SELECT *
        FROM events
        """ + where + "\n"
                        + """
        ORDER BY COALESCE(timestamp, '') DESC
        LIMIT :limit OFFSET :offset
        """,
                params);
        logSlow("events", started);
        return rows;
    }

    public List<Map<String, Object>> savedFilters() {
        return jdbc.queryForList("SELECT * FROM saved_filters ORDER BY created_at DESC");
    }

    public Map<String, Object> saveFilter(Map<String, Object> payload) {
        // RETURNING avoids the pooled-connection last_insert_rowid() hazard (see createCollection).
        return jdbc.queryForMap(
                """
        INSERT INTO saved_filters (name, query, source, type, domain, from_date, to_date, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING *
        """,
                text(payload, "name", "Filtro salvo"),
                text(payload, "q", ""),
                text(payload, "source", ""),
                text(payload, "type", ""),
                text(payload, "domain", ""),
                text(payload, "from", ""),
                text(payload, "to", ""),
                Instant.now().toString());
    }

    public void deleteSavedFilter(long id) {
        jdbc.update("DELETE FROM saved_filters WHERE id = ?", id);
    }

    public List<Map<String, Object>> tags() {
        return jdbc.queryForList("SELECT * FROM tags ORDER BY name ASC");
    }

    public Map<String, Object> createTag(Map<String, Object> payload) {
        jdbc.update(
                "INSERT OR IGNORE INTO tags (name, color) VALUES (?, ?)",
                text(payload, "name", "tag"),
                text(payload, "color", "#2f7d7e"));
        return jdbc.queryForMap("SELECT * FROM tags WHERE name = ?", text(payload, "name", "tag"));
    }

    public void tagEvent(long eventId, long tagId) {
        jdbc.update("INSERT OR IGNORE INTO event_tags (event_id, tag_id) VALUES (?, ?)", eventId, tagId);
    }

    public void untagEvent(long eventId, long tagId) {
        jdbc.update("DELETE FROM event_tags WHERE event_id = ? AND tag_id = ?", eventId, tagId);
    }

    // Tags currently applied to one event, so the panel can render its chips as selected.
    public List<Map<String, Object>> tagsForEvent(long eventId) {
        return jdbc.queryForList(
                """
        SELECT t.id, t.name, t.color
        FROM tags t JOIN event_tags et ON et.tag_id = t.id
        WHERE et.event_id = ?
        ORDER BY t.name ASC
        """,
                eventId);
    }

    public List<Map<String, Object>> eventsByTag(long tagId, int limit) {
        return named.queryForList(
                """
        SELECT %s
        FROM events e JOIN event_tags et ON et.event_id = e.id
        WHERE et.tag_id = :tagId
        ORDER BY COALESCE(e.timestamp, '') DESC
        LIMIT :limit
        """
                        .formatted(recallColumns("e")),
                new MapSqlParameterSource("tagId", tagId).addValue("limit", Math.max(1, Math.min(2000, limit))));
    }

    public List<Map<String, Object>> collections() {
        return jdbc.queryForList("SELECT * FROM collections ORDER BY created_at DESC");
    }

    public Map<String, Object> createCollection(Map<String, Object> payload) {
        // RETURNING keeps insert+read on one statement/connection; last_insert_rowid() across
        // pooled connections can return 0 and 500 (collection name isn't unique to re-select by).
        return jdbc.queryForMap(
                "INSERT INTO collections (name, description, created_at) VALUES (?, ?, ?) RETURNING *",
                text(payload, "name", "Coleção"),
                text(payload, "description", ""),
                Instant.now().toString());
    }

    public void addToCollection(long collectionId, long eventId) {
        jdbc.update(
                "INSERT OR IGNORE INTO collection_events (collection_id, event_id) VALUES (?, ?)",
                collectionId,
                eventId);
    }

    public void removeFromCollection(long collectionId, long eventId) {
        jdbc.update("DELETE FROM collection_events WHERE collection_id = ? AND event_id = ?", collectionId, eventId);
    }

    public List<Map<String, Object>> collectionEvents(long collectionId, int limit) {
        return named.queryForList(
                """
        SELECT %s
        FROM events e JOIN collection_events ce ON ce.event_id = e.id
        WHERE ce.collection_id = :collectionId
        ORDER BY COALESCE(e.timestamp, '') DESC
        LIMIT :limit
        """
                        .formatted(recallColumns("e")),
                new MapSqlParameterSource("collectionId", collectionId)
                        .addValue("limit", Math.max(1, Math.min(2000, limit))));
    }

    // Prefixes the lean recall projection with a table alias for JOIN queries.
    private static String recallColumns(String alias) {
        String[] columns = RECALL_COLUMNS.split(", ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) out.append(", ");
            out.append(alias).append('.').append(columns[i]);
        }
        return out.toString();
    }

    public Map<String, Object> note(long eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM event_notes WHERE event_id = ?", eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> saveNote(long eventId, Map<String, Object> payload) {
        jdbc.update(
                """
        INSERT INTO event_notes (event_id, note, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(event_id) DO UPDATE SET note = excluded.note, updated_at = excluded.updated_at
        """,
                eventId,
                text(payload, "note", ""),
                Instant.now().toString());
        return note(eventId);
    }

    private List<Facet> grouped(String expression, BuiltFilter built, int limit, String extra) {
        String where = joinWhere(built.where(), extra);
        MapSqlParameterSource params = copyParams(built.params()).addValue("limit", limit);
        return named.query(
                """
        SELECT %s as label, COUNT(*) as value
        FROM events
        %s
        GROUP BY %s
        ORDER BY value DESC, label ASC
        LIMIT :limit
        """
                        .formatted(expression, where.isBlank() ? "" : "WHERE " + where, expression),
                params,
                (rs, rowNum) -> new Facet(rs.getString("label"), rs.getLong("value")));
    }

    private List<Facet> timeline(BuiltFilter built) {
        String where = joinWhere(built.where(), "year_month IS NOT NULL");
        return named.query(
                """
        SELECT year_month as label, COUNT(*) as value
        FROM events
        WHERE %s
        GROUP BY year_month
        ORDER BY year_month ASC
        """
                        .formatted(where),
                built.params(),
                (rs, rowNum) -> new Facet(rs.getString("label"), rs.getLong("value")));
    }

    private BuiltFilter buildFilters(FilterParams filters, boolean includeQuery) {
        return buildFilters(filters, includeQuery, "");
    }

    private BuiltFilter buildFilters(FilterParams filters, boolean includeQuery, String qualifier) {
        QueryParts query = parseQuery(filters.q());
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        String columnPrefix = qualifier == null ? "" : qualifier;

        String from = filters.from().isBlank() ? query.after() : filters.from();
        String to = filters.to().isBlank() ? query.before() : filters.to();
        String domain = filters.domain().isBlank() ? query.site() : filters.domain();
        String type = filters.type().isBlank() ? query.type() : filters.type();
        String source = filters.source().isBlank() ? query.source() : filters.source();

        if (!from.isBlank()) {
            clauses.add(columnPrefix + "local_day >= :from");
            params.addValue("from", LocalDate.parse(from).toString());
        }
        if (!to.isBlank()) {
            clauses.add(columnPrefix + "local_day <= :to");
            params.addValue("to", LocalDate.parse(to).toString());
        }
        addMultiFilter(clauses, params, columnPrefix + "source", "source", source);
        addMultiFilter(clauses, params, columnPrefix + "type", "type", type);
        addMultiFilter(clauses, params, columnPrefix + "domain", "domain", domain);
        if (!filters.product().isBlank()) {
            clauses.add(columnPrefix + "source = :product");
            params.addValue("product", filters.product());
        }
        if (query.hasUrl()) clauses.add(columnPrefix + "url IS NOT NULL");
        if (query.hasTime()) clauses.add(columnPrefix + "timestamp IS NOT NULL");
        if (query.missingTime()) clauses.add(columnPrefix + "timestamp IS NULL");
        if (!query.path().isBlank()) {
            clauses.add(columnPrefix + "file_path LIKE :path");
            params.addValue("path", "%" + query.path() + "%");
        }
        if (!query.title().isBlank()) {
            clauses.add(columnPrefix + "title LIKE :title");
            params.addValue("title", "%" + query.title() + "%");
        }
        if (!query.body().isBlank()) {
            clauses.add(columnPrefix + "text LIKE :body");
            params.addValue("body", "%" + query.body() + "%");
        }
        int excludedTextIndex = 0;
        for (String excluded : query.excludedText()) {
            String param = "excluded_text_" + excludedTextIndex++;
            clauses.add(
                    """
          lower(coalesce(%1$stitle, '') || ' ' || coalesce(%1$stext, '') || ' ' || coalesce(%1$surl, '') || ' ' || coalesce(%1$sdomain, '') || ' ' || coalesce(%1$ssource, '') || ' ' || coalesce(%1$stype, '') || ' ' || coalesce(%1$sfile_path, '')) NOT LIKE :%2$s
          """
                            .formatted(columnPrefix, param)
                            .trim());
            params.addValue(param, "%" + excluded.toLowerCase(Locale.ROOT) + "%");
        }
        if (includeQuery && !query.text().isBlank()) {
            clauses.add(columnPrefix + "id IN (SELECT rowid FROM events_fts WHERE events_fts MATCH :fts)");
            params.addValue("fts", toFtsQuery(query.text()));
        }

        return new BuiltFilter(String.join(" AND ", clauses), params);
    }

    private QueryParts parseQuery(String value) {
        String site = "";
        String type = "";
        String source = "";
        String before = "";
        String after = "";
        String path = "";
        String title = "";
        String body = "";
        boolean hasUrl = false;
        boolean hasTime = false;
        boolean missingTime = false;
        List<String> text = new ArrayList<>();
        List<String> excludedText = new ArrayList<>();
        for (String token : String.valueOf(value).split("\\s+")) {
            if (token.startsWith("site:")) site = token.substring(5);
            else if (token.startsWith("type:")) type = token.substring(5);
            else if (token.startsWith("source:")) source = token.substring(7);
            else if (token.startsWith("before:")) before = token.substring(7);
            else if (token.startsWith("after:")) after = token.substring(6);
            else if (token.startsWith("path:")) path = token.substring(5);
            else if (token.startsWith("title:")) title = token.substring(6);
            else if (token.startsWith("text:")) body = token.substring(5);
            else if (token.equals("has:url")) hasUrl = true;
            else if (token.equals("has:time")) hasTime = true;
            else if (token.equals("missing:time")) missingTime = true;
            else if (token.startsWith("-") && token.length() > 1) excludedText.add(token.substring(1));
            else if (!token.isBlank()) text.add(token);
        }
        return new QueryParts(
                String.join(" ", text),
                excludedText,
                site,
                type,
                source,
                before,
                after,
                path,
                title,
                body,
                hasUrl,
                hasTime,
                missingTime);
    }

    private void addMultiFilter(
            List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (value == null || value.isBlank()) return;
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        int index = 0;
        for (String raw : value.split(",")) {
            String item = raw.trim();
            if (item.isBlank()) continue;
            boolean negative = item.startsWith("-");
            String clean = negative ? item.substring(1).trim() : item;
            if (clean.isBlank()) continue;
            String param = key + "_" + index++;
            params.addValue(param, clean);
            if (negative) exclude.add(":" + param);
            else include.add(":" + param);
        }
        if (!include.isEmpty()) clauses.add(column + " IN (" + String.join(", ", include) + ")");
        if (!exclude.isEmpty())
            clauses.add("(" + column + " IS NULL OR " + column + " NOT IN (" + String.join(", ", exclude) + "))");
    }

    private MapSqlParameterSource copyParams(MapSqlParameterSource source) {
        MapSqlParameterSource copy = new MapSqlParameterSource();
        for (String name : source.getParameterNames()) {
            copy.addValue(name, source.getValue(name));
        }
        return copy;
    }

    private String joinWhere(String left, String right) {
        if (left == null || left.isBlank()) return right == null ? "" : right;
        if (right == null || right.isBlank()) return left;
        return left + " AND " + right;
    }

    private String toFtsQuery(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.replace("\"", "").replace("'", "").split("\\s+")) {
            for (String cleaned :
                    part.replaceAll("[^\\p{L}\\p{N}_]+", " ").trim().split("\\s+")) {
                if (!cleaned.isBlank()) parts.add(cleaned + "*");
            }
        }
        return parts.isEmpty() ? "\"\"" : String.join(" ", parts);
    }

    private String recoverTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher iso = ISO_IN_TEXT.matcher(value);
        if (iso.find()) return iso.group();
        Matcher pt = PT_BR_DATE.matcher(value);
        if (pt.find()) {
            Month month = ptMonth(pt.group(2));
            if (month == null) return null;
            LocalDateTime local = LocalDateTime.of(
                    Integer.parseInt(pt.group(3)),
                    month,
                    Integer.parseInt(pt.group(1)),
                    Integer.parseInt(pt.group(4)),
                    Integer.parseInt(pt.group(5)),
                    Integer.parseInt(pt.group(6)));
            String zoneAbbrev = pt.group(7) == null ? "BRT" : pt.group(7).toUpperCase(Locale.ROOT);
            return local.toInstant(brazilOffset(zoneAbbrev)).toString();
        }
        try {
            return Instant.parse(value.trim()).toString();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private ZonedDateTime localTime(String instant) {
        if (instant == null) return null;
        try {
            return Instant.parse(instant).atZone(zone);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ZoneOffset brazilOffset(String zoneAbbrev) {
        return switch (zoneAbbrev) {
            case "UTC", "GMT" -> ZoneOffset.UTC;
            case "BRST" -> ZoneOffset.ofHours(-2);
            default -> ZoneOffset.ofHours(-3);
        };
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

    private void logSlow(String label, long started) {
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        if (elapsedMs >= 750) System.out.printf(Locale.ROOT, "Query lenta %s: %.1fms%n", label, elapsedMs);
    }

    private String text(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private record BuiltFilter(String where, MapSqlParameterSource params) {}

    private record QueryParts(
            String text,
            List<String> excludedText,
            String site,
            String type,
            String source,
            String before,
            String after,
            String path,
            String title,
            String body,
            boolean hasUrl,
            boolean hasTime,
            boolean missingTime) {}
}
