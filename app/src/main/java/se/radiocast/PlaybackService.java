package se.radiocast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

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

    private static volatile Listener listener;
    private static volatile boolean playing;
    /** True from Play until stopped, including while the stream is still buffering. */
    private static volatile boolean active;
    private static volatile String currentTitle;

    private MediaPlayer player;
    private WifiManager.WifiLock wifiLock;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private float volume = 1f;
    private boolean noisyRegistered;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Headphones unplugged: stop instead of blasting the speaker.
            stopPlayback("Stoppad när hörlurarna kopplades ur.");
        }
    };

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
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Uppspelning", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Visas medan radion spelar på telefonen");
        nm.createNotificationChannel(ch);
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
            if (player != null) player.setVolume(volume, volume);
        } else {
            stopPlayback("Stoppad.");
        }
        return START_NOT_STICKY;
    }

    private void start(String url, String title) {
        releasePlayer();
        currentTitle = title;
        if (!requestFocus()) {
            stopPlayback("En annan app använder ljudet just nu.");
            return;
        }
        notifyState(false, "Ansluter till " + title + "…");
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(mp -> {
            mp.setVolume(volume, volume);
            mp.start();
            playing = true;
            goForeground(title, "Spelar på telefonen");
            notifyState(true, "Spelar " + title + " på telefonen.");
        });
        player.setOnErrorListener((mp, what, extra) -> {
            stopPlayback("Strömmen gick inte att spela. Kontrollera länken eller nätet.");
            return true;
        });
        player.setOnCompletionListener(mp -> stopPlayback("Sändningen tog slut."));
        try {
            player.setDataSource(url);
            player.prepareAsync();
        } catch (Exception e) {
            stopPlayback("Strömmen gick inte att öppna.");
            return;
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wifiLock == null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radiocast");
            wifiLock.setReferenceCounted(false);
        }
        if (wifiLock != null) wifiLock.acquire();
        if (!noisyRegistered) {
            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(noisy, f);
            noisyRegistered = true;
        }
    }

    private boolean requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS) {
                            stopPlayback("Stoppad eftersom en annan app började spela.");
                        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            if (player != null) player.setVolume(volume * 0.3f, volume * 0.3f);
                        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                            if (player != null) player.setVolume(volume, volume);
                        }
                    })
                    .build();
        }
        return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void stopPlayback(String message) {
        releasePlayer();
        if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        if (noisyRegistered) {
            try { unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) { }
            noisyRegistered = false;
        }
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
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
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
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Stoppa", stopPi).build())
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
        if (noisyRegistered) {
            try { unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) { }
            noisyRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
