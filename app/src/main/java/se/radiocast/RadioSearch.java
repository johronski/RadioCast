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
        public final String country;
        public final String tags;
        public final String codec;
        public final int bitrate;

        Result(String name, String url, String country, String tags, String codec, int bitrate) {
            this.name = name;
            this.url = url;
            this.country = country;
            this.tags = tags;
            this.codec = codec;
            this.bitrate = bitrate;
        }

        /** Two lines for the result list. */
        public String label() {
            StringBuilder sb = new StringBuilder(name).append('\n');
            if (!country.isEmpty()) sb.append(country).append(" · ");
            sb.append(codec.isEmpty() ? "ström" : codec.toLowerCase(Locale.ROOT));
            if (bitrate > 0) sb.append(' ').append(bitrate).append(" kbps");
            if (!tags.isEmpty()) {
                String t = tags.length() > 40 ? tags.substring(0, 40) + "…" : tags;
                sb.append(" · ").append(t.replace(",", ", "));
            }
            return sb.toString();
        }
    }

    // Servers of the same catalogue; the next one is tried if one is down.
    private static final String[] HOSTS = {
            "all.api.radio-browser.info", "de1.api.radio-browser.info",
            "nl1.api.radio-browser.info", "at1.api.radio-browser.info",
    };

    private RadioSearch() { }

    /** Searches station names, and tags too when the name gives few hits (e.g. "techno"). */
    public static List<Result> search(String query) throws IOException {
        String q = query.trim();
        List<Result> results = query("name=" + enc(q));
        if (results.size() < 10) {
            merge(results, query("tag=" + enc(q.toLowerCase(Locale.ROOT))));
        }
        return results;
    }

    /** The most popular stations in a country, e.g. "SE". */
    public static List<Result> byCountry(String countryCode) throws IOException {
        return query("countrycode=" + enc(countryCode));
    }

    /** The most popular stations with a genre tag. */
    public static List<Result> byTag(String tag) throws IOException {
        return query("tag=" + enc(tag));
    }

    private static List<Result> query(String params) throws IOException {
        String path = "/json/stations/search?" + params
                + "&hidebroken=true&order=clickcount&reverse=true&limit=40";
        IOException last = null;
        for (String host : HOSTS) {
            try {
                return parse(get("https://" + host + path));
            } catch (IOException e) {
                last = e;
            } catch (JSONException e) {
                last = new IOException("Oväntat svar från katalogen.");
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
            String name = o.optString("name", "").trim();
            if (name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) continue;
            if (!seen.add(name.toLowerCase(Locale.ROOT) + "|" + url)) continue;
            out.add(new Result(name, url,
                    o.optString("countrycode", "").trim(),
                    o.optString("tags", "").trim(),
                    o.optString("codec", "").trim(),
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
        c.setRequestProperty("User-Agent", "RadioCast/1.2");
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
