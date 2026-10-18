package se.radiocast;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the station list into rows for the screen: either in the user's own order,
 * or grouped under a heading per country (headings can be folded away).
 * Pure Java, no Android, so it can be unit tested.
 */
public final class StationGroups {

    public static final class Row {
        /** Heading row: the country code ("" = unknown country). Station row: null. */
        public final String headerCountry;
        /** Station rows: index in the station list. Headings: -1. */
        public final int stationIndex;
        /** Headings: how many stations the country has. */
        public final int count;
        /** Headings: whether the group is folded away. */
        public final boolean collapsed;

        private Row(String headerCountry, int stationIndex, int count, boolean collapsed) {
            this.headerCountry = headerCountry;
            this.stationIndex = stationIndex;
            this.count = count;
            this.collapsed = collapsed;
        }

        public boolean isHeader() { return headerCountry != null; }

        static Row header(String cc, int count, boolean collapsed) { return new Row(cc, -1, count, collapsed); }
        static Row station(int index) { return new Row(null, index, 0, false); }
    }

    /** Swedish alphabetical order: ... X, Y, Z, Å, Ä, Ö; case does not matter. */
    private static final java.text.Collator SWEDISH = java.text.Collator.getInstance(new Locale("sv"));
    static { SWEDISH.setStrength(java.text.Collator.SECONDARY); }

    private StationGroups() { }

    /**
     * Rows for the list. Grouped: countries in alphabetical order (by Swedish name),
     * unknown country last, stations alphabetical within a country.
     */
    public static List<Row> rows(List<Station> stations, boolean byCountry, Collection<String> collapsed) {
        List<Row> rows = new ArrayList<>();
        if (!byCountry) {
            for (int i = 0; i < stations.size(); i++) rows.add(Row.station(i));
            return rows;
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < stations.size(); i++) {
            groups.computeIfAbsent(stations.get(i).countryCode, k -> new ArrayList<>()).add(i);
        }
        List<String> countries = new ArrayList<>(groups.keySet());
        countries.sort((a, b) -> {
            if (a.isEmpty() != b.isEmpty()) return a.isEmpty() ? 1 : -1;
            return SWEDISH.compare(SearchQuery.countryName(a), SearchQuery.countryName(b));
        });
        for (String cc : countries) {
            List<Integer> members = groups.get(cc);
            members.sort((x, y) -> SWEDISH.compare(stations.get(x).name, stations.get(y).name));
            boolean folded = collapsed.contains(cc);
            rows.add(Row.header(cc, members.size(), folded));
            if (!folded) for (int i : members) rows.add(Row.station(i));
        }
        return rows;
    }

    /** "Sverige", or "Okänt land". */
    public static String heading(String countryCode) {
        return countryCode.isEmpty() ? "Okänt land" : SearchQuery.countryName(countryCode);
    }

    /**
     * Catalogue names are often long: "Radio Nova 101.5 FM | Paris | Musique éclectique".
     * Keeps the first part, so the list stays readable. The full name is still shown when expanded.
     */
    public static String shortName(String name) {
        String n = name.trim().replaceAll("\\s+", " ");
        if (n.length() <= 28) return n;
        String[] separators = {" | ", " - ", " – ", " — ", " :: ", " • ", " / ", " (", " [", ": ", ", "};
        int cut = n.length();
        for (String sep : separators) {
            int i = n.indexOf(sep);
            if (i >= 6 && i < cut) cut = i;
        }
        String s = n.substring(0, cut).trim();
        if (s.length() > 40) s = s.substring(0, 38).trim() + "…";
        return s;
    }

    private static final Map<String, String> KNOWN_HOSTS = new LinkedHashMap<>();
    static {
        KNOWN_HOSTS.put("infomaniak.ch", "FR");      // Radio Nova and many French stations
        KNOWN_HOSTS.put("radiofrance.fr", "FR");
        KNOWN_HOSTS.put("sverigesradio.se", "SE");
        KNOWN_HOSTS.put("bbci.co.uk", "GB");
        KNOWN_HOSTS.put("somafm.com", "US");
        KNOWN_HOSTS.put("yle.fi", "FI");
        KNOWN_HOSTS.put("nrk.no", "NO");
        KNOWN_HOSTS.put("dr.dk", "DK");
    }

    private static final List<String> GENERIC_ENDINGS =
            java.util.Arrays.asList("fm", "tv", "io", "me", "co", "cc", "ws", "to", "ai", "la", "eu");

    /** Country from the stream address: known broadcasters first, then the domain ending (.se, .fr …). */
    public static String guessCountry(String url) {
        String host;
        try {
            host = URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return "";
        }
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : KNOWN_HOSTS.entrySet()) {
            if (host.equals(e.getKey()) || host.endsWith("." + e.getKey())) return e.getValue();
        }
        int dot = host.lastIndexOf('.');
        String tld = dot >= 0 ? host.substring(dot + 1) : "";
        if (tld.equals("uk")) return "GB";
        // Two-letter endings are countries; .fm, .tv, .io and the like are used everywhere, so skip them.
        if (tld.length() == 2 && tld.matches("[a-z]{2}") && !GENERIC_ENDINGS.contains(tld)) {
            return tld.toUpperCase(Locale.ROOT);
        }
        return "";
    }
}
