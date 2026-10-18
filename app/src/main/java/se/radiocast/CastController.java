package se.radiocast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * What the cast button controls: which speaker the app is connected to,
 * and playback on it. All network work runs on the given executor;
 * listener callbacks also arrive there.
 */
public class CastController {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    public interface Listener {
        void onStateChanged(State state, CastDevice device);
        void onStatus(String message);
    }

    private final Executor worker;
    private final Listener listener;
    private volatile CastClient client;
    private volatile CastDevice device;
    private volatile State state = State.DISCONNECTED;
    /** Name of what was last started on the speaker, for status texts. */
    private volatile String currentTitle;

    public CastController(Executor worker, Listener listener) {
        this.worker = worker;
        this.listener = listener;
    }

    public State getState() { return state; }
    public CastDevice getDevice() { return device; }

    /** Connects to a speaker (dropping any current one). Runs afterConnect on success. */
    public void connect(CastDevice d, Runnable afterConnect) {
        worker.execute(() -> {
            if (state == State.CONNECTED && device != null && device.key().equals(d.key()) && isLive()) {
                listener.onStatus("Redan ansluten till " + d.name + ".");
                if (afterConnect != null) afterConnect.run();
                return;
            }
            closeClient();
            device = d;
            setState(State.CONNECTING);
            listener.onStatus("Ansluter till " + d.name + "…");
            if (open(d)) {
                Timing.current.mark("Ansluten till " + d.name);
                // Start the speaker's player now, so pressing Play is quicker.
                try {
                    CastClient c = client;
                    if (c != null) c.prepare();
                } catch (IOException ignored) {
                    // Not fatal: the player is started on Play instead.
                }
                listener.onStatus("Ansluten till " + d.name + ".");
                if (afterConnect != null) afterConnect.run();
            } else {
                device = null;
                setState(State.DISCONNECTED);
                listener.onStatus("Når inte " + d.name + ". Är den på och på samma wifi?");
            }
        });
    }

    /**
     * Lets go of the speaker. With turnOffPlayer the player on the speaker is closed completely
     * (frees it for other apps); otherwise the radio keeps playing there.
     */
    public void disconnect(boolean turnOffPlayer) {
        worker.execute(() -> {
            CastClient c = client;
            String name = device != null ? device.name : "högtalaren";
            if (c != null && turnOffPlayer) {
                try { c.stopReceiver(); } catch (IOException ignored) { }
            }
            closeClient();
            device = null;
            setState(State.DISCONNECTED);
            listener.onStatus(turnOffPlayer ? "Spelaren på " + name + " är avstängd."
                    : "Frånkopplad. Radion spelar vidare på " + name + ".");
        });
    }

    public void play(String url, String title) {
        worker.execute(() -> {
            CastClient c = liveClient();
            if (c == null) return;
            listener.onStatus("Startar " + title + "…");
            currentTitle = title;
            try {
                c.play(url, title, contentType(url));
                // The speaker has the stream but is still filling its buffer; "Spelar" comes with the sound.
                if (!"PLAYING".equals(c.getPlayerState())) listener.onStatus("Buffrar " + title + "…");
            } catch (IOException e) {
                failed(e);
            }
        });
    }

    /** Stops the music but keeps the speaker's player running, so the next Play is quick. */
    public void stop() {
        worker.execute(() -> {
            CastClient c = liveClient();
            if (c == null) return;
            try {
                c.stopMedia();
                listener.onStatus("Stoppad. Högtalaren är redo, så nästa start går fort.");
            } catch (IOException e) {
                failed(e);
            }
        });
    }

    public void setVolume(double level) {
        worker.execute(() -> {
            CastClient c = liveClient();
            if (c == null) return;
            try {
                c.setVolume(level);
                listener.onStatus("Volym " + Math.round(level * 100) + " %.");
            } catch (IOException e) {
                failed(e);
            }
        });
    }

    // ---------------------------------------------------------------- internals (worker thread)

    private boolean isLive() {
        CastClient c = client;
        return c != null && c.isConnected();
    }

    /** The current connection, reconnecting once if it has dropped. Null if there is no speaker. */
    private CastClient liveClient() {
        if (isLive()) return client;
        CastDevice d = device;
        if (d == null) {
            listener.onStatus("Tryck på cast-knappen och välj en högtalare.");
            return null;
        }
        setState(State.CONNECTING);
        if (open(d)) return client;
        device = null;
        setState(State.DISCONNECTED);
        listener.onStatus("Tappade kontakten med " + d.name + ". Tryck på cast-knappen för att ansluta igen.");
        return null;
    }

    private boolean open(CastDevice d) {
        final CastClient[] holder = new CastClient[1];
        CastClient c = new CastClient(d.host, d.port, new CastClient.Listener() {
            @Override public void onMessage(String text) { }
            @Override public void onPlayerState(String playerState) {
                worker.execute(() -> {
                    if (client != holder[0] || !"PLAYING".equals(playerState)) return;
                    long ms = Timing.current.sound("Ljud på högtalaren");
                    CastDevice dev = device;
                    String where = dev != null ? " på " + dev.name : "";
                    String t = currentTitle != null ? currentTitle : "radio";
                    listener.onStatus("Spelar " + t + where + (ms >= 0 ? " · ljud efter " + Timing.seconds(ms) : "") + ".");
                });
            }
            @Override public void onDisconnected(String reason) {
                worker.execute(() -> {
                    if (client == holder[0] && client != null) {
                        client = null;
                        device = null;
                        setState(State.DISCONNECTED);
                        listener.onStatus("Anslutningen till högtalaren bröts. Tryck på cast-knappen för att ansluta igen.");
                    }
                });
            }
        });
        holder[0] = c;
        try {
            c.connect();
        } catch (IOException e) {
            c.close();
            return false;
        }
        client = c;
        setState(State.CONNECTED);
        return true;
    }

    private void failed(IOException e) {
        if (!isLive()) {
            closeClient();
            device = null;
            setState(State.DISCONNECTED);
        }
        listener.onStatus(e.getMessage() != null ? e.getMessage() : "Det gick inte att nå högtalaren.");
    }

    private void closeClient() {
        CastClient c = client;
        client = null;
        if (c != null) c.close();
    }

    private void setState(State s) {
        state = s;
        listener.onStateChanged(s, device);
    }

    static String contentType(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        if (u.contains(".aac")) return "audio/aac";
        if (u.contains(".ogg")) return "audio/ogg";
        return "audio/mpeg";
    }
}
