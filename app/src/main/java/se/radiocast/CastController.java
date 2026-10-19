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
        /**
         * What the connected speaker is doing: whether our player is running on it,
         * and its state (PLAYING, BUFFERING, PAUSED or IDLE).
         */
        default void onSpeakerState(boolean playerRunning, String playerState) { }
        /** The connected speaker's volume, 0–1. */
        default void onSpeakerVolume(double level) { }
        /** The stream the speaker's player has loaded (also one started before the app was opened). */
        default void onSpeakerMedia(String url, String title) { }
    }

    private final Executor worker;
    private final Listener listener;
    private volatile CastClient client;
    private volatile CastDevice device;
    private volatile State state = State.DISCONNECTED;
    /** Name of what was last started on the speaker, for status texts. */
    private volatile String currentTitle;
    /** Asks the speaker for its position while waiting for the sound to really start. */
    private final java.util.concurrent.ScheduledExecutorService poller =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cast-position");
                t.setDaemon(true);
                return t;
            });
    private volatile java.util.concurrent.ScheduledFuture<?> polling;
    /** When the speaker said PLAYING, and the first position it reported after that. */
    private volatile long playingAt = -1;
    private volatile double firstPosition = -1;

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
        disconnect(turnOffPlayer, null);
    }

    /** As {@link #disconnect(boolean)}, then runs {@code after} (on the worker) once disconnected. */
    public void disconnect(boolean turnOffPlayer, Runnable after) {
        if (after != null) {
            disconnect(turnOffPlayer, null);
            worker.execute(after);
            return;
        }
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

    /**
     * Turns off the player on the speaker (frees it for other apps) but stays connected,
     * so pressing Play starts it again.
     */
    public void stopDevice() {
        worker.execute(() -> {
            CastClient c = liveClient();
            if (c == null) return;
            CastDevice d = device;
            try {
                c.stopReceiver();
                listener.onSpeakerState(false, "IDLE");
                listener.onStatus("Chromecast-enheten " + (d != null ? d.name + " " : "")
                        + "är avstängd och ledig för andra appar.");
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
            @Override public void onVolume(double level) {
                worker.execute(() -> { if (client == holder[0]) listener.onSpeakerVolume(level); });
            }
            @Override public void onMedia(String url, String title) {
                worker.execute(() -> { if (client == holder[0]) listener.onSpeakerMedia(url, title); });
            }
            @Override public void onPlayerRunning(boolean running) {
                worker.execute(() -> {
                    if (client == holder[0]) listener.onSpeakerState(running, holder[0].getPlayerState());
                });
            }
            @Override public void onPlayerState(String playerState) {
                worker.execute(() -> {
                    if (client != holder[0]) return;
                    listener.onSpeakerState(holder[0].isPlayerRunning(), playerState);
                    if (!"PLAYING".equals(playerState)) { stopPolling(); return; }
                    if (!Timing.current.waitingForSound()) { announcePlaying(-1); return; }
                    // The speaker says PLAYING when its player starts, which can be before anything is heard.
                    // The sound counts as started when the playback position starts moving.
                    Timing.current.mark("Högtalaren säger att den spelar");
                    playingAt = Timing.now();
                    firstPosition = -1;
                    startPolling(holder[0]);
                });
            }
            @Override public void onPosition(double seconds) {
                worker.execute(() -> {
                    if (client != holder[0] || polling == null) return;
                    if (firstPosition < 0) { firstPosition = seconds; return; }
                    if (seconds - firstPosition < 0.2) return;
                    // Moving: the sound started about (seconds - firstPosition) before now, at the earliest at PLAYING.
                    long began = Math.max(playingAt, Timing.now() - Math.round((seconds - firstPosition) * 1000));
                    stopPolling();
                    announcePlaying(Timing.current.soundAt("Ljud på högtalaren (positionen rör sig)", began));
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

    private void startPolling(CastClient c) {
        stopPolling();
        long deadline = Timing.now() + 8000;
        polling = poller.scheduleWithFixedDelay(() -> {
            if (Timing.now() > deadline) {
                worker.execute(() -> {
                    if (polling == null) return;
                    stopPolling();
                    // The position never moved (some streams do not report it): count from PLAYING.
                    announcePlaying(Timing.current.soundAt("Ljud på högtalaren (enligt högtalaren)", playingAt));
                });
                return;
            }
            try { c.requestMediaStatus(); } catch (IOException ignored) { }
        }, 0, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        java.util.concurrent.ScheduledFuture<?> p = polling;
        polling = null;
        if (p != null) p.cancel(false);
    }

    private void announcePlaying(long ms) {
        CastDevice dev = device;
        String where = dev != null ? " på " + dev.name : "";
        String t = currentTitle != null ? currentTitle : "radio";
        listener.onStatus("Spelar " + t + where + (ms >= 0 ? " · ljud efter " + Timing.seconds(ms) : "") + ".");
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
        CastClient c = client;
        if (s == State.CONNECTED && c != null) listener.onSpeakerState(c.isPlayerRunning(), c.getPlayerState());
        if (s == State.DISCONNECTED) listener.onSpeakerState(false, "IDLE");
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
