package se.radiocast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Searches the free radio-browser.info catalogue. Blocking: call off the UI thread. */
public final class RadioSearch {

    public static final class Result {
        public final String name;
        public final String url;
        public final String countryCode;
        public final String language;
        public final int bitrate;

        Result(String name, String url, String countryCode, String language, int bitrate) {
            this.name = name;
            this.url = url;
            this.countryCode = countryCode;
            this.language = language;
            this.bitrate = bitrate;
        }

        /** "Sverige · svenska · 128 kbps" */
        public String details() {
            return SearchQuery.details(countryCode, language, bitrate);
        }

        /** Two lines for the result list. */
        public String label() {
            String d = details();
            return d.isEmpty() ? name : name + "\n" + d;
        }
    }

    /** Results plus a short explanation, e.g. that a typo was corrected. */
    public static final class Outcome {
        public final List<Result> results;
        public final String note;

        Outcome(List<Result> results, String note) {
            this.results = results;
            this.note = note;
        }

        static Outcome of(List<Result> results) { return new Outcome(results, null); }
    }

    // Servers of the same catalogue; the next one is tried if one is down.
    private static final String[] HOSTS = {
            "all.api.radio-browser.info", "de1.api.radio-browser.info",
            "nl1.api.radio-browser.info", "at1.api.radio-browser.info",
    };

    private static volatile boolean vocabularyLoaded;

    private RadioSearch() { }

    /**
     * Free-text search. Understands countries and languages in the text ("sverige p3"),
     * looks in both names and genres, puts the best name matches first,
     * and retries with corrected spelling when a typo gives few hits.
     */
    public static Outcome search(String raw) throws IOException {
        SearchQuery q = SearchQuery.parse(raw);
        String filters = filters(q);
        String scope = q.filtersLabel();

        if (q.text.isEmpty()) {
            if (filters.isEmpty()) return Outcome.of(new ArrayList<>());
            return new Outcome(query(filters.substring(1)), "Populäraste: " + scope);
        }

        List<Result> results = find(q.text, filters);
        String note = scope.isEmpty() ? null : "Söker i " + scope;

        if (results.size() < 3) {
            loadVocabulary();
            String fixed = SearchQuery.correct(q.text);
            if (!fixed.equals(q.text)) {
                List<Result> better = find(fixed, filters);
                if (better.size() > results.size()) {
                    results = better;
                    note = "Visar träffar för ”" + fixed + "”" + (scope.isEmpty() ? "" : " i " + scope);
                }
            }
        }
        // Several words that found nothing together: try the longest word on its own.
        if (results.isEmpty() && q.text.contains(" ")) {
            String longest = "";
            for (String w : q.text.split(" ")) if (w.length() > longest.length()) longest = w;
            if (longest.length() >= 3) {
                results = find(longest, filters);
                if (!results.isEmpty()) note = "Inget för hela frågan. Visar träffar för ”" + longest + "”";
            }
        }
        return new Outcome(results, note);
    }

    /** Names and genres, best name matches first, then by popularity. */
    private static List<Result> find(String text, String filters) throws IOException {
        List<Result> results = query("name=" + enc(text) + filters);
        if (results.size() < 15) merge(results, query("tag=" + enc(text) + filters));
        // Stable sort: within the same match quality, popularity order is kept.
        List<Result> ranked = new ArrayList<>(results);
        ranked.sort((a, b) -> SearchQuery.score(b.name, text) - SearchQuery.score(a.name, text));
        return ranked;
    }

    private static String filters(SearchQuery q) {
        StringBuilder f = new StringBuilder();
        if (q.countryCode != null) f.append("&countrycode=").append(enc(q.countryCode));
        if (q.language != null) f.append("&language=").append(enc(q.language));
        return f.toString();
    }

    /**
     * A mix: the most popular stations for each tag, taken in turns, so every style is represented.
     * Tags match partly, so "melodic" also finds "melodic techno".
     */
    public static Outcome mix(String... tags) throws IOException {
        List<List<Result>> lists = new ArrayList<>();
        IOException error = null;
        for (String t : tags) {
            try {
                lists.add(query("tag=" + enc(t)));
            } catch (IOException e) {
                error = e;
            }
        }
        if (lists.isEmpty() && error != null) throw error;
        List<Result> out = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            for (List<Result> l : lists) {
                if (i < l.size() && urls.add(l.get(i).url)) out.add(l.get(i));
            }
        }
        return Outcome.of(out);
    }

    /** The most popular stations in a country, e.g. "SE". */
    public static Outcome byCountry(String countryCode) throws IOException {
        return Outcome.of(query("countrycode=" + enc(countryCode)));
    }

    /** The most popular stations with a genre tag. */
    public static Outcome byTag(String tag) throws IOException {
        return Outcome.of(query("tag=" + enc(tag)));
    }

    /** Teaches the spelling corrector the catalogue's genres and popular station names. Once. */
    private static void loadVocabulary() {
        if (vocabularyLoaded) return;
        vocabularyLoaded = true;
        try {
            JSONArray tags = new JSONArray(getAny("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=1500"));
            List<String> words = new ArrayList<>();
            for (int i = 0; i < tags.length(); i++) words.add(tags.getJSONObject(i).optString("name", ""));
            JSONArray top = new JSONArray(getAny("/json/stations/topclick/500?hidebroken=true"));
            for (int i = 0; i < top.length(); i++) words.add(top.getJSONObject(i).optString("name", ""));
            SearchQuery.addVocabulary(words);
        } catch (IOException | JSONException ignored) {
            // The built-in word list still works.
        }
    }

    private static List<Result> query(String params) throws IOException {
        String path = "/json/stations/search?" + params
                + "&hidebroken=true&order=clickcount&reverse=true&limit=40";
        try {
            return parse(getAny(path));
        } catch (JSONException e) {
            throw new IOException("Oväntat svar från katalogen.");
        }
    }

    private static String getAny(String path) throws IOException {
        IOException last = null;
        for (String host : HOSTS) {
            try {
                return get("https://" + host + path);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("Radiokatalogen svarar inte. Kontrollera internet och försök igen.", last);
    }

    static List<Result> parse(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Result> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String url = o.optString("url_resolved", "").trim();
            if (url.isEmpty()) url = o.optString("url", "").trim();
            String name = o.optString("name", "").trim().replaceAll("\\s+", " ");
            if (name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) continue;
            if (!seen.add(name.toLowerCase(Locale.ROOT) + "|" + url)) continue;
            out.add(new Result(name, url,
                    o.optString("countrycode", "").trim(),
                    o.optString("language", "").trim(),
                    o.optInt("bitrate", 0)));
        }
        return out;
    }

    private static void merge(List<Result> into, List<Result> more) {
        Set<String> urls = new HashSet<>();
        for (Result r : into) urls.add(r.url);
        for (Result r : more) if (urls.add(r.url)) into.add(r);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "RadioCast/1.4");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
