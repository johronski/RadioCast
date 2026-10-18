package se.radiocast;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Google Cast (CASTV2) client.
 * Talks directly to a Chromecast on port 8009 and plays a stream URL
 * in Google's Default Media Receiver. Blocking methods: call off the UI thread.
 */
public class CastClient {

    public interface Listener {
        void onMessage(String text);
        void onDisconnected(String reason);
        /** The speaker's player changed state: PLAYING, BUFFERING, PAUSED or IDLE. */
        default void onPlayerState(String state) { }
        /** Our player on the speaker was started (true) or closed (false). */
        default void onPlayerRunning(boolean running) { }
        /** The speaker's volume, 0–1, whenever it reports it (also when changed elsewhere). */
        default void onVolume(double level) { }
        /** What the speaker's player has loaded: the stream address and its title. */
        default void onMedia(String url, String title) { }
    }

    /** What a speaker is doing, from {@link #probe}. */
    public static final class SpeakerStatus {
        /** Nothing is playing (or only the idle screen). */
        public final boolean idle;
        /** Our radio player (Google's media player) is running. */
        public final boolean ours;
        /** Short text such as "Spotify" or "Radio Nova". Empty when idle. */
        public final String text;

        SpeakerStatus(boolean idle, boolean ours, String text) {
            this.idle = idle;
            this.ours = ours;
            this.text = text;
        }
    }

    static final String DEFAULT_MEDIA_RECEIVER = "CC1AD845";
    static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    static final String SENDER = "sender-0";
    static final String RECEIVER = "receiver-0";

    private final String host;
    private final int port;
    private final Listener listener;
    private final AtomicInteger requestId = new AtomicInteger(1);
    private final Object writeLock = new Object();

    private SSLSocket socket;
    private OutputStream out;
    private volatile boolean running;
    private volatile String transportId;
    private volatile String sessionId;
    private volatile CountDownLatch appLatch = new CountDownLatch(1);
    private volatile CountDownLatch mediaLatch = new CountDownLatch(1);
    private volatile CountDownLatch statusLatch = new CountDownLatch(1);
    private volatile CountDownLatch mediaStatusLatch = new CountDownLatch(1);
    private volatile String lastReceiverStatus = "";
    private volatile long mediaSessionId = -1;
    private volatile String playerState = "IDLE";
    private volatile String mediaError;
    /** Another app (e.g. Spotify) is playing on the speaker. */
    private volatile boolean otherAppRunning;

    public CastClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public String getHost() { return host; }
    public boolean isConnected() { return running && socket != null && !socket.isClosed(); }

    public void connect() throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            // Chromecasts use self-signed certificates.
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(0);
            s.startHandshake();
            socket = s;
            out = s.getOutputStream();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        running = true;
        send(SENDER, RECEIVER, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
        Thread reader = new Thread(this::readLoop, "cast-reader");
        reader.setDaemon(true);
        reader.start();
        Thread beat = new Thread(this::heartbeatLoop, "cast-heartbeat");
        beat.setDaemon(true);
        beat.start();
    }

