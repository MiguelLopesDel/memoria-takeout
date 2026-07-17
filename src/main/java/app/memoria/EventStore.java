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
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The write side of the events database: schema, migrations, import persistence, backfills and
 * cleanups. This is the ONLY class that mutates events rows, and it owns the two invariants that
 * used to be every caller's problem:
 *
 * <ul>
 *   <li>events_fts is external-content with no sync triggers, so any insert/delete/update of an
 *       FTS-indexed column (title, text, url, domain, source, type) must rebuild the index — all
 *       mutation paths do that before returning, including failed imports.
 *   <li>local_day/local_hour/local_weekday/year_month are precomputed from the timestamp at import
 *       time, so any timestamp change must recompute them — {@link #setEventTimestamp} does both
 *       in one statement.
 * </ul>
 *
 * Read-side services (AnalyticsService, YouTubeService, AnnotationsService) never write to events.
 */
@Service
public class EventStore {
    private static final Pattern PT_BR_DATE = Pattern.compile(
            "(\\d{1,2}) de ([a-zç.]+) de (\\d{4}), (\\d{1,2}):(\\d{2}):(\\d{2})\\s*(BRT|BRST|UTC|GMT)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ISO_IN_TEXT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z");
    private static final DateTimeFormatter LOCAL_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LOCAL_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Path dbPath;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ZoneId zone;

    @org.springframework.beans.factory.annotation.Autowired
    public EventStore(
            @Value("${memoria.data-dir}") String dataDir,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            @Value("${memoria.timezone:America/Sao_Paulo}") String timezone)
            throws IOException {
        Path dir = Path.of(dataDir);
        this.dbPath = dir.resolve("memoria.db");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.zone = TimeZones.resolve(timezone);
    }

    // Convenience constructor for tests and non-Spring callers; uses the default timezone.
    public EventStore(String dataDir, JdbcTemplate jdbc, TransactionTemplate transactions) throws IOException {
        this(dataDir, jdbc, transactions, "America/Sao_Paulo");
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
        if (addedKey || upgradedKey) rebuildSearchIndex();
        refreshLatestImportCount();
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

    /**
     * Runs an incremental import while owning the complete mutation lifecycle. Batches can leave
     * the external-content FTS index stale only while {@code work} is running; the finally block
     * rebuilds it on both success and failure, so callers cannot forget the invariant.
     */
    ImportOutcome mergeImport(String sourcePath, ImportWork work) throws IOException {
        long beforeCount = eventCount();
        long importId = beginImport(sourcePath);
        try {
            work.run(this::insertBatch);
        } finally {
            finishImport(importId, eventCount());
        }
        long total = eventCount();
        return new ImportOutcome(Math.max(0, total - beforeCount), total);
    }

    private long beginImport(String sourcePath) {
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

    // Part of mergeImport: batches leave events_fts stale on purpose because rebuilding per batch
    // would be quadratic. mergeImport's finally block always rebuilds it before returning.
    private void insertBatch(List<EventRecord> events) {
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
                        ZonedDateTime local = localTime(event.timestamp());
                        ps.setString(1, event.eventKey());
                        ps.setString(2, event.timestamp());
                        ps.setString(3, local == null ? null : local.format(LOCAL_MONTH));
                        ps.setString(4, local == null ? null : local.format(LOCAL_DAY));
                        ps.setObject(5, local == null ? null : local.getHour());
                        ps.setObject(
                                6, local == null ? null : local.getDayOfWeek().getValue() % 7);
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

    private void finishImport(long importId, long count) {
        jdbc.update("UPDATE imports SET event_count = ? WHERE id = ?", count, importId);
        rebuildSearchIndex();
        backfillYouTubeChannels();
        jdbc.execute("PRAGMA optimize");
    }

    @FunctionalInterface
    interface ImportWork {
        void run(Consumer<List<EventRecord>> batches) throws IOException;
    }

    record ImportOutcome(long added, long total) {}

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

    // ---- The mutation funnel: every ad-hoc change to events rows goes through here. ----

    // Runs a mutation of events rows and upholds the search-index invariant structurally:
    // the mutation, the FTS rebuild and the import-count refresh commit in one transaction,
    // so no caller can delete/update events and leave events_fts stale.
    private void mutateEvents(Runnable mutation) {
        transactions.executeWithoutResult(status -> {
            mutation.run();
            rebuildSearchIndex();
            refreshLatestImportCount();
        });
        jdbc.execute("PRAGMA optimize");
    }

    private void rebuildSearchIndex() {
        jdbc.execute("INSERT INTO events_fts(events_fts) VALUES('rebuild')");
    }

    private void refreshLatestImportCount() {
        jdbc.update(
                """
        UPDATE imports
        SET event_count = (SELECT COUNT(*) FROM events)
        WHERE id = (SELECT MAX(id) FROM imports)
        """);
    }

    // Recovers timestamps for undated rows by scanning their text/title/raw_json for an ISO or
    // pt-BR date. Timestamp changes recompute the local columns (setEventTimestamp); the
    // timestamp itself is not FTS-indexed, so no search-index rebuild is needed.
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
        int[] updated = {0};
        // One surrounding transaction, same reason as the other backfills: per-row autocommit
        // makes tens of thousands of UPDATEs take minutes.
        transactions.executeWithoutResult(status -> {
            for (Map<String, Object> row : rows) {
                String timestamp = recoverTimestamp(String.valueOf(row.getOrDefault("text", ""))
                        + " " + String.valueOf(row.getOrDefault("title", ""))
                        + " " + String.valueOf(row.getOrDefault("raw_json", "")));
                if (timestamp == null) continue;
                setEventTimestamp(row.get("id"), timestamp);
                updated[0]++;
            }
        });
        if (updated[0] > 0) jdbc.execute("PRAGMA optimize");
        return Map.of("scanned", rows.size(), "updated", updated[0]);
    }

    // The local-columns invariant lives here: a timestamp never changes without local_day,
    // local_hour, local_weekday and year_month being recomputed in the configured zone.
    private void setEventTimestamp(Object id, String timestamp) {
        ZonedDateTime local = localTime(timestamp);
        jdbc.update(
                "UPDATE events SET timestamp = ?, year_month = ?, local_day = ?, local_hour = ?, local_weekday = ? WHERE id = ?",
                timestamp,
                local == null ? null : local.format(LOCAL_MONTH),
                local == null ? null : local.format(LOCAL_DAY),
                local == null ? null : local.getHour(),
                local == null ? null : local.getDayOfWeek().getValue() % 7,
                id);
    }

    public Map<String, Object> cleanupMetadataFragments() {
        return deleteUndatedHtmlRows("(text LIKE '%Por que isso%' OR text LIKE '%Why this%')"
                + " AND (text LIKE '%Produtos:%' OR text LIKE '%Products:%')");
    }

    public Map<String, Object> cleanupUndatedHtmlFragments() {
        return deleteUndatedHtmlRows("");
    }

    // Shared by the two HTML-fragment cleanups: deletes undated Minhaatividade.html rows
    // matching an optional extra predicate, through the mutation funnel.
    private Map<String, Object> deleteUndatedHtmlRows(String extraPredicate) {
        String where = "timestamp IS NULL AND file_path LIKE '%Minhaatividade.html'"
                + (extraPredicate.isBlank() ? "" : " AND " + extraPredicate);
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        Long candidates = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE " + where, Long.class);
        mutateEvents(() -> jdbc.update("DELETE FROM events WHERE " + where));
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
        mutateEvents(() -> {
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
        });
        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        return Map.of(
                "before", before == null ? 0 : before,
                "deleted", (before == null ? 0 : before) - (after == null ? 0 : after),
                "after", after == null ? 0 : after);
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
}
