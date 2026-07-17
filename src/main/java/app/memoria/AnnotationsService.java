package app.memoria;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * User-curated data around events: tags, collections, notes, saved filters and the Assuntos
 * topics CRUD (the topic report itself is analytics — see AnalyticsService.topicReport). These
 * tables reference events by id but never write to the events table, so none of EventStore's
 * mutation invariants (FTS rebuild, local columns) apply here.
 */
@Service
public class AnnotationsService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public AnnotationsService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
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
                        .formatted(EventQueries.recallColumns("e")),
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
                        .formatted(EventQueries.recallColumns("e")),
                new MapSqlParameterSource("collectionId", collectionId)
                        .addValue("limit", Math.max(1, Math.min(2000, limit))));
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

    private String text(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }
}
