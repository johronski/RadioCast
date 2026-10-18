package se.radiocast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Understands what the user typed in the search box:
 * "sverige p3" means the name "p3" among Swedish stations, "franska nyheter" means French-language news.
 * Also fixes common typos ("tehcno" becomes "techno"). Pure Java, no Android, so it can be unit tested.
 */
public final class SearchQuery {

    /** ISO country code such as "SE", or null. */
    public final String countryCode;
    /** Language as the catalogue writes it, such as "swedish", or null. */
    public final String language;
    /** What is left to search for in names and genres. May be empty. */
    public final String text;

    private SearchQuery(String countryCode, String language, String text) {
        this.countryCode = countryCode;
        this.language = language;
        this.text = text;
    }

    // ------------------------------------------------------------------ words

    private static final Map<String, String> COUNTRIES = new HashMap<>();
    private static final Map<String, String> LANGUAGES = new HashMap<>();
    private static final Map<String, String> SWEDISH_LANGUAGE_NAMES = new HashMap<>();
    /** Swedish words for genres, mapped to the catalogue's (English) tags. */
    private static final Map<String, String> SYNONYMS = new HashMap<>();
    private static final Set<String> VOCABULARY = Collections.synchronizedSet(new LinkedHashSet<>());

    static {
        String[][] countries = {
                {"SE", "sverige", "sweden", "svensk", "svenskt"}, {"NO", "norge", "norway", "norsk"},
                {"DK", "danmark", "denmark", "dansk"}, {"FI", "finland", "finsk"}, {"IS", "iceland", "isländsk"},
                {"FR", "frankrike", "france", "fransk", "franskt"}, {"DE", "tyskland", "germany", "deutschland", "tysk"},
                {"GB", "england", "storbritannien", "britain", "uk", "brittisk"}, {"US", "usa", "amerika", "amerikansk"},
                {"ES", "spanien", "spain", "spansk"}, {"IT", "italien", "italy", "italiensk"},
                {"NL", "nederländerna", "holland", "netherlands"}, {"BE", "belgien", "belgium"},
                {"CH", "schweiz", "switzerland"}, {"AT", "österrike", "austria"}, {"CA", "kanada", "canada"},
                {"PL", "polen", "poland"}, {"EE", "estland", "estonia"}, {"IE", "irland", "ireland"},
                {"PT", "portugal"}, {"GR", "grekland", "greece"}, {"JP", "japan"}, {"BR", "brasilien", "brazil"},
                {"AU", "australien", "australia"},
        };
        for (String[] c : countries) for (int i = 1; i < c.length; i++) COUNTRIES.put(fold(c[i]), c[0]);

        String[][] languages = {
                {"swedish", "svenska", "swedish"}, {"english", "engelska", "english"}, {"french", "franska", "french"},
                {"german", "tyska", "german"}, {"spanish", "spanska", "spanish"}, {"norwegian", "norska", "norwegian"},
                {"danish", "danska", "danish"}, {"finnish", "finska", "finnish"}, {"italian", "italienska", "italian"},
                {"dutch", "nederländska", "dutch"}, {"arabic", "arabiska", "arabic"}, {"portuguese", "portugisiska", "portuguese"},
                {"polish", "polska", "polish"}, {"russian", "ryska", "russian"}, {"greek", "grekiska", "greek"},
                {"turkish", "turkiska", "turkish"}, {"persian", "persiska", "persian"}, {"japanese", "japanska", "japanese"},
        };
        for (String[] l : languages) {
            LANGUAGES.put(fold(l[1]), l[0]);
            LANGUAGES.put(fold(l[2]), l[0]);
            SWEDISH_LANGUAGE_NAMES.put(l[0], l[1]);
        }

        String[][] synonyms = {
                {"elektroniskt", "electronic"}, {"elektronisk", "electronic"}, {"elektro", "electro"},
                {"klassiskt", "classical"}, {"klassisk", "classical"}, {"nyheter", "news"}, {"prat", "talk"},
                {"julmusik", "christmas"}, {"jul", "christmas"}, {"hiphop", "hip hop"}, {"dansmusik", "dance"},
                {"barn", "kids"}, {"lugnt", "chillout"}, {"lugn", "chillout"}, {"sport", "sports"},
        };
        for (String[] s : synonyms) SYNONYMS.put(fold(s[0]), s[1]);

        addVocabulary(Arrays.asList(
                // genres
                "electronic", "electro", "techno", "house", "deep", "trance", "dubstep", "drum", "bass", "ambient",
                "chillout", "chill", "lounge", "downtempo", "jazz", "blues", "rock", "pop", "hip", "hop", "rap",
                "classical", "country", "reggae", "metal", "punk", "soul", "funk", "disco", "synthpop", "synthwave",
                "indie", "alternative", "oldies", "news", "talk", "sports", "schlager", "dansband", "latin", "afro",
                "afrobeats", "minimal", "melodic", "progressive", "dance", "hits", "christmas", "gospel", "folk",
                "world", "phonk", "lofi", "garage", "breakbeat", "jungle", "hardstyle", "psytrance", "experimental",
                "acoustic", "instrumental", "soundtrack", "kids", "comedy", "culture", "public",
                // stations and broadcasters
                "radio", "nova", "mouv", "fip", "france", "inter", "culture", "sveriges", "megapol", "bandit",
                "rix", "star", "lugna", "favoriter", "skyrock", "fun", "couleur", "tipik", "nrj", "bbc", "kexp",
                "somafm", "groove", "salad", "nostalgie", "cherie", "virgin", "europe", "musiq", "classic",
                "vinyl", "yle", "dr", "nrk", "deutschlandfunk", "jazzradio", "ibiza", "global"));
    }

