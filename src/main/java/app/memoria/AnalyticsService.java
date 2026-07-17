package app.memoria;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only reports over the events table: metrics/overview, per-site drill-down, timeline and
 * calendar shapes, rankings, recall (Memórias), Padrões and Assuntos. Every report funnels the 7
 * shared filter params through {@link EventQueries}; nothing here mutates events (that is
 * EventStore's job — see its class comment for the FTS and local-columns invariants).
 */
@Service
public class AnalyticsService {
    private static final Pattern MONTH_DAY = Pattern.compile("^\\d{2}-\\d{2}$");
    private static final Pattern LOCAL_DAY_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter MONTH_DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final EventQueries queries;
    private final ZoneId zone;

    @org.springframework.beans.factory.annotation.Autowired
    public AnalyticsService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            EventQueries queries,
            @Value("${memoria.timezone:America/Sao_Paulo}") String timezone) {
        this.jdbc = jdbc;
        this.named = named;
        this.queries = queries;
        this.zone = TimeZones.resolve(timezone);
    }

    // Convenience constructor for tests and non-Spring callers; uses the default timezone.
    public AnalyticsService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, EventQueries queries) {
        this(jdbc, named, queries, "America/Sao_Paulo");
    }

    public MetricsResponse metrics(FilterParams filters) {
        long started = System.nanoTime();
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
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
                queries.grouped("type", built, 100, ""),
                queries.grouped("source", built, 16, ""),
                queries.grouped("COALESCE(domain, 'sem dominio')", built, 20, ""),
                queries.timeline(built));
        queries.logSlow("metrics", started);
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
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "url IS NOT NULL AND local_day IS NOT NULL");
        MapSqlParameterSource params =
                queries.copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
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
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "url IS NOT NULL");
        MapSqlParameterSource params =
                queries.copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
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
        EventQueries.BuiltFilter built = queries.buildFilters(filters, false);
        return new FacetsResponse(
                queries.grouped("source", built, 100, ""),
                queries.grouped("type", built, 100, ""),
                queries.grouped("domain", built, 40, "domain IS NOT NULL"));
    }

    public List<Facet> products() {
        return queries.grouped("source", queries.buildFilters(FilterParams.of("", "", "", "", "", ""), false), 200, "");
    }

    public List<Map<String, Object>> days(FilterParams filters) {
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "local_day IS NOT NULL");
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
                        .formatted(EventQueries.RECALL_COLUMNS, EventQueries.RECALL_COLUMNS),
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
                        .formatted(EventQueries.RECALL_COLUMNS),
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
                        .formatted(EventQueries.RECALL_COLUMNS));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> calendar(FilterParams filters) {
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "local_day IS NOT NULL");
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
        return queries.grouped(
                "domain", queries.buildFilters(filters, true), Math.max(1, Math.min(200, limit)), "domain IS NOT NULL");
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
        return queries.grouped("source", queries.buildFilters(filters, true), Math.max(1, Math.min(200, limit)), "");
    }

    public List<Facet> typeRanking(FilterParams filters, int limit) {
        return queries.grouped("type", queries.buildFilters(filters, true), Math.max(1, Math.min(200, limit)), "");
    }

    public List<Map<String, Object>> hourly(FilterParams filters) {
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "local_hour IS NOT NULL");
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
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "local_weekday IS NOT NULL");
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
        queries.logSlow("patterns", started);
        return out;
    }

    // 7x24 activity matrix (weekday x hour). Returned sparse; the frontend fills the grid.
    private List<Map<String, Object>> rhythm(FilterParams filters) {
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "local_weekday IS NOT NULL AND local_hour IS NOT NULL");
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
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "local_day IS NOT NULL");
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
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "root_domain IS NOT NULL AND year_month IS NOT NULL");
        int top = Math.max(1, Math.min(20, limit));

        MapSqlParameterSource topParams = queries.copyParams(built.params()).addValue("limit", top);
        List<Map<String, Object>> tops = named.queryForList(
                "SELECT root_domain AS label, COUNT(*) AS total FROM events WHERE " + where
                        + " GROUP BY root_domain ORDER BY total DESC LIMIT :limit",
                topParams);
        if (tops.isEmpty()) return List.of();

        return queries.monthlyPhases("root_domain", where, built.params(), tops);
    }

    // Recurring searches: identical search titles repeated over time (grouped case-insensitively).
    private List<Map<String, Object>> recurringSearches(FilterParams filters, int limit) {
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
        String where = queries.joinWhere(built.where(), "type = 'search' AND title IS NOT NULL");
        MapSqlParameterSource params =
                queries.copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
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

    public Map<String, Object> topicReport(String keywords, FilterParams filters, int limit) {
        long started = System.nanoTime();
        String fts = topicFtsQuery(keywords);
        if (fts.isEmpty()) throw new IllegalArgumentException("Informe ao menos uma palavra-chave.");
        EventQueries.BuiltFilter base = queries.buildFilters(filters, true);
        MapSqlParameterSource params = queries.copyParams(base.params()).addValue("topic_fts", fts);
        EventQueries.BuiltFilter built = new EventQueries.BuiltFilter(
                queries.joinWhere(
                        base.where(), "id IN (SELECT rowid FROM events_fts WHERE events_fts MATCH :topic_fts)"),
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
        out.put("timeline", queries.timeline(built));
        out.put("bySource", queries.grouped("source", built, 12, ""));
        out.put("byType", queries.grouped("type", built, 12, ""));
        out.put("topDomains", queries.grouped("root_domain", built, safeLimit, "root_domain IS NOT NULL"));
        MapSqlParameterSource pageParams = queries.copyParams(built.params()).addValue("limit", safeLimit);
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
                                .formatted(queries.joinWhere(built.where(), "url IS NOT NULL")),
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
                                .formatted(queries.joinWhere(built.where(), "channel IS NOT NULL")),
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
                                .formatted(queries.joinWhere(built.where(), "type = 'search' AND title IS NOT NULL")),
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
                                .formatted(EventQueries.RECALL_COLUMNS, built.where()),
                        built.params()));
        queries.logSlow("topicReport", started);
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
        EventQueries.BuiltFilter built = queries.buildFilters(filters, true);
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
        EventQueries.QueryParts query = queries.parseQuery(filters.q());
        EventQueries.BuiltFilter built =
                queries.buildFilters(filters, false, query.text().isBlank() ? "" : "events.");
        MapSqlParameterSource params = built.params().addValue("limit", limit).addValue("offset", offset);

        if (!query.text().isBlank()) {
            params.addValue("fts", queries.toFtsQuery(query.text()));
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
            queries.logSlow("events.fts", started);
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
        queries.logSlow("events", started);
        return rows;
    }
}
