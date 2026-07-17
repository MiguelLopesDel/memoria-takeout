package app.memoria;

import java.util.List;

public record FacetsResponse(List<Facet> sources, List<Facet> types, List<Facet> domains) {}