    /** Adds words the corrector may suggest, e.g. tags and station names from the catalogue. */
    public static void addVocabulary(Iterable<String> words) {
        for (String w : words) {
            for (String part : fold(w).split("[^a-z0-9]+")) {
                if (part.length() >= 3 && part.length() <= 20) VOCABULARY.add(part);
            }
        }
    }

    // ------------------------------------------------------------------ parsing

    public static SearchQuery parse(String raw) {
        String country = null, language = null;
        List<String> rest = new ArrayList<>();
        for (String word : fold(raw).split("\\s+")) {
            if (word.isEmpty()) continue;
            String syn = SYNONYMS.get(word);
            if (syn != null) { rest.add(syn); continue; }
            // A word that is a genre or station name stays one ("trance" is not "france").
            if (VOCABULARY.contains(word) && !COUNTRIES.containsKey(word) && !LANGUAGES.containsKey(word)) {
                rest.add(word);
                continue;
            }
            // Exact words first, so "franska" is the language, not a misspelt "fransk" (France).
            if (country == null && COUNTRIES.containsKey(word)) { country = COUNTRIES.get(word); continue; }
            if (language == null && LANGUAGES.containsKey(word)) { language = LANGUAGES.get(word); continue; }
            if (country == null) {
                String cc = fuzzy(COUNTRIES, word);
                if (cc != null) { country = cc; continue; }
            }
            if (language == null) {
                String lang = fuzzy(LANGUAGES, word);
                if (lang != null) { language = lang; continue; }
            }
            rest.add(word);
        }
        // "france inter" is a station, not "inter" in France.
        String text = String.join(" ", rest);
        if (country != null && text.equals("inter") && raw.toLowerCase(Locale.ROOT).contains("france inter")) {
            country = null;
            text = "france inter";
        }
        return new SearchQuery(country, language, text);
    }

