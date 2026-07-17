package app.memoria;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Recovers the channel name from a My Activity / watch-history HTML fragment. The flattened
// text reads "YouTube Watched <title> <channel> <pt-BR date> ...", so the channel is whatever
// sits between the known title and the first date. Ads entries ("Watched at 11:38 ...") and
// entries without a channel yield null. Subscription entries carry the channel as the title.
public final class YouTubeChannels {
    private static final Pattern PT_BR_DATE = Pattern.compile(
            "\\d{1,2} de [a-zç.]+ de \\d{4}, \\d{1,2}:\\d{2}:\\d{2}\\s*(BRT|BRST|UTC|GMT)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ISO_IN_TEXT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z");
    private static final int MAX_CHANNEL_LENGTH = 100;

    private YouTubeChannels() {}

    // JSON-format My Activity rows carry the channel structured in subtitles[0]
    // ({"name":"Channel","url":".../channel/UC..."}), which is exact — no heuristic needed.
    public static String fromSubtitles(JsonNode raw) {
        if (raw == null) return null;
        JsonNode first = raw.path("subtitles").path(0);
        String name = first.path("name").asText("").trim();
        if (name.isEmpty() || name.length() > MAX_CHANNEL_LENGTH) return null;
        String url = first.path("url").asText("");
        if (!url.isEmpty() && !url.contains("/channel/") && !url.contains("/@") && !url.contains("/user/")) return null;
        return name;
    }

    public static String extract(String title, String htmlText) {
        if (title == null || title.isBlank() || htmlText == null || htmlText.isBlank()) return null;
        int titleAt = htmlText.indexOf(title);
        if (titleAt < 0) return null;
        String tail = htmlText.substring(titleAt + title.length());
        int dateAt = earliestDate(tail);
        if (dateAt < 0) return null;
        String candidate = tail.substring(0, dateAt).trim();
        if (candidate.isEmpty()) {
            // "Subscribed to <channel>" puts the channel name in the title itself.
            String head = htmlText.substring(0, titleAt);
            if (head.contains("Subscribed to") && title.length() <= MAX_CHANNEL_LENGTH) return title;
            return null;
        }
        // Ads entries repeat the watch time instead of a channel ("Watched at 11:38 ...").
        if (candidate.startsWith("Watched at")) return null;
        if (candidate.length() > MAX_CHANNEL_LENGTH) return null;
        if (candidate.contains("Produtos:") || candidate.contains("Products:")) return null;
        return candidate;
    }

    private static int earliestDate(String value) {
        int at = -1;
        Matcher pt = PT_BR_DATE.matcher(value);
        if (pt.find()) at = pt.start();
        Matcher iso = ISO_IN_TEXT.matcher(value);
        if (iso.find() && (at < 0 || iso.start() < at)) at = iso.start();
        return at;
    }
}
