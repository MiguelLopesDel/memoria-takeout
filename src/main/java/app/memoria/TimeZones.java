package app.memoria;

import java.time.ZoneId;

/** Resolves a configured timezone id, falling back to America/Sao_Paulo when invalid or blank. */
final class TimeZones {
    static final ZoneId DEFAULT = ZoneId.of("America/Sao_Paulo");

    private TimeZones() {}

    static ZoneId resolve(String timezone) {
        if (timezone == null || timezone.isBlank()) return DEFAULT;
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception ignored) {
            return DEFAULT;
        }
    }
}
