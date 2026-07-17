package app.memoria;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared query DSL and common aggregations over the events table. Internal seam used by the
 * analytics modules (AnalyticsService and YouTubeService): it turns the 7
 * shared filter params plus the mini query language in {@code q} (site:/type:/source:/before:/
 * after:/path:/title:/text:, has:url, has:time, missing:time, -word) into a WHERE clause with
 * bound parameters, and runs the small GROUP BY shapes every report reuses (facets, monthly
 * timeline, monthly phases). Read-only: nothing here mutates events (that is EventStore's job).
 */
@Component
class EventQueries {
    // Recall queries return a lean projection (no raw_json) since a single day can hold thousands of rows.
    static final String RECALL_COLUMNS = "id, timestamp, local_day, local_hour, source, type, title, text, url, domain";

    private final NamedParameterJdbcTemplate named;

    EventQueries(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    record BuiltFilter(String where, MapSqlParameterSource params) {}

    record QueryParts(
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

    BuiltFilter buildFilters(FilterParams filters, boolean includeQuery) {
        return buildFilters(filters, includeQuery, "");
    }

    BuiltFilter buildFilters(FilterParams filters, boolean includeQuery, String qualifier) {
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

    QueryParts parseQuery(String value) {
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

    String toFtsQuery(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.replace("\"", "").replace("'", "").split("\\s+")) {
            for (String cleaned :
                    part.replaceAll("[^\\p{L}\\p{N}_]+", " ").trim().split("\\s+")) {
                if (!cleaned.isBlank()) parts.add(cleaned + "*");
            }
        }
        return parts.isEmpty() ? "\"\"" : String.join(" ", parts);
    }

    BuiltFilter scopedFilter(BuiltFilter built, String scope) {
        return new BuiltFilter(joinWhere(built.where(), scope), built.params());
    }

    String joinWhere(String left, String right) {
        if (left == null || left.isBlank()) return right == null ? "" : right;
        if (right == null || right.isBlank()) return left;
        return left + " AND " + right;
    }

    MapSqlParameterSource copyParams(MapSqlParameterSource source) {
        MapSqlParameterSource copy = new MapSqlParameterSource();
        for (String name : source.getParameterNames()) {
            copy.addValue(name, source.getValue(name));
        }
        return copy;
    }

    List<Facet> grouped(String expression, BuiltFilter built, int limit, String extra) {
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

    List<Facet> timeline(BuiltFilter built) {
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

    // Shared by phases (Padrões) and channelPhases (YouTube): fetch each top label's monthly
    // series and derive first/last/peak stats. `column` is a trusted identifier, never user input.
    List<Map<String, Object>> monthlyPhases(
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

    // Prefixes the lean recall projection with a table alias for JOIN queries.
    static String recallColumns(String alias) {
        String[] columns = RECALL_COLUMNS.split(", ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) out.append(", ");
            out.append(alias).append('.').append(columns[i]);
        }
        return out.toString();
    }

    void logSlow(String label, long started) {
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        if (elapsedMs >= 750) System.out.printf(Locale.ROOT, "Query lenta %s: %.1fms%n", label, elapsedMs);
    }
}
