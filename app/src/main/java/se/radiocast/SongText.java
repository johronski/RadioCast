package se.radiocast;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turning what radio servers send about the current song into clean text, or nothing. */
public final class SongText {

    private SongText() { }

    private static final Pattern STREAM_TITLE = Pattern.compile("StreamTitle='(.*?)';", Pattern.DOTALL);
    private static final String[] JUNK = {
            "unknown", "advert", "advertisement", "commercial", "jingle", "publicité", "pub", "promo",
            "station id", "id", "live", "en direct", "direct", "radio", "music", "musique", "untitled", "n/a", "null",
    };

    /** UTF-8 if the bytes are valid UTF-8, otherwise Latin-1 (older servers send that). */
    public static String decode(byte[] bytes, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
        }
    }

    /** The song in an ICY metadata block ("StreamTitle='Artist - Title';"), or null. */
    public static String streamTitle(String metadata) {
        if (metadata == null) return null;
        Matcher m = STREAM_TITLE.matcher(metadata);
        return m.find() ? m.group(1).replace("\\'", "'") : null;
    }

    /**
     * Clean song text ("Artist – Title"), or null when it is empty, just the station's name,
     * an address, or the usual filler ("Unknown", "Jingle", "Pub"…).
     */
    public static String clean(String raw, String stationName) {
        if (raw == null) return null;
        String t = raw.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[-–—|:•\\s]+|[-–—|:•\\s]+$", "").trim();
        if (t.length() < 3) return null;
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("http") || low.startsWith("www.") || low.matches(".*\\.(com|fr|se|net|org)(/.*)?$")) return null;
        for (String j : JUNK) if (low.equals(j)) return null;
        if (low.matches("(?i)(advert|commercial|jingle|publicit)[^a-z]?.*")) return null;
        // Normalise "Artist - Title" to "Artist – Title".
        t = t.replaceAll("\\s+[-–—]\\s+", " – ");
        boolean looksLikeSong = t.contains(" – ");
        if (looksLikeSong && stationName != null) {
            // "Nova – le grand mix": the station's own slogan, not a song.
            String artist = simplify(split(t)[0]);
            String shortName = simplify(StationGroups.shortName(stationName));
            String bare = simplify(stationName.replaceAll("(?i)\\bradio\\b", ""));
            if (!artist.isEmpty() && (artist.equals(shortName) || artist.equals(simplify(stationName)) || artist.equals(bare))) return null;
        }
        if (stationName != null && !looksLikeSong) {
            String st = simplify(stationName), tt = simplify(t);
            String shortName = simplify(StationGroups.shortName(stationName));
            if (tt.isEmpty() || st.contains(tt)) return null;
            if (!shortName.isEmpty() && tt.contains(shortName) && tt.length() <= shortName.length() + 12) return null;
        }
        return t;
    }

    /** Lowercase letters and digits only, for comparing names. */
    static String simplify(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** "Artist – Title" as artist and title (title alone when there is no separator). */
    public static String[] split(String song) {
        int i = song.indexOf(" – ");
        if (i < 0) return new String[]{"", song};
        return new String[]{song.substring(0, i).trim(), song.substring(i + 3).trim()};
    }
}
