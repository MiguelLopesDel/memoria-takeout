package app.memoria;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reduces a host to its registrable domain (eTLD+1), so subdomains collapse into one site:
 * {@code pt.m.wikipedia.org} and {@code en.wikipedia.org} both become {@code wikipedia.org},
 * while multi-label public suffixes like {@code com.br} keep the label before them
 * ({@code forum.biglinux.com.br} -> {@code biglinux.com.br}).
 *
 * <p>This is a heuristic, not the full Public Suffix List: it covers the common second-level
 * labels under two-letter country TLDs, which handles the overwhelming majority of real hosts.
 */
final class Domains {
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Set<String> SECOND_LEVEL = Set.of(
            "com", "net", "org", "gov", "edu", "co", "ac", "mil", "gob", "gouv", "or", "ne", "go", "biz", "info", "ind",
            "adv", "eng", "art");

    private Domains() {}

    static String registrable(String host) {
        if (host == null || host.isBlank()) return null;
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isBlank() || h.equals("localhost") || IPV4.matcher(h).matches()) return h;

        String[] labels = h.split("\\.");
        if (labels.length <= 2) return h;

        String tld = labels[labels.length - 1];
        String secondLevel = labels[labels.length - 2];
        boolean countryTld = tld.length() == 2;
        if (countryTld && SECOND_LEVEL.contains(secondLevel)) {
            return labels[labels.length - 3] + "." + secondLevel + "." + tld;
        }
        return secondLevel + "." + tld;
    }
}
