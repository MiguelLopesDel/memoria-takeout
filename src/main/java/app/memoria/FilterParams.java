package app.memoria;

public record FilterParams(
        String q, String source, String type, String domain, String from, String to, String product) {
    public static FilterParams of(String q, String source, String type, String domain, String from, String to) {
        return of(q, source, type, domain, from, to, "");
    }

    public static FilterParams of(
            String q, String source, String type, String domain, String from, String to, String product) {
        return new FilterParams(
                clean(q), clean(source), clean(type), clean(domain), clean(from), clean(to), clean(product));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
