package app.memoria;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only YouTube deep-dive reports. The ecosystem covers the YouTube source (My Activity,
 * watch history, comments, chats, posts...) plus browser visits to youtube.com, so a video's
 * view count merges Takeout activity with Chrome history hits on the same watch URL. Nothing
 * here mutates events; the channel-column backfill lives in EventStore.
 */
@Service
public class YouTubeService {
    private static final String YOUTUBE_SCOPE = "(source = 'YouTube' OR root_domain = 'youtube.com')";
    private static final String VIDEO_GUARD = "url LIKE '%watch?v=%'";
    // Canonical 11-char video id lifted straight from the watch URL; no extra column needed.
    private static final String VIDEO_ID = "substr(url, instr(url, 'watch?v=') + 8, 11)";
    // "How many times you actually watched/opened it": non-interaction events, deduplicated
    // by second-truncated timestamp so the same record found in overlapping Takeout files
    // counts once (the JSON format carries milliseconds, the HTML one doesn't).
    private static final String VIEW_COUNT =
            "COUNT(DISTINCT CASE WHEN type NOT IN ('comment', 'chat', 'message', 'post') THEN COALESCE(substr(timestamp, 1, 19), 'row:' || id) END)";

    private final NamedParameterJdbcTemplate named;
    private final EventQueries queries;

    public YouTubeService(NamedParameterJdbcTemplate named, EventQueries queries) {
        this.named = named;
        this.queries = queries;
    }

    public Map<String, Object> youtubeReport(FilterParams filters, int limit) {
        long started = System.nanoTime();
        EventQueries.BuiltFilter built = queries.scopedFilter(queries.buildFilters(filters, true), YOUTUBE_SCOPE);
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
        out.put("timeline", queries.timeline(built));
        out.put("byType", queries.grouped("type", built, 12, ""));
        out.put("topVideos", youtubeVideos(filters, "", limit));
        out.put("topChannels", youtubeTopChannels(built, limit));
        out.put("channelPhases", youtubeChannelPhases(built, 10));
        queries.logSlow("youtube", started);
        return out;
    }

    // Videos grouped by watch id — "quantas vezes vi, primeira e última vez". The optional
    // search matches the video title or the channel name.
    public List<Map<String, Object>> youtubeVideos(FilterParams filters, String search, int limit) {
        // Ads impressions share watch URLs and would otherwise dominate the "most watched" list.
        EventQueries.BuiltFilter built = queries.scopedFilter(
                queries.buildFilters(filters, true),
                YOUTUBE_SCOPE + " AND " + VIDEO_GUARD + " AND raw_json NOT LIKE '%From Google Ads%'");
        String where = built.where();
        MapSqlParameterSource params =
                queries.copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
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
                                .formatted(EventQueries.RECALL_COLUMNS, where),
                        params));
        return out;
    }

    // One channel's story: monthly rhythm, most-watched videos, first/last contact.
    public Map<String, Object> youtubeChannel(FilterParams filters, String channel, int limit) {
        EventQueries.BuiltFilter base =
                queries.scopedFilter(queries.buildFilters(filters, true), YOUTUBE_SCOPE + " AND channel = :yt_channel");
        MapSqlParameterSource params = queries.copyParams(base.params()).addValue("yt_channel", channel);
        EventQueries.BuiltFilter built = new EventQueries.BuiltFilter(base.where(), params);
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
        out.put("timeline", queries.timeline(built));
        MapSqlParameterSource videoParams =
                queries.copyParams(built.params()).addValue("limit", Math.max(1, Math.min(100, limit)));
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

    private List<Map<String, Object>> youtubeTopChannels(EventQueries.BuiltFilter built, int limit) {
        String where = queries.joinWhere(built.where(), "channel IS NOT NULL");
        MapSqlParameterSource params =
                queries.copyParams(built.params()).addValue("limit", Math.max(1, Math.min(200, limit)));
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
    private List<Map<String, Object>> youtubeChannelPhases(EventQueries.BuiltFilter built, int limit) {
        String where = queries.joinWhere(built.where(), "channel IS NOT NULL AND year_month IS NOT NULL");
        MapSqlParameterSource topParams =
                queries.copyParams(built.params()).addValue("limit", Math.max(1, Math.min(20, limit)));
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
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> top : tops) seen.add(String.valueOf(top.get("label")));
            MapSqlParameterSource droppedParams = queries.copyParams(built.params())
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

        List<Map<String, Object>> out = queries.monthlyPhases("channel", where, built.params(), tops);
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
}
