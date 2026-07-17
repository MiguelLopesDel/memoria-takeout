package app.memoria;

import java.util.List;
import java.util.Map;

public record MetricsResponse(
        Map<String, Object> summary,
        List<Facet> byType,
        List<Facet> bySource,
        List<Facet> topDomains,
        List<Facet> timeline) {}
