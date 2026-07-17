package app.memoria;

import java.util.Map;

public record StatusResponse(
        String dbPath, long eventCount, Map<String, Object> latestImport, String defaultTakeoutPath) {}
