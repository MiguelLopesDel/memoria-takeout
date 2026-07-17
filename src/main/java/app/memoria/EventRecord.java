package app.memoria;

public record EventRecord(
        String eventKey,
        String timestamp,
        String source,
        String type,
        String title,
        String text,
        String url,
        String domain,
        String rootDomain,
        String filePath,
        String rawJson) {}
