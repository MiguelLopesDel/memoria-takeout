package app.memoria;

import java.util.List;
import java.util.Map;

public record EventsResponse(List<Map<String, Object>> rows) {}