    /**
     * Gets the speaker ready so Play starts faster: starts the media player on it now,
     * unless another app is using the speaker (then it waits until Play, so nothing is interrupted).
     * Returns true if the player is ready.
     */
    public boolean prepare() throws IOException {
        statusLatch = new CountDownLatch(1);
        send(SENDER, RECEIVER, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":" + requestId.getAndIncrement() + "}");
        try {
            statusLatch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
        if (transportId != null) {
            // Our player is already running (e.g. the radio is on): join it and ask what it is doing.
            send(SENDER, transportId, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            send(SENDER, transportId, NS_MEDIA, "{\"type\":\"GET_STATUS\",\"requestId\":" + requestId.getAndIncrement() + "}");
            return true;
        }
        if (otherAppRunning) return false;
        ensureApp();
        return true;
    }

    /** Launches the media receiver if needed and starts the stream. */
    public void play(String url, String title, String contentType) throws IOException {
        ensureApp();
        mediaLatch = new CountDownLatch(1);
        mediaError = null;
        String load = "{\"type\":\"LOAD\",\"requestId\":" + requestId.getAndIncrement()
                + ",\"autoplay\":true,\"currentTime\":0,\"media\":{\"contentId\":" + quote(url)
                + ",\"streamType\":\"LIVE\",\"contentType\":" + quote(contentType)
                + ",\"metadata\":{\"metadataType\":0,\"title\":" + quote(title) + "}}}";
        send(SENDER, transportId, NS_MEDIA, load);
        Timing.current.mark("Ström skickad till högtalaren");
        try {
            if (!mediaLatch.await(15, TimeUnit.SECONDS)) {
                throw new IOException("Högtalaren svarade inte på uppspelningen.");
            }
        } catch (InterruptedException e) {
            throw new IOException("Avbruten");
        }
        if (mediaError != null) throw new IOException(mediaError);
        Timing.current.mark("Högtalaren tog emot strömmen och buffrar");
    }

    public String getPlayerState() { return playerState; }

    /** Our player is running on the speaker (it may be silent). */
    public boolean isPlayerRunning() { return transportId != null; }

    /**
     * Stops the music but leaves the player running on the speaker, so the next Play starts quickly.
     */
    public void stopMedia() throws IOException {
        if (transportId == null) return;
        if (mediaSessionId < 0) {
            mediaStatusLatch = new CountDownLatch(1);
            send(SENDER, transportId, NS_MEDIA, "{\"type\":\"GET_STATUS\",\"requestId\":" + requestId.getAndIncrement() + "}");
            try { mediaStatusLatch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
        if (mediaSessionId < 0) return; // nothing loaded, so nothing to stop
        send(SENDER, transportId, NS_MEDIA, "{\"type\":\"STOP\",\"mediaSessionId\":" + mediaSessionId
                + ",\"requestId\":" + requestId.getAndIncrement() + "}");
    }

    /**
     * Asks a speaker what it is doing, without disturbing it. Blocking, a few seconds at most.
     * Returns null if the speaker cannot be reached.
     */
    public static SpeakerStatus probe(String host, int port) {
        CastClient c = new CastClient(host, port, new Listener() {
            @Override public void onMessage(String text) { }
            @Override public void onDisconnected(String reason) { }
        });
        try {
            c.connect();
            c.statusLatch = new CountDownLatch(1);
            c.send(SENDER, RECEIVER, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":1}");
            if (!c.statusLatch.await(3, TimeUnit.SECONDS)) return null;
            return describe(c.lastReceiverStatus);
        } catch (Exception e) {
            return null;
        } finally {
            c.close();
        }
    }

    /** Turns a RECEIVER_STATUS reply into a SpeakerStatus. */
    static SpeakerStatus describe(String receiverStatus) {
        String ours = appObject(receiverStatus, DEFAULT_MEDIA_RECEIVER);
        if (ours != null) {
            String t = field(ours, "statusText");
            return new SpeakerStatus(false, true, cleanStatus(t, "Radio"));
        }
        if (!receiverStatus.contains("\"appId\"") || receiverStatus.replace(" ", "").contains("\"isIdleScreen\":true")) {
            return new SpeakerStatus(true, false, "");
        }
        Matcher m = Pattern.compile("\"displayName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(receiverStatus);
        String name = m.find() ? m.group(1) : "";
        return new SpeakerStatus(false, false, name.isEmpty() ? "en annan app" : name);
    }

    /** "Now Casting: Radio Nova" or "Casting: Radio Nova" becomes "Radio Nova". */
    public static String cleanStatus(String statusText, String fallback) {
        if (statusText == null) return fallback;
        String t = statusText.replaceFirst("(?i)^(now )?casting:\\s*", "").trim();
        if (t.isEmpty() || t.equalsIgnoreCase("Ready To Cast") || t.equalsIgnoreCase("Default Media Receiver")) return fallback;
        return t;
    }

    /** Closes the player on the speaker completely, freeing the speaker for other apps. */
    public void stopReceiver() throws IOException {
        if (sessionId == null) requestStatus();
        if (sessionId != null) {
            send(SENDER, RECEIVER, NS_RECEIVER, "{\"type\":\"STOP\",\"sessionId\":" + quote(sessionId)
                    + ",\"requestId\":" + requestId.getAndIncrement() + "}");
        }
        transportId = null;
        sessionId = null;
        appLatch = new CountDownLatch(1);
    }

    public void setVolume(double level) throws IOException {
        level = Math.max(0, Math.min(1, level));
        send(SENDER, RECEIVER, NS_RECEIVER, "{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":"
                + String.format(java.util.Locale.ROOT, "%.2f", level) + "},\"requestId\":"
                + requestId.getAndIncrement() + "}");
    }

    public void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- internals

    private void requestStatus() throws IOException {
        send(SENDER, RECEIVER, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":" + requestId.getAndIncrement() + "}");
        try { appLatch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    private void ensureApp() throws IOException {
        if (transportId != null) return;
        appLatch = new CountDownLatch(1);
        send(SENDER, RECEIVER, NS_RECEIVER, "{\"type\":\"LAUNCH\",\"appId\":\"" + DEFAULT_MEDIA_RECEIVER
                + "\",\"requestId\":" + requestId.getAndIncrement() + "}");
        try {
            if (!appLatch.await(15, TimeUnit.SECONDS) || transportId == null) {
                throw new IOException("Kunde inte starta uppspelaren på högtalaren.");
            }
        } catch (InterruptedException e) {
            throw new IOException("Avbruten");
        }
        send(SENDER, transportId, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
        Timing.current.mark("Spelaren startad på högtalaren");
    }

    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(5000);
                if (running) send(SENDER, RECEIVER, NS_HEARTBEAT, "{\"type\":\"PING\"}");
            } catch (Exception e) {
                return;
            }
        }
    }

    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (running) {
                int len = in.readInt();
                if (len < 0 || len > 1 << 20) throw new IOException("Felaktigt meddelande");
                byte[] buf = new byte[len];
                in.readFully(buf);
                Message m = decode(buf);
                handle(m);
            }
        } catch (IOException e) {
            // Wake anything waiting for a reply so it fails right away.
            mediaError = "Anslutningen till högtalaren bröts.";
            mediaLatch.countDown();
            appLatch.countDown();
            statusLatch.countDown();
            mediaStatusLatch.countDown();
            if (running) {
                running = false;
                listener.onDisconnected("Anslutningen till högtalaren bröts.");
            }
        }
    }

    private void handle(Message m) throws IOException {
        String type = field(m.payload, "type");
        if (NS_HEARTBEAT.equals(m.namespace) && "PING".equals(type)) {
            send(SENDER, m.source, NS_HEARTBEAT, "{\"type\":\"PONG\"}");
        } else if (NS_RECEIVER.equals(m.namespace) && "RECEIVER_STATUS".equals(type)) {
            lastReceiverStatus = m.payload;
            boolean wasRunning = transportId != null;
            Matcher vol = Pattern.compile("\"volume\"\\s*:\\s*\\{[^}]*\"level\"\\s*:\\s*([0-9.]+)").matcher(m.payload);
            if (vol.find()) {
                try { listener.onVolume(Double.parseDouble(vol.group(1))); } catch (NumberFormatException ignored) { }
            }
            String app = appObject(m.payload, DEFAULT_MEDIA_RECEIVER);
            if (app != null) {
                otherAppRunning = false;
                String t = field(app, "transportId");
                if (t != null) {
                    transportId = t;
                    sessionId = field(app, "sessionId");
                    appLatch.countDown();
                }
            } else {
                transportId = null;
                sessionId = null;
                mediaSessionId = -1;
                otherAppRunning = m.payload.contains("\"appId\"")
                        && !m.payload.replace(" ", "").contains("\"isIdleScreen\":true");
                if (!"IDLE".equals(playerState)) {
                    playerState = "IDLE";
                    listener.onPlayerState("IDLE");
                }
            }
            if (wasRunning != (transportId != null)) listener.onPlayerRunning(transportId != null);
            statusLatch.countDown();
        } else if (NS_RECEIVER.equals(m.namespace) && "LAUNCH_ERROR".equals(type)) {
            appLatch.countDown();
        } else if (NS_MEDIA.equals(m.namespace)) {
            if ("MEDIA_STATUS".equals(type)) {
                Matcher id = Pattern.compile("\"mediaSessionId\"\\s*:\\s*(\\d+)").matcher(m.payload);
                if (id.find()) mediaSessionId = Long.parseLong(id.group(1));
                String state = field(m.payload, "playerState");
                if (state == null && m.payload.replace(" ", "").contains("\"status\":[]")) state = "IDLE";
                String url = field(m.payload, "contentId");
                if (url != null) {
                    String title = field(m.payload, "title");
                    listener.onMedia(unescape(url), title != null ? unescape(title) : null);
                }
                if (state != null && !state.equals(playerState)) {
                    playerState = state;
                    listener.onPlayerState(state);
                }
                mediaStatusLatch.countDown();
                mediaLatch.countDown();
            } else if ("LOAD_FAILED".equals(type) || "LOAD_CANCELLED".equals(type) || "INVALID_REQUEST".equals(type)) {
                mediaError = "Högtalaren kunde inte spela strömmen. Kontrollera länken.";
                mediaLatch.countDown();
            }
        } else if (NS_CONNECTION.equals(m.namespace) && "CLOSE".equals(type) && m.source.equals(transportId)) {
            transportId = null;
            sessionId = null;
        }
    }

    void send(String source, String dest, String namespace, String payload) throws IOException {
        byte[] body = encode(source, dest, namespace, payload);
        synchronized (writeLock) {
            if (out == null) throw new IOException("Inte ansluten");
            byte[] frame = new byte[4 + body.length];
            frame[0] = (byte) (body.length >>> 24);
            frame[1] = (byte) (body.length >>> 16);
            frame[2] = (byte) (body.length >>> 8);
            frame[3] = (byte) body.length;
            System.arraycopy(body, 0, frame, 4, body.length);
            try {
                out.write(frame);
                out.flush();
            } catch (IOException e) {
                close();
                throw e;
            }
        }
    }

    // --------------------------------------------------------- protobuf (CastMessage)

    static final class Message {
        String source = "", destination = "", namespace = "", payload = "";
    }

    static byte[] encode(String source, String dest, String namespace, String payload) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarintField(b, 1, 0);          // protocol_version = CASTV2_1_0
        writeStringField(b, 2, source);     // source_id
        writeStringField(b, 3, dest);       // destination_id
        writeStringField(b, 4, namespace);  // namespace
        writeVarintField(b, 5, 0);          // payload_type = STRING
        writeStringField(b, 6, payload);    // payload_utf8
        return b.toByteArray();
    }

    static Message decode(byte[] buf) throws IOException {
        Message m = new Message();
        int[] pos = {0};
        while (pos[0] < buf.length) {
            long key = readVarint(buf, pos);
            int fieldNo = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                readVarint(buf, pos);
            } else if (wire == 2) {
                int len = (int) readVarint(buf, pos);
                if (len < 0 || pos[0] + len > buf.length) throw new IOException("Felaktigt meddelande");
                String s = new String(buf, pos[0], len, StandardCharsets.UTF_8);
                pos[0] += len;
                switch (fieldNo) {
                    case 2: m.source = s; break;
                    case 3: m.destination = s; break;
                    case 4: m.namespace = s; break;
                    case 6: m.payload = s; break;
                    default: break;
                }
            } else {
                throw new IOException("Okänt fältformat");
            }
        }
        return m;
    }

    private static void writeVarintField(ByteArrayOutputStream b, int field, long value) {
        writeVarint(b, (field << 3));
        writeVarint(b, value);
    }

    private static void writeStringField(ByteArrayOutputStream b, int field, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarint(b, (field << 3) | 2);
        writeVarint(b, bytes.length);
        b.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream b, long v) {
        while ((v & ~0x7FL) != 0) {
            b.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        b.write((int) v);
    }

    private static long readVarint(byte[] buf, int[] pos) throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= buf.length) throw new IOException("Felaktigt meddelande");
            int x = buf[pos[0]++] & 0xFF;
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IOException("Felaktigt meddelande");
        }
    }

    // ------------------------------------------------------------- tiny JSON helpers

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Undoes JSON escapes in a string value (\\/ and \\" and \\uXXXX). */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) {
                try { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                catch (NumberFormatException e) { sb.append(n); }
            } else if (n == 'n') sb.append('\n');
            else if (n == 't') sb.append('\t');
            else sb.append(n);
        }
        return sb.toString();
    }

    /** First string value for key in the given JSON text (shallow, good enough for Cast replies). */
    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** The JSON object inside "applications" that contains the given appId, or null. */
    static String appObject(String json, String appId) {
        Matcher m = Pattern.compile("\"appId\"\\s*:\\s*\"" + Pattern.quote(appId) + "\"").matcher(json);
        if (!m.find()) return null;
        int start = -1, depth = 0;
        for (int i = m.start(); i >= 0; i--) {
            char c = json.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth == 0) { start = i; break; }
                depth--;
            }
        }
        if (start < 0) return null;
        depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }
}
