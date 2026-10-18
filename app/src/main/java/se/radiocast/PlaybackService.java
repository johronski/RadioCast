package se.radiocast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

/** Plays a radio stream on the phone itself, also when the app is in the background. */
public class PlaybackService extends Service {

    public interface Listener {
        void onLocalState(boolean playing, String title, String message);
    }

    static final String ACTION_PLAY = "se.radiocast.PLAY";
    static final String ACTION_STOP = "se.radiocast.STOP";
    static final String ACTION_VOLUME = "se.radiocast.VOLUME";
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_VOLUME = "volume";

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;

    // Start after half a second of audio instead of the usual several seconds.
    private static final int START_BUFFER_MS = 500;
    private static final int REBUFFER_MS = 2000;
    private static final int MIN_BUFFER_MS = 15000;
    private static final int MAX_BUFFER_MS = 30000;

    private static volatile Listener listener;
    private static volatile boolean playing;
    /** True from Play until stopped, including while the stream is still buffering. */
    private static volatile boolean active;
    private static volatile String currentTitle;

    private ExoPlayer player;
    private float volume = 1f;
    /** Lock-screen and headset controls for playback on the phone. */
    private MediaSession session;
    private String currentUrl;

    public static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onLocalState(playing, currentTitle, null);
    }

    public static boolean isActive() { return active; }

    public static void play(Context c, String url, String title, float volume) {
        active = true;
        Intent i = new Intent(c, PlaybackService.class).setAction(ACTION_PLAY)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_VOLUME, volume);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        if (!active) return;
        c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_STOP));
    }

    public static void setVolume(Context c, float volume) {
        if (!active) return;
        c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_VOLUME).putExtra(EXTRA_VOLUME, volume));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Uppspelning", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Visas medan radion spelar på telefonen");
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "RadioCast-phone");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { stopPlayback("Stoppad."); }
            @Override public void onStop() { stopPlayback("Stoppad."); }
            @Override public void onPlay() {
                if (currentUrl != null && currentTitle != null) start(currentUrl, currentTitle);
            }
        });
    }

    private void updateSession(int state) {
        if (session == null) return;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle != null ? currentTitle : "RadioCast")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Spelar i telefonen")
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                .build());
        session.setActive(state != PlaybackState.STATE_STOPPED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            String title = intent.getStringExtra(EXTRA_TITLE);
            volume = intent.getFloatExtra(EXTRA_VOLUME, 1f);
            goForeground(title, "Ansluter…");
            start(url, title);
        } else if (ACTION_VOLUME.equals(action)) {
            volume = intent.getFloatExtra(EXTRA_VOLUME, volume);
            if (player != null) player.setVolume(volume);
        } else {
            stopPlayback("Stoppad.");
        }
        return START_NOT_STICKY;
    }

    private ExoPlayer player() {
        if (player == null) {
            DefaultLoadControl load = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                    .build();
            player = new ExoPlayer.Builder(this)
                    .setLoadControl(load)
                    // ExoPlayer handles audio focus (ducking, phone calls) and unplugged headphones.
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(), true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .build();
            player.addListener(new Player.Listener() {
                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) {
                        playing = true;
                        long ms = Timing.current.sound("Ljud i telefonen");
                        goForeground(currentTitle, "Spelar i telefonen");
                        updateSession(PlaybackState.STATE_PLAYING);
                        notifyState(true, "Spelar " + currentTitle + " i telefonen"
                                + (ms >= 0 ? " · ljud efter " + Timing.seconds(ms) : "") + ".");
                    }
                }

                @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                    if (playWhenReady) return;
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                        stopPlayback("Stoppad när hörlurarna kopplades ur.");
                    } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                        stopPlayback("Stoppad eftersom en annan app började spela.");
                    }
                }

                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) Timing.current.mark("Strömmen ansluten och buffrad");
                    if (state == Player.STATE_ENDED) stopPlayback("Sändningen tog slut.");
                }

                @Override public void onPlayerError(PlaybackException error) {
                    stopPlayback("Strömmen gick inte att spela. Kontrollera länken eller nätet.");
                }
            });
        }
        return player;
    }

    private void start(String url, String title) {
        Timing.current.mark("Telefonens spelare startad");
        currentTitle = title;
        currentUrl = url;
        updateSession(PlaybackState.STATE_BUFFERING);
        playing = false;
        notifyState(false, "Ansluter till " + title + "…");
        ExoPlayer p = player();
        p.setVolume(volume);
        p.setMediaItem(MediaItem.fromUri(url));
        p.prepare();
        p.setPlayWhenReady(true);
    }

    private void stopPlayback(String message) {
        releasePlayer();
        updateSession(PlaybackState.STATE_STOPPED);
        active = false;
        currentTitle = null;
        notifyState(false, message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releasePlayer() {
        playing = false;
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
    }

    private void notifyState(boolean isPlaying, String message) {
        Listener l = listener;
        if (l != null) l.onLocalState(isPlaying, currentTitle, message);
    }

    private void goForeground(String title, String text) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title != null ? title : "RadioCast")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop), "Stoppa", stopPi).build())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0))
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        active = false;
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
