package se.radiocast;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out which song is playing. For each channel it looks for a source that works, remembers it,
 * notices when it stops working and looks again:
 * <ol>
 * <li>the phone's own player, when the phone plays (free: the song comes with the stream)</li>
 * <li>a known now-playing address for the stream's provider (from meta-sources.json in the repo)</li>
 * <li>the radio server's status page (Icecast or Shoutcast)</li>
 * <li>a short look into the stream: read up to the first song block, then hang up</li>
 * </ol>
 * The short look runs only while the app is on screen, unless the user wants the song on the lock screen.
 */
public final class SongWatcher {

    public static final class Song {
        public final String text, cover, stationUrl;
        Song(String text, String cover, String stationUrl) {
            this.text = text; this.cover = cover; this.stationUrl = stationUrl;
        }
        boolean same(Song o) {
            return o != null && text.equals(o.text) && eq(cover, o.cover) && stationUrl.equals(o.stationUrl);
        }
        private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
    }

    public interface Listener { void onSong(Song song); }

    static final String PATTERNS_URL = "https://raw.githubusercontent.com/johronski/RadioCast/main/meta-sources.json";
    private static final long POLL_MS = 25_000;
    private static final long STUCK_MS = 20 * 60_000;
    private static final long BAD_FOR_MS = 24 * 3600_000L;
    private static final long NONE_RETRY_MS = 6 * 3600_000L;
    private static final String[] SOURCES = {"pattern", "icecast", "shoutcast", "icy"};

