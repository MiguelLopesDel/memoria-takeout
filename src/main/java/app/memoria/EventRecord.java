package app.memoria;

public record EventRecord(
        String eventKey,
        String timestamp,
        String yearMonth,
        String localDay,
        Integer localHour,
        Integer localWeekday,
        String source,
        String type,
        String title,
        String text,
        String url,
        String domain,
        String rootDomain,
        String filePath,
        String rawJson) {}
