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
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * While casting: a notification and lock-screen controls (play / stop / disconnect),
 * and the phone's volume keys control the speaker's volume.
 */
public class CastControlService extends Service {

    private static final String CHANNEL = "casting";
    private static final int NOTIFICATION_ID = 2;
    private static final String ACTION_TOGGLE = "se.radiocast.cast.TOGGLE";
    private static final String ACTION_DISCONNECT = "se.radiocast.cast.DISCONNECT";
    private static final int VOLUME_STEPS = 20;

    private static volatile CastControlService running;

    private MediaSession session;
    private VolumeProvider volume;

    /** Updates the notification and lock screen, if the service is running. */
    static void refresh(Context context) {
        CastControlService s = running;
        if (s != null) MAIN.post(() -> { if (running == s) s.update(); });
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Castning", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Visas medan radion castas till en högtalare");
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "RadioCast-cast");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { stop(); }
            @Override public void onStop() { stop(); }
        });
        // Volume keys change the speaker's volume while casting.
        volume = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, VOLUME_STEPS, currentVolumeStep()) {
            @Override public void onSetVolumeTo(int v) { setSpeakerVolume(v); }
            @Override public void onAdjustVolume(int direction) { setSpeakerVolume(getCurrentVolume() + direction); }
        };
        session.setPlaybackToRemote(volume);
        session.setActive(true);
        running = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) {
            Radio r = Radio.peek();
            if (r != null && r.speakerMusicActive()) stop(); else play();
        } else if (ACTION_DISCONNECT.equals(action)) {
            Radio r = Radio.peek();
            if (r != null) r.cast.disconnect(false);
        }
        update();
        return START_NOT_STICKY;
    }

    private void play() {
        Radio r = Radio.peek();
        if (r == null || !r.casting()) return;
        Station s = r.nowPlaying;
        if (s != null) r.playOnSpeaker(s);
    }

    private void stop() {
        Radio r = Radio.peek();
        if (r != null && r.casting()) r.stopOnSpeaker();
    }

    private void setSpeakerVolume(int step) {
        int v = Math.max(0, Math.min(VOLUME_STEPS, step));
        volume.setCurrentVolume(v);
        Radio r = Radio.peek();
        if (r != null && r.casting()) r.cast.setVolume(v / (double) VOLUME_STEPS);
    }

    private int currentVolumeStep() {
        Radio r = Radio.peek();
        double level = r != null ? r.speakerVolume : -1;
        return level < 0 ? VOLUME_STEPS / 2 : (int) Math.round(level * VOLUME_STEPS);
    }

    /** Builds the notification and lock-screen state from what the speaker is doing. */
    private void update() {
        Radio r = Radio.peek();
        if (r == null) { stopSelf(); return; }
        boolean music = r.speakerMusicActive();
        boolean buffering = "BUFFERING".equals(r.speakerPlayerState);
        Station s = r.nowPlaying;
        String station = s != null ? StationGroups.shortName(s.name) : "RadioCast";
        String text = (music ? (buffering ? "Startar på " : "Spelar på ") : "Stoppad · ") + r.targetName();
        // With a known song: the title, then the artist, station and speaker below it.
        SongWatcher.Song song = music ? r.songs.current() : null;
        String title = song != null ? song.shortTitle() : station;
        if (song != null) text = (song.artist().isEmpty() ? "" : song.artist() + " · ") + station + " · " + text;

        volume.setCurrentVolume(currentVolumeStep());
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song != null && !song.artist().isEmpty() ? song.artist() : text)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, station + " · " + r.targetName())
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(music ? (buffering ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PLAYING)
                        : PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                .build());

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, CastControlService.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnect = PendingIntent.getService(this, 2,
                new Intent(this, CastControlService.class).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_line)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, music ? R.drawable.ic_stop : R.drawable.ic_play),
                        music ? "Stoppa" : "Spela", toggle).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_close), "Koppla från", disconnect).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        running = null;
        session.setActive(false);
        session.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
