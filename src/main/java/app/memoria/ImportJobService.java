package app.memoria;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class ImportJobService {
    private final TakeoutImporter importer;
    private final EventStore database;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<ImportJob> current = new AtomicReference<>(idle());

    public ImportJobService(TakeoutImporter importer, EventStore database) {
        this.importer = importer;
        this.database = database;
    }

    @PostConstruct
    public void init() {
        ImportJob latest = database.latestImportJob();
        if (latest == null) return;
        current.set(
                "running".equals(latest.status())
                        ? withStatus(
                                latest,
                                "error",
                                "Importação interrompida",
                                Instant.now().toString(),
                                "O backend foi encerrado antes de concluir este job.")
                        : latest);
    }

    public synchronized ImportJob start(String path) {
        ImportJob active = current.get();
        if ("running".equals(active.status())) return active;

        String id = UUID.randomUUID().toString();
        ImportJob started = new ImportJob(
                id,
                path,
                "running",
                "Preparando importação",
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                null,
                Instant.now().toString(),
                null,
                null);
        current.set(started);
        database.upsertImportJob(started);
        executor.submit(() -> run(started));
        return started;
    }

    public ImportJob status() {
        return current.get();
    }

    private void run(ImportJob started) {
        try {
            ImportResult result = importer.importTakeout(started.path(), update -> {
                current.updateAndGet(job -> {
                    if (!started.id().equals(job.id())) return job;
                    ImportJob next = refreshed(job, update);
                    database.upsertImportJob(next);
                    return next;
                });
            });
            ImportJob done = new ImportJob(
                    started.id(),
                    result.sourcePath(),
                    "done",
                    String.format(
                            "Importação concluída: %d novos de %d processados (%d no total)",
                            result.added(), result.eventCount(), result.total()),
                    result.total(),
                    current.get().filesFound(),
                    current.get().filesFound(),
                    null,
                    null,
                    current.get().bytesRead(),
                    current.get().eventsPerMinute(),
                    elapsedSeconds(started.startedAt()),
                    0L,
                    started.startedAt(),
                    Instant.now().toString(),
                    null);
            current.set(done);
            database.upsertImportJob(done);
        } catch (Exception error) {
            ImportJob failed = new ImportJob(
                    started.id(),
                    started.path(),
                    "error",
                    "Importação falhou",
                    current.get().eventCount(),
                    current.get().filesFound(),
                    current.get().filesProcessed(),
                    current.get().currentFile(),
                    current.get().currentSource(),
                    current.get().bytesRead(),
                    current.get().eventsPerMinute(),
                    elapsedSeconds(started.startedAt()),
                    current.get().estimatedRemainingSeconds(),
                    started.startedAt(),
                    Instant.now().toString(),
                    error.getMessage());
            current.set(failed);
            database.upsertImportJob(failed);
        }
    }

    private ImportJob refreshed(ImportJob job, TakeoutImporter.ImportProgress update) {
        long eventCount = update.eventCount() < 0 ? job.eventCount() : update.eventCount();
        int filesFound = update.filesFound() < 0 ? job.filesFound() : update.filesFound();
        int filesProcessed = update.filesProcessed() < 0 ? job.filesProcessed() : update.filesProcessed();
        long bytesRead = update.bytesRead() < 0 ? job.bytesRead() : update.bytesRead();
        long elapsed = elapsedSeconds(job.startedAt());
        double eventsPerMinute = elapsed <= 0 ? 0 : eventCount * 60.0 / elapsed;
        Long estimate = estimateRemainingSeconds(eventCount, filesFound, filesProcessed, elapsed);
        return new ImportJob(
                job.id(),
                job.path(),
                "running",
                update.message() == null ? job.message() : update.message(),
                eventCount,
                filesFound,
                filesProcessed,
                update.currentFile() == null ? job.currentFile() : update.currentFile(),
                update.currentSource() == null ? job.currentSource() : update.currentSource(),
                bytesRead,
                eventsPerMinute,
                elapsed,
                estimate,
                job.startedAt(),
                null,
                null);
    }

    private Long estimateRemainingSeconds(long eventCount, int filesFound, int filesProcessed, long elapsed) {
        if (elapsed <= 0 || eventCount <= 0 || filesFound <= 0 || filesProcessed <= 0) return null;
        double processedRatio = Math.min(0.98, filesProcessed / (double) filesFound);
        if (processedRatio <= 0) return null;
        long totalEstimate = Math.round(elapsed / processedRatio);
        return Math.max(0, totalEstimate - elapsed);
    }

    private long elapsedSeconds(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) return 0;
        try {
            return Math.max(
                    0, Duration.between(Instant.parse(startedAt), Instant.now()).toSeconds());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private ImportJob withStatus(ImportJob job, String status, String message, String finishedAt, String error) {
        ImportJob updated = new ImportJob(
                job.id(),
                job.path(),
                status,
                message,
                job.eventCount(),
                job.filesFound(),
                job.filesProcessed(),
                job.currentFile(),
                job.currentSource(),
                job.bytesRead(),
                job.eventsPerMinute(),
                job.elapsedSeconds(),
                job.estimatedRemainingSeconds(),
                job.startedAt(),
                finishedAt,
                error);
        database.upsertImportJob(updated);
        return updated;
    }

    private static ImportJob idle() {
        return new ImportJob(
                "",
                "",
                "idle",
                "Nenhuma importação em andamento",
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                null,
                null);
    }
}
