package app.memoria;

public record ImportJob(
        String id,
        String path,
        String status,
        String message,
        long eventCount,
        int filesFound,
        int filesProcessed,
        String currentFile,
        String currentSource,
        long bytesRead,
        double eventsPerMinute,
        long elapsedSeconds,
        Long estimatedRemainingSeconds,
        String startedAt,
        String finishedAt,
        String error) {}
