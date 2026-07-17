package app.memoria;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Stable identity for an event, shared by fresh imports (TakeoutImporter) and the
// migration backfill (EventStore) so both compute byte-identical keys.
//
// Strategy: a reliable per-occurrence natural key when the source carries one that is
// also present in the stored raw_json (so the backfill can re-derive it); otherwise a
// content hash plus an occurrence ordinal that preserves indistinguishable-but-distinct
// records (e.g. several identical Google Lens searches in the same second) while still
// deduplicating a re-imported export.
public final class EventKeys {
    public static final String IDENTITY_VERSION = "canonical-v1";

    private EventKeys() {}

    // Returns a reliable natural key for the record, or null when none applies. Both the
    // importer and the backfill call this against the same raw node / raw_json, so the keys
    // match and incremental import dedups without a forced clean re-import.
    public static String naturalKey(String source, JsonNode raw) {
        if (raw == null) return null;
        if ("Chrome".equals(source)) {
            long timeUsec = raw.path("time_usec").asLong(0);
            if (timeUsec > 0) return "chrome:" + timeUsec;
        }
        JsonNode commentId = raw.get("ID do comentário");
        if (commentId != null && !commentId.asText("").isBlank())
            return "ytc:" + commentId.asText().trim();
        JsonNode chatId = raw.get("ID do chat ao vivo");
        if (chatId != null && !chatId.asText("").isBlank())
            return "ytchat:" + chatId.asText().trim();
        JsonNode postId = raw.get("ID do post");
        if (postId != null && !postId.asText("").isBlank())
            return "ytpost:" + postId.asText().trim();
        return null;
    }

    // Identity deliberately depends on the immutable exported record, not parser output.
    // This lets a re-import update normalized fields after parser fixes without changing IDs.
    public static String contentHash(
            String timestamp,
            String source,
            String type,
            String url,
            String title,
            String text,
            String rawJson,
            JsonNode raw) {
        StringBuilder canonical = new StringBuilder(rawJson == null ? 64 : rawJson.length() + 32);
        canonical.append(IDENTITY_VERSION).append('\u0001');
        if (raw == null) canonical.append(n(rawJson));
        else appendCanonical(raw, canonical);
        return "event:" + sha256Hex(canonical.toString()).substring(0, 16);
    }

    private static void appendCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
        } else if (node.isObject()) {
            out.append('{');
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            Collections.sort(fields);
            for (String field : fields) {
                out.append(field.length()).append(':').append(field).append('=');
                appendCanonical(node.get(field), out);
                out.append(';');
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (JsonNode child : node) {
                appendCanonical(child, out);
                out.append(';');
            }
            out.append(']');
        } else {
            out.append(node.toString());
        }
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