    private final Context app;
    private final Radio radio;
    private final SharedPreferences prefs;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "songs");
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> covers = new LinkedHashMap<String, String>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 60; }
    };

    private volatile Song song;
    private volatile boolean foreground;
    private volatile String playerTitle, playerUrl;

    // Worker-thread state
    private String watchedUrl;
    private long nextFetch;
    private int misses;
    private String lastText;
    private long lastChange;
    private JSONArray patterns;
    private long patternsCheckedAt;

    SongWatcher(Context context, Radio radio) {
        this.app = context.getApplicationContext();
        this.radio = radio;
        this.prefs = app.getSharedPreferences("radiocast", Context.MODE_PRIVATE);
        exec.scheduleWithFixedDelay(this::tickSafely, 2, 3, TimeUnit.SECONDS);
    }

    public Song current() { return song; }
    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** The app is on screen (the short look into the stream is allowed then). */
    public void setForeground(boolean on) {
        foreground = on;
        if (on) poke();
    }

    /** Show the song on the lock screen while casting, also when the app is not open (uses more data). */
    public boolean lockScreen() { return prefs.getBoolean("song_lockscreen", false); }
    public void setLockScreen(boolean on) { prefs.edit().putBoolean("song_lockscreen", on).apply(); poke(); }

    /** Something changed (new station, playback started): look now instead of at the next poll. */
    public void poke() {
        exec.execute(() -> { nextFetch = 0; tickSafely(); });
    }

    /** The phone's player read a song title from the stream it plays. */
    void onPlayerTitle(String url, String title) {
        playerUrl = url;
        playerTitle = title;
        poke();
    }

    // ================================================================ worker thread

    private void tickSafely() {
        try { tick(); } catch (RuntimeException ignored) { }
    }

    private void tick() {
        Station s = radio.nowPlaying;
        boolean phone = PlaybackService.isActive();
        boolean speaker = radio.speakerMusicActive();
        if (s == null || (!phone && !speaker)) {
            if (watchedUrl != null) { watchedUrl = null; publish(null); }
            return;
        }
        String url = phone && PlaybackService.currentStream() != null ? PlaybackService.currentStream() : s.url;
        if (!url.equals(watchedUrl)) {
            watchedUrl = url;
            nextFetch = 0;
            misses = 0;
            lastText = null;
            if (!phone) { playerTitle = null; playerUrl = null; }
            publish(null);
        }

        // On the phone the song comes with the stream: no extra traffic.
        if (phone && url.equals(playerUrl)) {
            String text = SongText.clean(playerTitle, s.name);
            if (text != null) {
                publish(new Song(text, coverFor(text, null), url));
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (now < nextFetch) return;
        nextFetch = now + POLL_MS;

        boolean mayPeek = !phone && (foreground || lockScreen());
        Found f = lookup(url, s.name, phone, mayPeek);
        if (f == null) {
            // Keep the last song through a short gap; clear it when nothing comes for a while.
            if (++misses >= 3) publish(null);
            return;
        }
        misses = 0;
        if (!f.text.equals(lastText)) {
            lastText = f.text;
            lastChange = now;
        } else if (now - lastChange > STUCK_MS) {
            // The same title for a long time: maybe the source froze. See if another source disagrees.
            lastChange = now;
            Found other = lookupOther(url, s.name, phone, mayPeek, f.source);
            if (other != null && !other.text.equals(f.text)) {
                remember(url, other.source, f.source);
                f = other;
                lastText = f.text;
            }
        }
        publish(new Song(f.text, coverFor(f.text, f.cover), url));
    }

    private static final class Found {
        final String text, cover, source;
        Found(String text, String cover, String source) { this.text = text; this.cover = cover; this.source = source; }
    }

    /** Uses the remembered source; finds a new one when there is none or it keeps failing. */
    private Found lookup(String url, String stationName, boolean phone, boolean mayPeek) {
        JSONObject st = state(url);
        long now = System.currentTimeMillis();
        String src = st.optString("src", "");
        if (src.equals("none")) {
            if (now < st.optLong("retry")) return null;
            src = "";
        }
        if (!src.isEmpty() && allowed(src, phone, mayPeek)) {
            Found f = fetch(src, url, stationName);
            if (f != null) {
                put(st, "fails", 0);
                save(url, st);
                return f;
            }
            int fails = st.optInt("fails") + 1;
            put(st, "fails", fails);
            if (fails < 3) { save(url, st); return null; } // a hiccup: try the same source next time
            markBad(st, src, now + BAD_FOR_MS);
            put(st, "src", "");
            put(st, "fails", 0);
        }
        // Look for a source that works.
        boolean triedAll = true;
        for (String c : SOURCES) {
            if (c.equals(src) || isBad(st, c, now)) continue;
            if (!allowed(c, phone, mayPeek)) { triedAll = false; continue; }
            Found f = fetch(c, url, stationName);
            if (f != null) {
                put(st, "src", c);
                put(st, "fails", 0);
                save(url, st);
                return f;
            }
        }
        if (triedAll) {
            put(st, "src", "none");
            put(st, "retry", now + NONE_RETRY_MS);
        }
        save(url, st);
        return null;
    }

    private Found lookupOther(String url, String stationName, boolean phone, boolean mayPeek, String except) {
        long now = System.currentTimeMillis();
        JSONObject st = state(url);
        for (String c : SOURCES) {
            if (c.equals(except) || isBad(st, c, now) || !allowed(c, phone, mayPeek)) continue;
            Found f = fetch(c, url, stationName);
            if (f != null) return f;
        }
        return null;
    }

    private void remember(String url, String source, String frozen) {
        JSONObject st = state(url);
        put(st, "src", source);
        put(st, "fails", 0);
        markBad(st, frozen, System.currentTimeMillis() + BAD_FOR_MS);
        save(url, st);
    }

    /** The short look into the stream is not needed while the phone plays, and only allowed when wanted. */
    private static boolean allowed(String source, boolean phone, boolean mayPeek) {
        return !source.equals("icy") || (!phone && mayPeek);
    }

    private Found fetch(String source, String url, String stationName) {
        try {
            Found raw;
            switch (source) {
                case "pattern": raw = fromPattern(url); break;
                case "icecast": raw = fromIcecast(url); break;
                case "shoutcast": raw = fromShoutcast(url); break;
                case "icy": raw = fromStream(url); break;
                default: return null;
            }
            if (raw == null) return null;
            String text = SongText.clean(raw.text, stationName);
            return text == null ? null : new Found(text, raw.cover, source);
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- sources

    /** A known now-playing address for this stream's provider (meta-sources.json). */
    private Found fromPattern(String url) throws IOException, JSONException {
        JSONArray list = patterns();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.getJSONObject(i);
            Matcher m = Pattern.compile(p.getString("match")).matcher(url);
            if (!m.find()) continue;
            String api = p.getString("url");
            for (int g = m.groupCount(); g >= 1; g--) {
                api = api.replace("$" + g, m.group(g) != null ? m.group(g) : "");
            }
            JSONObject json = new JSONObject(get(api, 512 * 1024));
            String text = fill(p.getString("song"), json);
            String cover = p.has("cover") ? stringAt(json, p.getString("cover")) : null;
            return text == null ? null : new Found(text, cover, "pattern");
        }
        return null;
    }

    /** Icecast's status page lists every stream on the server with its current title. */
    private Found fromIcecast(String url) throws IOException, JSONException {
        URL u = new URL(url);
        JSONObject stats = new JSONObject(get(base(u) + "/status-json.xsl", 512 * 1024)).getJSONObject("icestats");
        Object src = stats.opt("source");
        List<JSONObject> sources = new ArrayList<>();
        if (src instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) src).length(); i++) sources.add(((JSONArray) src).getJSONObject(i));
        } else if (src instanceof JSONObject) {
            sources.add((JSONObject) src);
        }
        JSONObject match = null;
        for (JSONObject o : sources) {
            if (o.optString("listenurl").endsWith(u.getPath())) { match = o; break; }
        }
        if (match == null && sources.size() == 1) match = sources.get(0);
        if (match == null) return null;
        String title = match.optString("title").trim(), artist = match.optString("artist").trim();
        String text = artist.isEmpty() ? title : artist + " – " + title;
        return text.isEmpty() ? null : new Found(text, null, "icecast");
    }

    /** Shoutcast servers answer with just the current title. */
    private Found fromShoutcast(String url) throws IOException {
        String body = get(base(new URL(url)) + "/currentsong?sid=1", 4096).trim();
        if (body.isEmpty() || body.contains("<")) return null;
        return new Found(body, null, "shoutcast");
    }

    /** Reads the stream up to its first song block (the current song), then hangs up. */
    private Found fromStream(String url) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Icy-MetaData", "1");
        try {
            if (c.getResponseCode() != 200) return null;
            int metaint;
            try { metaint = Integer.parseInt(c.getHeaderField("icy-metaint").trim()); }
            catch (RuntimeException e) { return null; } // the stream carries no song titles
            if (metaint <= 0 || metaint > 256 * 1024) return null;
            InputStream in = new BufferedInputStream(c.getInputStream());
            for (int block = 0; block < 2; block++) {
                skipFully(in, metaint);
                int len = in.read();
                if (len < 0) return null;
                len *= 16;
                if (len == 0) continue; // no change announced in this block; the next one usually has it
                byte[] meta = new byte[len];
                readFully(in, meta);
                String title = SongText.streamTitle(SongText.decode(meta, len));
                return title == null ? null : new Found(title, null, "icy");
            }
            return null;
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- cover art

    /** The provider's cover, or one from Apple's open music search when the artist matches. */
    private String coverFor(String text, String given) {
        if (given != null && given.startsWith("http")) return given;
        if (!text.contains(" – ")) return null;
        synchronized (covers) {
            if (covers.containsKey(text)) {
                String c = covers.get(text);
                return c.isEmpty() ? null : c;
            }
        }
        String found = "";
        try {
            String[] parts = SongText.split(text);
            JSONObject res = new JSONObject(get("https://itunes.apple.com/search?entity=song&limit=3&term="
                    + URLEncoder.encode(text.replace(" – ", " "), "UTF-8"), 256 * 1024));
            JSONArray results = res.optJSONArray("results");
            String artist = SongText.simplify(parts[0]);
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String a = SongText.simplify(r.optString("artistName"));
                if (artist.isEmpty() || a.isEmpty() || !(a.contains(artist) || artist.contains(a))) continue;
                String art = r.optString("artworkUrl100");
                if (!art.isEmpty()) { found = art.replace("100x100bb", "300x300bb"); break; }
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            // No cover: the station's logo is shown instead.
        }
        synchronized (covers) { covers.put(text, found); }
        return found.isEmpty() ? null : found;
    }

    // ---------------------------------------------------------------- known sources file

    /** The list of known now-playing addresses: from the repo (checked daily), else the copy in the app. */
    private JSONArray patterns() {
        long now = System.currentTimeMillis();
        if (patterns == null) {
            String saved = prefs.getString("meta_sources", null);
            patterns = parsePatterns(saved);
            if (patterns == null) patterns = parsePatterns(asset());
            if (patterns == null) patterns = new JSONArray();
            patternsCheckedAt = prefs.getLong("meta_sources_at", 0);
        }
        if (now - patternsCheckedAt > 24 * 3600_000L) {
            patternsCheckedAt = now;
            prefs.edit().putLong("meta_sources_at", now).apply();
            try {
                String body = get(PATTERNS_URL, 256 * 1024);
                JSONArray fresh = parsePatterns(body);
                if (fresh != null) {
                    patterns = fresh;
                    prefs.edit().putString("meta_sources", body).apply();
                }
            } catch (IOException ignored) {
                // Keep what we have.
            }
        }
        return patterns;
    }

    private static JSONArray parsePatterns(String json) {
        if (json == null) return null;
        try { return new JSONObject(json).getJSONArray("sources"); }
        catch (JSONException e) { return null; }
    }

    private String asset() {
        try (InputStream in = app.getAssets().open("meta-sources.json")) {
            return new String(readAll(in, 256 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** "{a.b} – {c.d[0].e}" filled from the JSON; parts that are missing are left out. */
    static String fill(String template, JSONObject json) {
        Matcher m = Pattern.compile("\\{([^}]+)\\}").matcher(template);
        List<String> values = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        boolean missing = false;
        while (m.find()) {
            String v = stringAt(json, m.group(1));
            if (v == null || v.trim().isEmpty()) { missing = true; v = ""; }
            else values.add(v.trim());
            m.appendReplacement(sb, Matcher.quoteReplacement(v.trim()));
        }
        m.appendTail(sb);
        if (values.isEmpty()) return null;
        return missing ? String.join(" – ", values) : sb.toString();
    }

    /** The string at a path like "now.firstLine.title" or "results[0].name", or null. */
    static String stringAt(JSONObject json, String path) {
        Object cur = json;
        for (String part : path.split("\\.")) {
            Matcher m = Pattern.compile("([^\\[]*)(?:\\[(\\d+)\\])?").matcher(part);
            if (!m.matches()) return null;
            if (!m.group(1).isEmpty()) {
                if (!(cur instanceof JSONObject)) return null;
                cur = ((JSONObject) cur).opt(m.group(1));
            }
            if (m.group(2) != null) {
                if (!(cur instanceof JSONArray)) return null;
                cur = ((JSONArray) cur).opt(Integer.parseInt(m.group(2)));
            }
            if (cur == null || cur == JSONObject.NULL) return null;
        }
        return cur instanceof String ? (String) cur : (cur instanceof Number ? cur.toString() : null);
    }

    // ---------------------------------------------------------------- remembered sources per stream

    private JSONObject state(String url) {
        try {
            JSONObject all = new JSONObject(prefs.getString("song_sources", "{}"));
            JSONObject st = all.optJSONObject(url);
            return st != null ? st : new JSONObject();
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void save(String url, JSONObject st) {
        try {
            JSONObject all = new JSONObject(prefs.getString("song_sources", "{}"));
            all.put(url, st);
            // Keep it small: forget the oldest streams beyond 100.
            while (all.length() > 100) {
                Iterator<String> it = all.keys();
                if (!it.hasNext()) break;
                all.remove(it.next());
            }
            prefs.edit().putString("song_sources", all.toString()).apply();
        } catch (JSONException ignored) { }
    }

    private static boolean isBad(JSONObject st, String source, long now) {
        JSONObject bad = st.optJSONObject("bad");
        return bad != null && bad.optLong(source) > now;
    }

    private static void markBad(JSONObject st, String source, long until) {
        try {
            JSONObject bad = st.optJSONObject("bad");
            if (bad == null) { bad = new JSONObject(); st.put("bad", bad); }
            bad.put(source, until);
        } catch (JSONException ignored) { }
    }

    private static void put(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (JSONException ignored) { }
    }

    // ---------------------------------------------------------------- publishing

    private void publish(Song s) {
        Song old = song;
        if (s == null ? old == null : s.same(old)) return;
        song = s;
        for (Listener l : listeners) l.onSong(s);
        CastControlService.refresh(app);
        PlaybackService.refreshSong();
    }

    // ---------------------------------------------------------------- http

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "RadioCast/2.6 (Android)");
        return c;
    }

    static String get(String url, int max) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] b = readAll(c.getInputStream(), max);
            return SongText.decode(b, b.length);
        } finally {
            c.disconnect();
        }
    }

    private static String base(URL u) {
        return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("För stort svar");
        }
        return out.toByteArray();
    }

    private static void skipFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, n));
            if (r < 0) throw new IOException("Strömmen tog slut");
            n -= r;
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new IOException("Strömmen tog slut");
            off += r;
        }
    }

}