    /** One typo away, for words of five letters or more ("sverge", "frankrke"). */
    private static String fuzzy(Map<String, String> words, String word) {
        if (word.length() < 5) return null;
        String best = null;
        int allowed = word.length() >= 8 ? 2 : 1;
        for (Map.Entry<String, String> e : words.entrySet()) {
            if (e.getKey().length() >= 5 && distance(word, e.getKey()) <= allowed) {
                if (best != null && !best.equals(e.getValue())) return null; // ambiguous
                best = e.getValue();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ typo correction

    /**
     * Replaces words that look like typos with the closest known word.
     * Returns the same text if nothing needs fixing.
     */
    public static String correct(String text) {
        String[] words = text.split(" ");
        boolean changed = false;
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.length() < 4 || VOCABULARY.contains(w) || w.matches(".*\\d.*")) continue;
            int allowed = w.length() >= 7 ? 2 : 1;
            String best = null;
            int bestD = allowed + 1;
            synchronized (VOCABULARY) {
                for (String v : VOCABULARY) {
                    if (Math.abs(v.length() - w.length()) > allowed) continue;
                    int d = distance(w, v);
                    // Same distance: prefer the word sharing the first letter, then the earlier (more common) word.
                    if (d < bestD || (d == bestD && best != null && best.charAt(0) != w.charAt(0) && v.charAt(0) == w.charAt(0))) {
                        bestD = d;
                        best = v;
                    }
                }
            }
            if (best != null && bestD <= allowed) {
                words[i] = best;
                changed = true;
            }
        }
        return changed ? String.join(" ", words) : text;
    }

    /** Edit distance where swapping two neighbouring letters counts as one typo. */
    static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    // ------------------------------------------------------------------ ranking

    /**
     * How well a station name matches the search text: 3 = every word appears as a whole word
     * ("p3" in "Sveriges Radio P3"), 2 = every word starts a word, 1 = appears somewhere (as in "MP3"), 0 = not at all.
     */
    public static int score(String name, String text) {
        if (text.isEmpty()) return 0;
        List<String> tokens = Arrays.asList(fold(name).split("[^a-z0-9]+"));
        String flat = fold(name);
        int worst = 3;
        for (String w : text.split(" ")) {
            int s;
            if (tokens.contains(w)) s = 3;
            else if (startsAWord(tokens, w)) s = 2;
            else if (flat.contains(w)) s = 1;
            else s = 0;
            worst = Math.min(worst, s);
        }
        return worst;
    }

    private static boolean startsAWord(List<String> tokens, String w) {
        for (String t : tokens) if (t.startsWith(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ display

    /** "SE" becomes "Sverige". */
    public static String countryName(String code) {
        if (code == null || code.length() != 2) return "";
        String name = new Locale("", code.toUpperCase(Locale.ROOT)).getDisplayCountry(new Locale("sv"));
        return name.isEmpty() || name.equalsIgnoreCase(code) ? code.toUpperCase(Locale.ROOT) : name;
    }

    /** "swedish,english" becomes "svenska". */
    public static String languageName(String catalogueLanguage) {
        if (catalogueLanguage == null || catalogueLanguage.trim().isEmpty()) return "";
        String first = catalogueLanguage.split("[,;/]")[0].trim().toLowerCase(Locale.ROOT);
        String sv = SWEDISH_LANGUAGE_NAMES.get(first);
        return sv != null ? sv : first;
    }

    /** "Sverige · svenska · 128 kbps", leaving out what is unknown. */
    public static String details(String countryCode, String language, int bitrate) {
        List<String> parts = new ArrayList<>();
        String c = countryName(countryCode);
        if (!c.isEmpty()) parts.add(c);
        String l = languageName(language);
        if (!l.isEmpty()) parts.add(l);
        if (bitrate > 0) parts.add(bitrate + " kbps");
        return String.join(" · ", parts);
    }

    /** What the search is limited to, for the result header, e.g. "Sverige, svenska". */
    public String filtersLabel() {
        List<String> parts = new ArrayList<>();
        if (countryCode != null) parts.add(countryName(countryCode));
        if (language != null) parts.add(languageName(language));
        return String.join(", ", parts);
    }

    /** Lower case, accents removed (ö stays readable as o for matching), spaces tidied. */
    static String fold(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).trim(), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").replaceAll("\\s+", " ");
    }
}
