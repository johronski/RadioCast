package se.radiocast;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide radio state that outlives the main screen: the cast connection, what the speaker
 * is doing, and what is playing. The notification and lock-screen controls use it too.
 */
public final class Radio {

    private static Radio instance;

    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    public final CastController cast;
    /** Which song is playing, when the station tells it. */
    public final SongWatcher songs;
    private final Context app;
    private final List<CastController.Listener> listeners = new CopyOnWriteArrayList<>();

    /** Whether our player is running on the connected speaker, and its state (PLAYING, BUFFERING, IDLE…). */
    public volatile boolean speakerPlayerRunning;
    public volatile String speakerPlayerState = "IDLE";
    /** The connected speaker's volume, 0–1, or -1 when unknown. */
    public volatile double speakerVolume = -1;
    /** What is playing or played last (also a station only tried out from the search). */
    public volatile Station nowPlaying;
    /**
     * The speaker let go of after a while without music (to save battery). Play reconnects to it,
     * and the cast button still shows it. Cleared when another output is chosen.
     */
    public volatile CastDevice resting;

    private static final long IDLE_MS = 15 * 60_000;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idle = this::goIdle;

    private void goIdle() {
        if (casting() && !speakerMusicActive()) {
            resting = cast.getDevice();
            cast.disconnectIdle();
        }
    }

    /** Starts the idle countdown when the speaker is connected but silent, cancels it otherwise. */
    private void watchIdle() {
        main.removeCallbacks(idle);
        if (casting() && !speakerMusicActive()) main.postDelayed(idle, IDLE_MS);
    }

    private Radio(Context context) {
        app = context.getApplicationContext();
        cast = new CastController(worker, new CastController.Listener() {
            @Override public void onStateChanged(CastController.State state, CastDevice device) {
                if (state == CastController.State.DISCONNECTED) {
                    speakerPlayerRunning = false;
                    speakerPlayerState = "IDLE";
                    speakerVolume = -1;
                }
                if (state == CastController.State.CONNECTED) resting = null;
                updateControls(state);
                watchIdle();
                for (CastController.Listener l : listeners) l.onStateChanged(state, device);
            }
            @Override public void onStatus(String message) {
                for (CastController.Listener l : listeners) l.onStatus(message);
            }
            @Override public void onSpeakerState(boolean playerRunning, String playerState) {
                speakerPlayerRunning = playerRunning;
                speakerPlayerState = playerState;
                CastControlService.refresh(app);
                watchIdle();
                if (songs != null) songs.poke();
                for (CastController.Listener l : listeners) l.onSpeakerState(playerRunning, playerState);
            }
            @Override public void onSpeakerMedia(String url, String title) {
                Station s = nowPlaying;
                if (s == null || !s.url.equals(url)) {
                    // Started before the app was opened: name it from the speaker until the list knows it.
                    nowPlaying = new Station(title != null && !title.isEmpty() ? title : "Radio", url, "", "", 0);
                    CastControlService.refresh(app);
                }
                for (CastController.Listener l : listeners) l.onSpeakerMedia(url, title);
            }
            @Override public void onSpeakerVolume(double level) {
                speakerVolume = level;
                CastControlService.refresh(app);
                for (CastController.Listener l : listeners) l.onSpeakerVolume(level);
            }
        });
        songs = new SongWatcher(app, this);
    }

    public static synchronized Radio get(Context context) {
        if (instance == null) instance = new Radio(context);
        return instance;
    }

    /** The radio if it has been started in this process, else null (used by the notification). */
    static synchronized Radio peek() { return instance; }

    public void addListener(CastController.Listener l) { listeners.add(l); }
    public void removeListener(CastController.Listener l) { listeners.remove(l); }

    public boolean casting() { return cast.getState() == CastController.State.CONNECTED; }

    public String targetName() {
        CastDevice d = cast.getDevice();
        return d != null ? d.name : "högtalaren";
    }

    /** Music is playing or starting on the speaker. */
    public boolean speakerMusicActive() {
        return casting() && (speakerPlayerState.equals("PLAYING") || speakerPlayerState.equals("BUFFERING")
                || speakerPlayerState.equals("PAUSED"));
    }

    /** Plays a station on the connected speaker (used by the screen and by the media controls). */
    public void playOnSpeaker(Station s) {
        nowPlaying = s;
        String name = StationGroups.shortName(s.name);
        Timing.current.start(name + " på " + targetName());
        speakerPlayerState = "BUFFERING"; // until the speaker reports
        watchIdle();
        cast.play(s.url, name);
        CastControlService.refresh(app);
    }

    /** Stops the music on the speaker; its player stays ready so the next start is quick. */
    public void stopOnSpeaker() {
        cast.stop();
        speakerPlayerState = "IDLE";
        watchIdle();
        CastControlService.refresh(app);
    }

    /** While casting, a notification with lock-screen controls and volume keys for the speaker. */
    private void updateControls(CastController.State state) {
        if (state == CastController.State.CONNECTED) {
            try {
                Intent i = new Intent(app, CastControlService.class);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
            } catch (RuntimeException e) {
                // Android may refuse to start it from the background; the app still works without it.
            }
        } else if (state == CastController.State.DISCONNECTED) {
            app.stopService(new Intent(app, CastControlService.class));
        }
    }
}
