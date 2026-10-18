package se.radiocast;

import java.util.Locale;

/** A radio channel in the user's list (or one being tried out from the search). */
public final class Station {
    public final String name;
    public final String url;
    /** ISO country code such as "SE", or "" when unknown. */
    public final String countryCode;
    public final String language;
    public final int bitrate;
    /** Address of the channel's logo, or "" when there is none. */
    public final String logo;
    /** Short genre for the list, e.g. "Hiphop", or "". */
    public final String genre;

    public Station(String name, String url, String countryCode, String language, int bitrate) {
        this(name, url, countryCode, language, bitrate, "", "");
    }

    public Station(String name, String url, String countryCode, String language, int bitrate,
                   String logo, String genre) {
        this.name = name;
        this.url = url;
        this.countryCode = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        this.language = language == null ? "" : language;
        this.bitrate = bitrate;
        this.logo = logo == null ? "" : logo.trim();
        this.genre = genre == null ? "" : genre.trim();
    }

    /** For channels saved without a country (older versions, own links): a best guess from the address. */
    public static Station withGuessedCountry(String name, String url) {
        return new Station(name, url, StationGroups.guessCountry(url), "", 0);
    }

    /** The line under the name in the list: "Hiphop · 128 kbps", "128 kbps" or "". */
    public String subtitle() {
        StringBuilder b = new StringBuilder(genre);
        if (bitrate > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(bitrate).append(" kbps");
        }
        return b.toString();
    }

    /** Up to five letters for the fallback logo: "Radio Nova" → "NOVA", "Sveriges Radio P3" → "P3". */
    public static String initials(String name) {
        String n = StationGroups.shortName(name).toUpperCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}' ]", " ").trim();
        if (n.isEmpty()) return "?";
        String[] words = n.split("\\s+");
        // Skip generic words so "Radio Nova" shows NOVA and "Sveriges Radio P3" shows P3.
        java.util.List<String> keep = new java.util.ArrayList<>();
        for (String w : words) {
            if (!w.equals("RADIO") && !w.equals("SVERIGES") && !w.equals("FM") && !w.equals("THE")) keep.add(w);
        }
        if (keep.isEmpty()) keep.add(words[0]);
        String last = keep.get(keep.size() - 1);
        if (keep.size() == 1 || last.length() <= 3) {
            String w = keep.size() == 1 ? keep.get(0) : last;
            return w.length() <= 5 ? w : w.substring(0, 2);
        }
        StringBuilder b = new StringBuilder();
        for (String w : keep) if (b.length() < 3) b.append(w.charAt(0));
        return b.toString();
    }

    /** Which of the five pastel pairs a fallback logo gets; stable for the same name. */
    public static int paletteIndex(String name) {
        int h = 0;
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) h = h * 31 + c;
        return Math.floorMod(h, 5);
    }

    /** "techno,house" → "Techno". */
    public static String genreFromTags(String tags) {
        String g = SearchQuery.genres(tags, 1);
        return g.isEmpty() ? "" : g.substring(0, 1).toUpperCase(Locale.ROOT) + g.substring(1);
    }
}
