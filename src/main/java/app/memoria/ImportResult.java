package app.memoria;

// eventCount: records emitted by the parsers this run.
// added: rows actually inserted (new events after dedup). total: rows in the DB afterwards.
public record ImportResult(String sourcePath, long eventCount, long added, long total) {}
